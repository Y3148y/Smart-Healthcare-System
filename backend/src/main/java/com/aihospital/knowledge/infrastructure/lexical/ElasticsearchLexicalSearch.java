package com.aihospital.knowledge.infrastructure.lexical;

import com.aihospital.knowledge.domain.Bm25Retriever;
import com.aihospital.knowledge.domain.LexicalSearch;
import com.aihospital.shared.model.Models.Evidence;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.ConnectException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.*;

/** Elasticsearch owns analysis, Lucene postings and BM25. Only approved snapshot documents are queried. */
@Component
@ConditionalOnProperty(name = "ai.retrieval.lexical-backend", havingValue = "elasticsearch", matchIfMissing = true)
public class ElasticsearchLexicalSearch implements LexicalSearch {
    private static final String SCHEMA = "cjk-bm25-v1";
    private final ObjectMapper json;
    private final java.util.function.BooleanSupplier containerRuntime;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    @Value("${ai.elasticsearch.url:http://127.0.0.1:9201}") private String url = "http://127.0.0.1:9201";
    @Value("${ai.elasticsearch.api-key:}") private String apiKey = "";
    @Value("${ai.elasticsearch.index-prefix:ai-hospital-lexical}") private String prefix = "ai-hospital-lexical";
    @Value("${ai.elasticsearch.min-score:0}") private double minimum = 0;
    @Value("${ai.elasticsearch.title-boost:2}") private double titleBoost = 2;
    @Value("${ai.elasticsearch.timeout-seconds:5}") private int timeoutSeconds = 5;
    @Value("${ai.demo-local-container:false}") private boolean localContainerDemo;
    private volatile String installedSnapshot;

    @org.springframework.beans.factory.annotation.Autowired
    public ElasticsearchLexicalSearch(ObjectMapper json) {
        this(json, () -> java.nio.file.Files.isRegularFile(java.nio.file.Path.of("/.dockerenv")));
    }
    ElasticsearchLexicalSearch(ObjectMapper json, java.util.function.BooleanSupplier containerRuntime) {
        this.json = json; this.containerRuntime = containerRuntime;
    }

    @PostConstruct public void validate() {
        URI endpoint = URI.create(url);
        boolean loopback = endpoint.getHost() != null && Set.of("localhost", "127.0.0.1", "::1", "[::1]").contains(endpoint.getHost());
        boolean localComposeService = localContainerDemo && containerRuntime.getAsBoolean()
                && "elasticsearch".equals(endpoint.getHost()) && endpoint.getPort() == 9200 && apiKey.isBlank();
        if (endpoint.getHost() == null || endpoint.getUserInfo() != null || endpoint.getQuery() != null
                || endpoint.getFragment() != null || !Set.of("", "/").contains(endpoint.getPath())
                || !("https".equals(endpoint.getScheme()) || "http".equals(endpoint.getScheme()) && (loopback || localComposeService))
                || !prefix.matches("[a-z][a-z0-9-]{0,100}") || !Double.isFinite(minimum) || minimum < 0
                || !Double.isFinite(titleBoost) || titleBoost <= 0 || titleBoost > 10
                || timeoutSeconds < 1 || timeoutSeconds > 30
                || apiKey.chars().anyMatch(c -> Character.isWhitespace(c) || Character.isISOControl(c)))
            throw new IllegalArgumentException("Invalid Elasticsearch configuration");
        url = url.replaceAll("/+$", "");
    }

