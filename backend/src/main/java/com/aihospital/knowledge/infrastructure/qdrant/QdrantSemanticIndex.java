package com.aihospital.knowledge.infrastructure.qdrant;

import com.aihospital.shared.model.Models.Evidence;
import com.aihospital.knowledge.domain.KnowledgeChunk;
import com.aihospital.knowledge.domain.Bm25Retriever;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.net.ConnectException;
import java.net.http.HttpConnectTimeoutException;
import java.net.http.HttpTimeoutException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.Set;
import java.util.HashSet;
import java.util.HashMap;

/** Optional semantic index. API keys never appear in corpus metadata or tool traces. */
@Component
public class QdrantSemanticIndex {
    private static final Logger log = LoggerFactory.getLogger(QdrantSemanticIndex.class);
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    @Value("${ai.embedding-model:}") private String model = "";
    @Value("${ai.embedding-api-key:}") private String apiKey = "";
    @Value("${ai.embedding-base-url:}") private String embeddingBaseUrl = "";
    @Value("${ai.qdrant-url:http://127.0.0.1:6333}") private String qdrantUrl;
    @Value("${ai.qdrant-collection:ai_hospital_knowledge_v1}") private String collection;
    @Value("${ai.qdrant.hnsw.m:16}") private int hnswM = 16;
    @Value("${ai.qdrant.hnsw.ef-construct:100}") private int hnswEfConstruct = 100;
    @Value("${ai.qdrant.hnsw.full-scan-threshold-kb:10000}") private int hnswFullScanThresholdKb = 10000;
    private volatile boolean indexed;
    private volatile String status = "NOT_CONFIGURED";
    private volatile Set<String> activePointIds = Set.of();
    @Value("${ai.embedding-batch-size:10}") private int batchSize = 10;
    private final Set<String> indexedPointIds = new HashSet<>();
    private Map<String, List<KnowledgeChunk>> provenance = Map.of();
    private final Map<String, JsonNode> indexedPayloads = new java.util.HashMap<>();
    private volatile Map<String, JsonNode> activePayloads = Map.of();

    /** Call under the same monitor as ensureIndexed to install one approved corpus snapshot. */
    public synchronized void registerChunkProvenance(Map<String, List<KnowledgeChunk>> snapshot) {
        var copy = new java.util.HashMap<String, List<KnowledgeChunk>>();
        snapshot.forEach((key, chunks) -> copy.put(key, List.copyOf(chunks)));
        provenance = Map.copyOf(copy);
    }

    private JsonNode payload(Evidence evidence) {
        var payload = json.createObjectNode().put("title", evidence.title())
                .put("source", evidence.source()).put("excerpt", evidence.excerpt());
        var origins = provenance.get(Bm25Retriever.key(evidence));
        if (origins != null && !origins.isEmpty()) {
            payload.set("provenance", json.valueToTree(origins));
            payload.put("embedding_model", model);
        }
        return payload;
    }

    public QdrantSemanticIndex(ObjectMapper json) { this.json = json; }
    public boolean configured() { return !model.isBlank() && com.aihospital.shared.security.ApiCredentialCheck.usable(apiKey) && !embeddingBaseUrl.isBlank(); }
    public boolean indexed() { return indexed; }
    public String status() { return status; }
    public String modelName() { return model; }
    public synchronized int indexedCount(List<Evidence> corpus) {
        return (int) corpus.stream().map(this::pointId).filter(indexedPointIds::contains).count();
    }
    public record CollectionConfiguration(String status, int pointCount, int indexedVectorsCount, int vectorSize, String distance,
                                          int hnswM, int hnswEfConstruct, int fullScanThresholdKb) {}

