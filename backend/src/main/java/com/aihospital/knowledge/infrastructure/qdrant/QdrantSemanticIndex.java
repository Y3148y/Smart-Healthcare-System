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
    @Value("${ai.embedding-model:}") private String model;
    @Value("${ai.embedding-api-key:}") private String apiKey;
    @Value("${ai.embedding-base-url:}") private String embeddingBaseUrl;
    @Value("${ai.qdrant-url:http://127.0.0.1:6333}") private String qdrantUrl;
    @Value("${ai.qdrant-collection:ai_hospital_knowledge_v1}") private String collection;
    private volatile boolean indexed;
    private final Set<String> indexedPointIds = new HashSet<>();

    public QdrantSemanticIndex(ObjectMapper json) { this.json = json; }
    public boolean configured() { return !model.isBlank() && !apiKey.isBlank() && !embeddingBaseUrl.isBlank(); }
    public boolean indexed() { return indexed; }

    public synchronized boolean ensureIndexed(List<Evidence> corpus) {
        if (!configured()) return false;
        if (corpus.isEmpty()) return false;
        try {
            List<Evidence> pending = corpus.stream().filter(item -> !indexedPointIds.contains(pointId(item))).toList();
            if (pending.isEmpty()) return indexed;
            List<List<Double>> vectors = embeddings(pending.stream().map(Evidence::excerpt).toList());
            if (vectors.size() != pending.size()) throw new IllegalStateException("向量数量与片段数量不一致");
            int dimensions = vectors.get(0).size();
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
            return true;
        } catch (Exception ex) {
            log.warn("Semantic index unavailable; local retrieval remains active: {}", ex.toString());
            return false;
        }
    }

    private String pointId(Evidence evidence) {
        return UUID.nameUUIDFromBytes((evidence.title() + "|" + evidence.excerpt()).getBytes(StandardCharsets.UTF_8)).toString();
    }

    public List<Evidence> search(String query, int limit, double minimumScore) {
        if (!indexed || !configured()) return List.of();
        try {
            List<Double> vector = embeddings(List.of(query)).get(0);
            JsonNode response = request("POST", qdrantUrl + "/collections/" + collection + "/points/search",
                    Map.of("vector", vector, "limit", limit, "with_payload", true, "score_threshold", minimumScore), false);
            List<Evidence> found = new ArrayList<>();
            for (JsonNode hit : response.path("result")) {
                JsonNode payload = hit.path("payload");
                found.add(new Evidence(payload.path("title").asText(), payload.path("source").asText(),
                        payload.path("excerpt").asText(), hit.path("score").asDouble()));
            }
            return found;
        } catch (Exception ex) {
            log.warn("Semantic retrieval failed; using local retrieval: {}", ex.toString());
            return List.of();
        }
    }

    private List<List<Double>> embeddings(List<String> input) throws Exception {
        JsonNode response = request("POST", embeddingBaseUrl.replaceAll("/+$", "") + "/embeddings",
                Map.of("model", model, "input", input), true);
        List<List<Double>> vectors = new ArrayList<>();
        for (int i = 0; i < input.size(); i++) vectors.add(null);
        for (JsonNode item : response.path("data")) {
            List<Double> vector = new ArrayList<>();
            for (JsonNode number : item.path("embedding")) vector.add(number.asDouble());
            int index = item.path("index").asInt(-1);
            if (index >= 0 && index < vectors.size()) vectors.set(index, vector);
        }
        if (vectors.stream().anyMatch(value -> value == null || value.isEmpty()))
            throw new IllegalStateException("Embedding API 未返回完整向量");
        return vectors;
    }

    private void ensureCollection(int dimensions) throws Exception {
        HttpRequest probe = HttpRequest.newBuilder(URI.create(qdrantUrl + "/collections/" + collection))
                .timeout(Duration.ofSeconds(5)).GET().build();
        int status = http.send(probe, HttpResponse.BodyHandlers.discarding()).statusCode();
        if (status == 200) return;
        if (status != 404) throw new IllegalStateException("Qdrant collection check returned HTTP " + status);
        request("PUT", qdrantUrl + "/collections/" + collection,
                Map.of("vectors", Map.of("size", dimensions, "distance", "Cosine")), false);
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