    @Override public Result search(String query, List<Evidence> approved, int limit, double ignoredMemoryMinimum) {
        if (approved.isEmpty()) return new Result(List.of(), "ELASTICSEARCH_EMPTY_CORPUS", Map.of());
        try {
            var active = new TreeMap<String, Evidence>();
            for (Evidence evidence : approved) active.put(digest(Bm25Retriever.key(evidence)), evidence);
            String snapshot = digest(SCHEMA + "\n" + String.join("\n", active.keySet()));
            String index = prefix + "-" + snapshot;
            ensureInstalled(index, snapshot, active);
            var request = Map.of("size", Math.max(1, Math.min(100, limit)), "min_score", minimum,
                    "sort", List.of(Map.of("_score", "desc"), Map.of("chunk_id", "asc")),
                    "query", Map.of("multi_match", Map.of("query", query, "type", "best_fields",
                            "fields", List.of("title^" + titleBoost, "body"))));
            JsonNode response = send("POST", "/" + index + "/_search", json.writeValueAsString(request), "application/json", 200);
            if (response.path("timed_out").asBoolean() || response.path("_shards").path("failed").asInt() > 0)
                return new Result(List.of(), "ELASTICSEARCH_PARTIAL_RESULT", Map.of());
            JsonNode hits = response.path("hits").path("hits");
            if (!hits.isArray() || hits.size() > Math.max(1, Math.min(100, limit)))
                return new Result(List.of(), "ELASTICSEARCH_INVALID_RESPONSE", Map.of());
            var selected = new ArrayList<Evidence>();
            var seen = new HashSet<String>();
            for (JsonNode hit : hits) {
                String id = hit.path("_id").asText();
                Evidence evidence = active.get(id);
                JsonNode source = hit.path("_source");
                double score = hit.path("_score").asDouble(Double.NaN);
                if (evidence == null || !seen.add(id) || !Double.isFinite(score) || score <= 0 || score < minimum
                        || !source.path("title").asText().equals(evidence.title())
                        || !source.path("body").asText().equals(evidence.excerpt())
                        || !source.path("source").asText().equals(evidence.source())
                        || !source.path("chunk_id").asText().equals(id))
                    return new Result(List.of(), "ELASTICSEARCH_INVALID_RESPONSE", Map.of());
                selected.add(new Evidence(evidence.title(), evidence.source(), evidence.excerpt(), score));
            }
            return new Result(selected, "ELASTICSEARCH_READY", Map.of());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new Result(List.of(), "ELASTICSEARCH_INTERRUPTED", Map.of());
        } catch (HttpConnectTimeoutException timeout) {
            return new Result(List.of(), "ELASTICSEARCH_CONNECT_TIMEOUT", Map.of());
        } catch (HttpTimeoutException timeout) {
            return new Result(List.of(), "ELASTICSEARCH_REQUEST_TIMEOUT", Map.of());
        } catch (ConnectException refused) {
            return new Result(List.of(), "ELASTICSEARCH_CONNECTION_REFUSED", Map.of());
        } catch (ElasticsearchHttpStatusException response) {
            return new Result(List.of(), "ELASTICSEARCH_HTTP_" + response.status(), Map.of());
        } catch (Exception failure) {
            installedSnapshot = null;
            // No provider response, query, URL or credential in diagnostics; never silently switch engines.
            return new Result(List.of(), "ELASTICSEARCH_UNAVAILABLE", Map.of());
        }
    }