    public CollectionConfiguration collectionConfiguration() throws Exception {
        HttpRequest probe = HttpRequest.newBuilder(URI.create(qdrantUrl + "/collections/" + collection))
                .timeout(Duration.ofSeconds(5)).GET().build();
        HttpResponse<String> response = http.send(probe, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200)
            throw new IllegalStateException("Qdrant collection details returned HTTP " + response.statusCode());
        JsonNode result = json.readTree(response.body()).path("result");
        JsonNode vectors = result.path("config").path("params").path("vectors");
        JsonNode hnsw = result.path("config").path("hnsw_config");
        String status = result.path("status").asText();
        String distance = vectors.path("distance").asText();
        int pointCount = result.path("points_count").asInt(-1);
        // Qdrant may omit this metric on older versions; -1 means unknown, not zero.
        int indexedVectorsCount = result.path("indexed_vectors_count").asInt(-1);
        int vectorSize = vectors.path("size").asInt(-1);
        int m = hnsw.path("m").asInt(-1);
        int efConstruct = hnsw.path("ef_construct").asInt(-1);
        int fullScanThresholdKb = hnsw.path("full_scan_threshold").asInt(-1);
        if (status.isBlank() || pointCount < 0 || indexedVectorsCount < -1 || indexedVectorsCount > pointCount
                || vectorSize < 1 || distance.isBlank()
                || m < 2 || efConstruct < 1 || fullScanThresholdKb < 0)
            throw new IllegalStateException("Qdrant collection details are incomplete");
        return new CollectionConfiguration(status, pointCount, indexedVectorsCount, vectorSize, distance,
                m, efConstruct, fullScanThresholdKb);
    }

    public synchronized boolean ensureIndexed(List<Evidence> corpus) {
        if (!configured()) { status = "NOT_CONFIGURED"; return false; }
        activePointIds = corpus.stream().map(this::pointId).collect(java.util.stream.Collectors.toUnmodifiableSet());
        var expectedPayloads = new java.util.HashMap<String, JsonNode>();
        for (Evidence item : corpus) expectedPayloads.put(pointId(item), payload(item));
        activePayloads = Map.copyOf(expectedPayloads);
        indexedPointIds.removeIf(id -> !java.util.Objects.equals(indexedPayloads.get(id), activePayloads.get(id)));
        if (corpus.isEmpty()) { indexed = false; status = "EMPTY_CORPUS"; return false; }
        String phase = "EMBEDDING";
        try {
            List<Evidence> pending = corpus.stream().filter(item -> !indexedPointIds.contains(pointId(item))).toList();
            if (pending.isEmpty()) return indexed;
            phase = "QDRANT_LOOKUP";
            indexedPointIds.addAll(existingPointIds(pending));
            for (String id : indexedPointIds) if (activePayloads.containsKey(id)) indexedPayloads.put(id, activePayloads.get(id));
            pending = pending.stream().filter(item -> !indexedPointIds.contains(pointId(item))).toList();
            if (pending.isEmpty()) {
                indexed = true;
                status = "INDEXED";
                return true;
            }
            phase = "EMBEDDING";
            List<List<Double>> vectors = new ArrayList<>();
            int batch = Math.max(1, Math.min(32, batchSize));
            for (int from = 0; from < pending.size(); from += batch)
                vectors.addAll(embeddings(pending.subList(from, Math.min(pending.size(), from + batch))
                        .stream().map(item -> item.title() + "\n" + item.excerpt()).toList()));
            if (vectors.size() != pending.size()) throw new IllegalStateException("向量数量与片段数量不一致");
            int dimensions = vectors.get(0).size();
            if (vectors.stream().anyMatch(v -> v.size() != dimensions))
                throw new SafeIndexFailure("EMBEDDING_RESPONSE_INCONSISTENT_DIMENSIONS");
            phase = "QDRANT_COLLECTION";
            ensureCollection(dimensions);
            List<Map<String,Object>> points = new ArrayList<>();
            for (int i = 0; i < pending.size(); i++) {
                Evidence evidence = pending.get(i);
                String id = pointId(evidence);
                points.add(Map.of("id", id, "vector", vectors.get(i), "payload", activePayloads.get(id)));
            }
            phase = "QDRANT_UPSERT";
            request("PUT", qdrantUrl + "/collections/" + collection + "/points?wait=true", Map.of("points", points), false);
            for (Evidence item : pending) {
                String id = pointId(item);
                indexedPointIds.add(id);
                indexedPayloads.put(id, activePayloads.get(id));
            }
            indexed = true;
            status = "INDEXED";
            return true;
        } catch (Exception ex) {
            if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
            status = safeFailureStatus(ex, phase, "INDEX_UNAVAILABLE");
            log.warn("Semantic index unavailable; retrieval policy controls fallback ({})", status);
            return false;
        }
    }
    /** Fresh remote publication check. Cached acknowledgements cannot satisfy this gate. */
    public synchronized boolean verifyRemote(List<Evidence> corpus) {
        if(corpus.isEmpty())return true;
        boolean verified=false;
        try {
            var configuration=collectionConfiguration();
            if(!"Cosine".equalsIgnoreCase(configuration.distance())||!Set.of("green","yellow").contains(configuration.status()))return false;
            var expected=new HashMap<String,JsonNode>();for(var item:corpus)expected.put(pointId(item),payload(item));
            var ids=new ArrayList<>(expected.keySet());var seen=new HashSet<String>();
            for(int offset=0;offset<ids.size();offset+=50) {
                var batch=ids.subList(offset,Math.min(ids.size(),offset+50));
                var points=request("POST",qdrantUrl+"/collections/"+collection+"/points",Map.of("ids",batch,"with_payload",true,"with_vector",true),false).path("result");
                if(!points.isArray()||points.size()!=batch.size())return false;
                for(var point:points) {
                    String id=point.path("id").asText();var vector=point.path("vector");
                    if(!batch.contains(id)||!seen.add(id)||!expected.get(id).equals(point.path("payload"))||!vector.isArray()||vector.size()!=configuration.vectorSize())return false;
                    double norm=0;for(var value:vector){if(!value.isNumber()||!Double.isFinite(value.asDouble()))return false;norm+=value.asDouble()*value.asDouble();}
                    if(!Double.isFinite(norm)||norm==0)return false;
                }
            }
            verified=seen.size()==expected.size();return verified;
        } catch(InterruptedException interrupted){Thread.currentThread().interrupt();return false;}
        catch(Exception unavailable){return false;}
        finally{if(!verified){indexed=false;indexedPointIds.clear();status="PUBLICATION_VERIFICATION_FAILED";}}
    }

