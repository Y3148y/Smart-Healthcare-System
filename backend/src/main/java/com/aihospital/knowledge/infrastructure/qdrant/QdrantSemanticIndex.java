package com.aihospital.knowledge.infrastructure.qdrant;

import com.aihospital.shared.model.Models.Evidence;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.net.URI;
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

    public QdrantSemanticIndex(ObjectMapper json) { this.json = json; }
    public boolean configured() { return !model.isBlank() && !apiKey.isBlank() && !embeddingBaseUrl.isBlank(); }
    public boolean indexed() { return indexed; }
    public String status() { return status; }
    public String modelName() { return model; }
    public synchronized int indexedCount(List<Evidence> corpus) {
        return (int) corpus.stream().map(this::pointId).filter(indexedPointIds::contains).count();
    }

    public synchronized boolean ensureIndexed(List<Evidence> corpus) {
        if (!configured()) { status = "NOT_CONFIGURED"; return false; }
        activePointIds = corpus.stream().map(this::pointId).collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (corpus.isEmpty()) { indexed = false; status = "EMPTY_CORPUS"; return false; }
        try {
            List<Evidence> pending = corpus.stream().filter(item -> !indexedPointIds.contains(pointId(item))).toList();
            if (pending.isEmpty()) return indexed;
            indexedPointIds.addAll(existingPointIds(pending));
            pending = pending.stream().filter(item -> !indexedPointIds.contains(pointId(item))).toList();
            if (pending.isEmpty()) {
                indexed = true;
                status = "INDEXED";
                return true;
            }
            List<List<Double>> vectors = new ArrayList<>();
            int batch = Math.max(1, Math.min(32, batchSize));
            for (int from = 0; from < pending.size(); from += batch)
                vectors.addAll(embeddings(pending.subList(from, Math.min(pending.size(), from + batch))
                        .stream().map(item -> item.title() + "\n" + item.excerpt()).toList()));
            if (vectors.size() != pending.size()) throw new IllegalStateException("向量数量与片段数量不一致");
            int dimensions = vectors.get(0).size();
            if (vectors.stream().anyMatch(v -> v.size() != dimensions)) throw new IllegalStateException("Inconsistent embedding dimensions");
            ensureCollection(dimensions);
            List<Map<String,Object>> points = new ArrayList<>();
            for (int i = 0; i < pending.size(); i++) {
                Evidence evidence = pending.get(i);
                String id = pointId(evidence);
                points.add(Map.of("id", id, "vector", vectors.get(i), "payload", Map.of(
                        "title", evidence.title(), "source", evidence.source(), "excerpt", evidence.excerpt())));
            }
            request("PUT", qdrantUrl + "/collections/" + collection + "/points?wait=true", Map.of("points", points), false);
            for (Evidence item : pending) indexedPointIds.add(pointId(item));
            indexed = true;
            status = "INDEXED";
            return true;
        } catch (Exception ex) {
            if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
            status = "INDEX_UNAVAILABLE";
            log.warn("Semantic index unavailable; retrieval policy controls fallback ({})", ex.getClass().getSimpleName());
            return false;
        }
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
            throw new IllegalStateException("Qdrant collection check returned HTTP " + response.statusCode());

        Map<String, Evidence> expected = new java.util.HashMap<>();
        for (Evidence item : candidates) expected.put(pointId(item), item);
        List<String> ids = List.copyOf(expected.keySet());
        Set<String> existing = new HashSet<>();
        int batch = 100;
        for (int from = 0; from < ids.size(); from += batch) {
            List<String> batchIds = ids.subList(from, Math.min(ids.size(), from + batch));
            JsonNode retrieved = request("POST", qdrantUrl + "/collections/" + collection + "/points",
                    Map.of("ids", batchIds, "with_payload", true, "with_vector", false), false);
            if (!retrieved.path("result").isArray()) throw new IllegalStateException("Invalid Qdrant point retrieval response");
            for (JsonNode point : retrieved.path("result")) {
                String id = point.path("id").asText();
                Evidence evidence = expected.get(id);
                JsonNode payload = point.path("payload");
                if (evidence != null && evidence.title().equals(payload.path("title").asText())
                        && evidence.source().equals(payload.path("source").asText())
                        && evidence.excerpt().equals(payload.path("excerpt").asText())) existing.add(id);
            }
        }
        return existing;
    }

    public record SearchResult(List<Evidence> evidence, String status) {}
    public List<Evidence> search(String query, int limit, double minimumScore) {
        return searchDetailed(query, limit, minimumScore).evidence();
    }
    public SearchResult searchDetailed(String query, int limit, double minimumScore) {
        if (!indexed || !configured() || activePointIds.isEmpty()) return new SearchResult(List.of(), "NOT_READY");
        try {
            List<Double> vector = embeddings(List.of(query)).get(0);
            JsonNode response = request("POST", qdrantUrl + "/collections/" + collection + "/points/search",
                    Map.of("vector", vector, "limit", Math.max(1, Math.min(limit, 100)), "with_payload", true,
                            "score_threshold", minimumScore, "filter", Map.of("must", List.of(Map.of("has_id", activePointIds)))), false);
            if (!response.path("result").isArray()) throw new IllegalStateException("Invalid Qdrant search response");
            List<Evidence> found = new ArrayList<>();
            for (JsonNode hit : response.path("result")) {
                JsonNode payload = hit.path("payload");
                double score = hit.path("score").asDouble(Double.NaN);
                Evidence evidence = new Evidence(payload.path("title").asText(), payload.path("source").asText(),
                        payload.path("excerpt").asText(), score);
                if (Double.isFinite(score) && score >= minimumScore && activePointIds.contains(pointId(evidence))) found.add(evidence);
            }
            status = "READY";
            return new SearchResult(List.copyOf(found), "READY");
        } catch (Exception ex) {
            if (ex instanceof InterruptedException) Thread.currentThread().interrupt();
            status = "SEARCH_UNAVAILABLE";
            synchronized (this) { indexed = false; indexedPointIds.clear(); }
            log.warn("Semantic retrieval failed; retrieval policy controls fallback ({})", ex.getClass().getSimpleName());
            return new SearchResult(List.of(), "SEARCH_UNAVAILABLE");
        }
    }

    private List<List<Double>> embeddings(List<String> input) throws Exception {
        JsonNode response = request("POST", embeddingBaseUrl.replaceAll("/+$", "") + "/embeddings",
                Map.of("model", model, "input", input), true);
        List<List<Double>> vectors = new ArrayList<>();
        for (int i = 0; i < input.size(); i++) vectors.add(null);
        for (JsonNode item : response.path("data")) {
            List<Double> vector = new ArrayList<>();
            for (JsonNode number : item.path("embedding")) {
                if (!number.isNumber() || !Double.isFinite(number.asDouble())) throw new IllegalStateException("Invalid embedding value");
                vector.add(number.asDouble());
            }
            int index = item.path("index").asInt(-1);
            if (index < 0 || index >= vectors.size() || vectors.get(index) != null) throw new IllegalStateException("Invalid embedding index");
            vectors.set(index, vector);
        }
        if (vectors.stream().anyMatch(value -> value == null || value.isEmpty()))
            throw new IllegalStateException("Embedding API 未返回完整向量");
        return vectors;
    }

    private void ensureCollection(int dimensions) throws Exception {
        HttpRequest probe = HttpRequest.newBuilder(URI.create(qdrantUrl + "/collections/" + collection))
                .timeout(Duration.ofSeconds(5)).GET().build();
        var response = http.send(probe, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        int code = response.statusCode();
        if (code == 200) {
            var vectors = json.readTree(response.body()).path("result").path("config").path("params").path("vectors");
            if (vectors.path("size").asInt(-1) != dimensions || !"Cosine".equalsIgnoreCase(vectors.path("distance").asText()))
                throw new IllegalStateException("Qdrant collection dimensions/distance mismatch; configure a new collection");
            return;
        }
        if (code != 404) throw new IllegalStateException("Qdrant collection check returned HTTP " + code);
        if (hnswM < 2 || hnswEfConstruct < 1 || hnswFullScanThresholdKb < 0)
            throw new IllegalStateException("Invalid Qdrant HNSW configuration");
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
        if (response.statusCode() / 100 != 2) throw new IllegalStateException("外部索引服务响应 HTTP " + response.statusCode());
        return json.readTree(response.body());
    }
}