    @Override public synchronized boolean verify(List<Evidence> corpus) {
        if(corpus.isEmpty())return true;
        boolean verified=false;
        try {
            var active=new TreeMap<String,Evidence>();for(var item:corpus)active.put(digest(Bm25Retriever.key(item)),item);
            String snapshot=digest(SCHEMA+"\n"+String.join("\n",active.keySet())),index=prefix+"-"+snapshot;
            var metadata=send("GET","/"+index,"","application/json",200).path(index).path("mappings").path("_meta");
            if(!SCHEMA.equals(metadata.path("schema").asText())||!snapshot.equals(metadata.path("snapshot").asText()))return false;
            if(send("GET","/"+index+"/_count","","application/json",200).path("count").asLong(-1)!=active.size())return false;
            var ids=new ArrayList<>(active.keySet());var seen=new HashSet<String>();
            for(int offset=0;offset<ids.size();offset+=100) {
                var batch=ids.subList(offset,Math.min(ids.size(),offset+100));
                var fetched=send("POST","/"+index+"/_mget",json.writeValueAsString(Map.of("ids",batch)),"application/json",200).path("docs");
                if(!fetched.isArray()||fetched.size()!=batch.size())return false;
                for(var document:fetched) {
                    String id=document.path("_id").asText();var expected=active.get(id);var source=document.path("_source");
                    if(expected==null||!batch.contains(id)||!seen.add(id)||!document.path("found").asBoolean()
                            ||!expected.title().equals(source.path("title").asText())||!expected.source().equals(source.path("source").asText())
                            ||!expected.excerpt().equals(source.path("body").asText())||!id.equals(source.path("chunk_id").asText()))return false;
                }
            }
            verified=seen.size()==active.size();return verified;
        } catch(InterruptedException interrupted){Thread.currentThread().interrupt();return false;}
        catch(Exception unavailable){return false;}
        finally{if(!verified)installedSnapshot=null;}
    }
    private synchronized void ensureInstalled(String index, String snapshot, Map<String, Evidence> active) throws Exception {
        if (index.equals(installedSnapshot)) return;
        var definition = Map.of("settings", Map.of("number_of_shards", 1, "number_of_replicas", 0,
                        "similarity", Map.of("medical_bm25", Map.of("type", "BM25", "k1", 1.2, "b", .75))),
                "mappings", Map.of("_meta", Map.of("schema", SCHEMA, "snapshot", snapshot), "dynamic", "strict",
                        "properties", Map.of("chunk_id", Map.of("type", "keyword"), "source", Map.of("type", "keyword", "index", false),
                                "title", Map.of("type", "text", "analyzer", "cjk", "similarity", "medical_bm25"),
                                "body", Map.of("type", "text", "analyzer", "cjk", "similarity", "medical_bm25"))));
        var create = raw("PUT", "/" + index, json.writeValueAsString(definition), "application/json");
        if (create.statusCode() != 200 && create.statusCode() != 201) {
            JsonNode error = json.readTree(create.body()).path("error");
            if (create.statusCode() != 400 || !"resource_already_exists_exception".equals(error.path("type").asText()))
                throw new IllegalStateException("Index creation failed");
            JsonNode mapping = send("GET", "/" + index + "/_mapping", "", "application/json", 200);
            JsonNode meta = mapping.path(index).path("mappings").path("_meta");
            if (!SCHEMA.equals(meta.path("schema").asText()) || !snapshot.equals(meta.path("snapshot").asText()))
                throw new IllegalStateException("Index metadata mismatch");
        }
        var entries = new ArrayList<>(active.entrySet());
        for (int start = 0; start < entries.size(); start += 100) {
            var bulk = new StringBuilder();
            for (var entry : entries.subList(start, Math.min(start + 100, entries.size()))) {
                Evidence evidence = entry.getValue();
                bulk.append(json.writeValueAsString(Map.of("index", Map.of("_id", entry.getKey())))).append('\n');
                bulk.append(json.writeValueAsString(Map.of("chunk_id", entry.getKey(), "title", evidence.title(),
                        "body", evidence.excerpt(), "source", evidence.source()))).append('\n');
            }
            JsonNode response = send("POST", "/" + index + "/_bulk?refresh=wait_for", bulk.toString(), "application/x-ndjson", 200);
            if (!response.has("errors") || response.path("errors").asBoolean(true)) throw new IllegalStateException("Bulk indexing failed");
            JsonNode items = response.path("items");
            if (!items.isArray() || items.size() != Math.min(100, entries.size() - start))
                throw new IllegalStateException("Incomplete bulk response");
            for (JsonNode item : items) {
                int status = item.path("index").path("status").asInt();
                if (status != 200 && status != 201) throw new IllegalStateException("Bulk item failed");
            }
        }
        installedSnapshot = index;
    }

    private JsonNode send(String method, String path, String body, String contentType, int expected) throws Exception {
        var response = raw(method, path, body, contentType);
        if (response.statusCode() != expected) throw new ElasticsearchHttpStatusException(response.statusCode());
        return json.readTree(response.body());
    }
    private HttpResponse<String> raw(String method, String path, String body, String contentType) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(url + path)).timeout(Duration.ofSeconds(timeoutSeconds))
                .header("Content-Type", contentType);
        if (!apiKey.isBlank()) request.header("Authorization", "ApiKey " + apiKey);
        return http.send(request.method(method, HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }
    private static String digest(String value) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    }

    private static final class ElasticsearchHttpStatusException extends Exception {
        private final int status;
        private ElasticsearchHttpStatusException(int status) { this.status = status; }
        private int status() { return status; }
    }
}