    private String safeFailureStatus(Exception failure, String phase, String fallback) {
        if (failure instanceof UpstreamHttpException upstream) return upstream.safeStatus();
        if (failure instanceof SafeIndexFailure safe) return safe.code();
        if (failure instanceof HttpConnectTimeoutException) return phase + "_CONNECT_TIMEOUT";
        if (failure instanceof HttpTimeoutException) return phase + "_REQUEST_TIMEOUT";
        if (failure instanceof ConnectException) return phase + "_CONNECTION_REFUSED";
        if (failure instanceof InterruptedException) return phase + "_INTERRUPTED";
        if (failure instanceof com.fasterxml.jackson.core.JsonProcessingException)
            return phase + "_INVALID_RESPONSE";
        if (failure instanceof java.io.IOException) return phase + "_TRANSPORT_UNAVAILABLE";
        return fallback;
    }

    private String pointId(Evidence evidence) {
        return UUID.nameUUIDFromBytes(("v2|" + embeddingBaseUrl + "|" + model + "|" + evidence.source()
                + "|" + evidence.title() + "|" + evidence.excerpt()).getBytes(StandardCharsets.UTF_8)).toString();
    }

    /** Reuse only points whose deterministic id and full evidence payload match this corpus snapshot. */
    private Set<String> existingPointIds(List<Evidence> candidates) throws Exception {
        HttpRequest probe = HttpRequest.newBuilder(URI.create(qdrantUrl + "/collections/" + collection))
                .timeout(Duration.ofSeconds(5)).GET().build();
        HttpResponse<String> response = http.send(probe, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() == 404) return Set.of();
        if (response.statusCode() != 200)
            throw new UpstreamHttpException("QDRANT", response.statusCode(), "");

        Map<String, Evidence> expected = new java.util.HashMap<>();
        for (Evidence item : candidates) expected.put(pointId(item), item);
        List<String> ids = List.copyOf(expected.keySet());
        Set<String> existing = new HashSet<>();
        int batch = 100;
        for (int from = 0; from < ids.size(); from += batch) {
            List<String> batchIds = ids.subList(from, Math.min(ids.size(), from + batch));
            JsonNode retrieved = request("POST", qdrantUrl + "/collections/" + collection + "/points",
                    Map.of("ids", batchIds, "with_payload", true, "with_vector", false), false);
            if (!retrieved.path("result").isArray()) throw new SafeIndexFailure("QDRANT_LOOKUP_INVALID_RESPONSE");
            for (JsonNode point : retrieved.path("result")) {
                String id = point.path("id").asText();
                Evidence evidence = expected.get(id);
                JsonNode payload = point.path("payload");
                if (evidence != null && payload.equals(activePayloads.get(id))) existing.add(id);
            }
        }
        return existing;
    }

