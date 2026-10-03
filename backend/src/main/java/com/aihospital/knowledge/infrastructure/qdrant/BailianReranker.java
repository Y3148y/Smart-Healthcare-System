package com.aihospital.knowledge.infrastructure.qdrant;

import com.aihospital.shared.model.Models.Evidence;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;

/** DashScope text-rerank API. Credentials and patient queries are never logged. */
@Component
public class BailianReranker {
    public record Ranked(Evidence evidence, boolean selected, String reason) {}
    public record Result(List<Evidence> evidence, String status, List<Ranked> ranking) {
        public Result(List<Evidence> evidence, String status) { this(evidence, status, List.of()); }
    }
    private final ObjectMapper json;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    @Value("${ai.rerank.url:}") private String url = "";
    @Value("${ai.rerank.api-key:}") private String key = "";
    @Value("${ai.rerank.model:}") private String model = "";
    @Value("${ai.rerank.min-score:0.5}") private double minimum = 0.5;
    @Value("${ai.rerank.timeout-seconds:12}") private int timeout = 12;
    public BailianReranker(ObjectMapper json) { this.json = json; }
    public boolean configured() { return !url.isBlank() && !key.isBlank() && !model.isBlank(); }
    public String modelName() { return model; }
    public Result rank(String query, List<Evidence> candidates, int limit) {
        if (!configured()) return new Result(List.of(), "NOT_CONFIGURED");
        if (candidates.isEmpty()) return new Result(List.of(), "EMPTY");
        try {
            var request = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(timeout))
                    .header("Authorization", "Bearer " + key).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(Map.of(
                            "model", model, "input", Map.of("query", query, "documents", candidates.stream()
                                    .map(e -> e.title() + "\n" + e.excerpt()).toList()),
                            "parameters", Map.of("top_n", candidates.size(), "return_documents", false))))).build();
            var response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() / 100 != 2) return new Result(List.of(), failureStatus(response));
            var results = json.readTree(response.body()).path("output").path("results");
            if (!results.isArray() || results.size() != candidates.size()) return new Result(List.of(), "INVALID_RESPONSE");
            Set<Integer> seen = new HashSet<>();
            List<Evidence> ranked = new ArrayList<>();
            for (var item : results) {
                int index = item.path("index").asInt(-1);
                double score = item.path("relevance_score").asDouble(Double.NaN);
                if (index < 0 || index >= candidates.size() || !seen.add(index) || !Double.isFinite(score)
                        || score < 0 || score > 1) return new Result(List.of(), "INVALID_RESPONSE");
                Evidence e = candidates.get(index);
                ranked.add(new Evidence(e.title(), e.source(), e.excerpt(), score));
            }
            var sorted = ranked.stream().sorted(Comparator.comparingDouble(Evidence::score).reversed()
                    .thenComparing(com.aihospital.knowledge.domain.Bm25Retriever::key)).toList();
            var selected = sorted.stream().filter(e -> e.score() >= minimum).limit(Math.max(1, limit)).toList();
            var decisions = sorted.stream().map(e -> new Ranked(e, selected.contains(e), e.score() < minimum
                    ? "below_rerank_threshold" : selected.contains(e) ? "selected" : "beyond_final_top_k")).toList();
            return new Result(selected, "OK", decisions);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt(); return new Result(List.of(), "INTERRUPTED");
        } catch (Exception ex) { return new Result(List.of(), "UNAVAILABLE"); }
    }
    private String failureStatus(HttpResponse<String> response) {
        String status = "HTTP_" + response.statusCode();
        try {
            String code = json.readTree(response.body()).path("code").asText("");
            // Display only a provider error identifier, never a raw body or echoed patient input.
            if (code.matches("[A-Za-z0-9_.-]{1,80}")) status += ":" + code;
        } catch (Exception ignored) { }
        return status;
    }
}