    public record SearchResult(List<Evidence> evidence, String status) {}
    public List<Evidence> search(String query, int limit, double minimumScore) {
        return searchDetailed(query, limit, minimumScore).evidence();
    }
    public SearchResult searchDetailed(String query, int limit, double minimumScore) {
        Map<String, JsonNode> expectedSnapshot = activePayloads;
        Set<String> snapshotIds = expectedSnapshot.keySet();
        if (!indexed || !configured() || snapshotIds.isEmpty()) return new SearchResult(List.of(), "NOT_READY");
        String phase = "EMBEDDING";
        try {
            List<Double> vector = embeddings(List.of(query)).get(0);
            phase = "QDRANT_SEARCH";
            JsonNode response = request("POST", qdrantUrl + "/collections/" + collection + "/points/search",
                    Map.of("vector", vector, "limit", Math.max(1, Math.min(limit, 100)), "with_payload", true,
                            "score_threshold", minimumScore, "filter", Map.of("must", List.of(Map.of("has_id", snapshotIds)))), false);
            if (!response.path("result").isArray()) throw new SafeIndexFailure("QDRANT_SEARCH_INVALID_RESPONSE");
            List<Evidence> found = new ArrayList<>();
            for (JsonNode hit : response.path("result")) {
                JsonNode payload = hit.path("payload");
                double score = hit.path("score").asDouble(Double.NaN);
                Evidence evidence = new Evidence(payload.path("title").asText(), payload.path("source").asText(),
                        payload.path("excerpt").asText(), score);
                String hitId = hit.path("id").asText();
                if (Double.isFinite(score) && score >= minimumScore && snapshotIds.contains(hitId)
                        && hitId.equals(pointId(evidence)) && payload.equals(expectedSnapshot.get(hitId))) found.add(evidence);
            }
            status = "READY";
            return new SearchResult(List.copyOf(found), "READY");
        } catch (Exception ex) {
            if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
            status = safeFailureStatus(ex, phase, "SEARCH_UNAVAILABLE");
            synchronized (this) { indexed = false; indexedPointIds.clear(); }
            log.warn("Semantic retrieval failed; retrieval policy controls fallback ({})", status);
            return new SearchResult(List.of(), status);
        }
    }

    private List<List<Double>> embeddings(List<String> input) throws Exception {
        JsonNode response = requestEmbedding(input);
        try {
            return parseEmbeddings(response, input.size());
        } catch (DuplicateEmbeddingIndexException duplicateIndex) {
            // Some OpenAI-compatible embedding providers return index=0 for every row in a
            // batch. Never pair those vectors by response order: retry this batch as singleton
            // requests so every vector is unambiguously associated with its source text.
            if (input.size() == 1) throw duplicateIndex;
            List<List<Double>> retried = new ArrayList<>();
            for (String text : input) retried.add(parseEmbeddings(requestEmbedding(List.of(text)), 1).get(0));
            return List.copyOf(retried);
        }
    }

    private JsonNode requestEmbedding(List<String> input) throws Exception {
        return request("POST", embeddingBaseUrl.replaceAll("/+$", "") + "/embeddings",
                Map.of("model", model, "input", input, "encoding_format", "float"), true);
    }

    private List<List<Double>> parseEmbeddings(JsonNode response, int expectedCount) throws Exception {
        JsonNode data = response.path("data");
        if (!data.isArray() || data.size() != expectedCount)
            throw new SafeIndexFailure("EMBEDDING_RESPONSE_VECTOR_COUNT_MISMATCH");
        List<List<Double>> vectors = new ArrayList<>();
        for (int i = 0; i < expectedCount; i++) vectors.add(null);
        for (JsonNode item : data) {
            List<Double> vector = new ArrayList<>();
            for (JsonNode number : item.path("embedding")) {
                if (!number.isNumber() || !Double.isFinite(number.asDouble()))
                    throw new SafeIndexFailure("EMBEDDING_RESPONSE_INVALID_VECTOR_VALUE");
                vector.add(number.asDouble());
            }
            if (vector.isEmpty()) throw new SafeIndexFailure("EMBEDDING_RESPONSE_EMPTY_VECTOR");
            int index = item.path("index").asInt(-1);
            if (index < 0 || index >= vectors.size())
                throw new SafeIndexFailure("EMBEDDING_RESPONSE_INVALID_INDEX");
            if (vectors.get(index) != null) throw new DuplicateEmbeddingIndexException();
            vectors.set(index, vector);
        }
        if (vectors.stream().anyMatch(value -> value == null || value.isEmpty()))
            throw new SafeIndexFailure("EMBEDDING_RESPONSE_INCOMPLETE");
        return List.copyOf(vectors);
    }

    private static final class SafeIndexFailure extends Exception {
        private final String code;
        private SafeIndexFailure(String code) { this.code = code; }
        private String code() { return code; }
    }

    private static final class DuplicateEmbeddingIndexException extends Exception {}

    private void ensureCollection(int dimensions) throws Exception {
        HttpRequest probe = HttpRequest.newBuilder(URI.create(qdrantUrl + "/collections/" + collection))
                .timeout(Duration.ofSeconds(5)).GET().build();
        var response = http.send(probe, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        int code = response.statusCode();
        if (code == 200) {
            var vectors = json.readTree(response.body()).path("result").path("config").path("params").path("vectors");
            if (vectors.path("size").asInt(-1) != dimensions || !"Cosine".equalsIgnoreCase(vectors.path("distance").asText()))
                throw new SafeIndexFailure("QDRANT_COLLECTION_DIMENSION_MISMATCH");
            return;
        }
        if (code != 404) throw new UpstreamHttpException("QDRANT", code, "");
        if (hnswM < 2 || hnswEfConstruct < 1 || hnswFullScanThresholdKb < 0)
            throw new SafeIndexFailure("QDRANT_INVALID_INDEX_CONFIGURATION");
        request("PUT", qdrantUrl + "/collections/" + collection,
                Map.of("vectors", Map.of("size", dimensions, "distance", "Cosine"),
                        "hnsw_config", Map.of("m", hnswM, "ef_construct", hnswEfConstruct,
                                "full_scan_threshold", hnswFullScanThresholdKb)), false);
    }

    private JsonNode request(String method, String url, Object body, boolean embeddingRequest) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15))
                .header("Content-Type", "application/json");
        if (embeddingRequest) builder.header("Authorization", "Bearer " + apiKey);
        HttpRequest request = builder.method(method, HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build();
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() / 100 != 2)
            throw new UpstreamHttpException(embeddingRequest ? "EMBEDDING" : "QDRANT", response.statusCode(),
                    embeddingRequest ? safeEmbeddingError(response.body()) : "");
        return json.readTree(response.body());
    }

    // Only known identifiers are retained. A syntactically safe arbitrary string can still be a key or input.
    private String safeEmbeddingError(String body) {
        if (body == null || body.length() > 16_384) return "";
        try {
            JsonNode root = json.readTree(body);
            JsonNode error = root.path("error").isObject() ? root.path("error") : root;
            String code = error.path("code").asText("");
            if (!Set.of("InvalidParameter", "InternalError.Algo.InvalidParameter", "invalid_parameter",
                    "invalid_value", "invalid_request_error", "invalid_api_key", "InvalidApiKey",
                    "InvalidInput", "ModelNotFound", "model_not_found", "unsupported_model",
                    "AccessDenied", "access_denied", "AccessDenied.Unpurchased", "Model.AccessDenied",
                    "Arrearage", "insufficient_quota", "Throttling", "Throttling.RateQuota",
                    "Throttling.AllocationQuota", "rate_limit_exceeded", "DataInspectionFailed",
                    "data_inspection_failed", "InternalError", "ServiceUnavailable").contains(code)) return "";
            String param = error.path("param").asText("");
            return ":" + code + (Set.of("model", "input", "dimensions", "encoding_format").contains(param)
                    ? ":" + param : "");
        } catch (Exception ignored) { return ""; }
    }

    private static final class UpstreamHttpException extends Exception {
        private final String service;
        private final int code;
        private final String detail;
        private UpstreamHttpException(String service, int code, String detail) {
            this.service = service; this.code = code; this.detail = detail;
        }
        private String safeStatus() { return service + "_HTTP_" + code + detail; }
    }
}
