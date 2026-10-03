package com.aihospital.knowledge;

import com.aihospital.knowledge.domain.Bm25Retriever;
import com.aihospital.knowledge.infrastructure.demo.InMemoryKnowledgeCatalog;
import com.aihospital.shared.model.Models.Evidence;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Engineering labels; diagnostic report, not a clinical safety metric or a tuned pass score. */
class RagRelevanceBaselineTest {
    @Test void recordLegacyAndBm25AgainstFrozenQueries() throws Exception {
        var json = new ObjectMapper();
        var cases = json.readTree(getClass().getResourceAsStream("/rag-relevance-cases.json"));
        var local = new InMemoryKnowledgeCatalog();
        List<Map<String, Object>> rows = new ArrayList<>();
        for (var sample : cases) {
            String query = sample.path("query").asText();
            Set<String> relevant = new HashSet<>(), forbidden = new HashSet<>();
            sample.path("relevant").forEach(n -> relevant.add(n.asText()));
            sample.path("forbidden").forEach(n -> forbidden.add(n.asText()));
            rows.add(row(sample.path("id").asText(), "legacy", query,
                    local.retrieve(query, 3, 0.28).evidence(), relevant, forbidden));
            rows.add(row(sample.path("id").asText(), "bm25", query,
                    Bm25Retriever.search(query, local.approvedCorpus(), 3, 0.28), relevant, forbidden));
        }
        Files.createDirectories(Path.of("target"));
        json.writerWithDefaultPrettyPrinter().writeValue(Path.of("target/rag-baseline.json").toFile(), rows);
        assertEquals(24, rows.size());
        assertTrue(rows.stream().allMatch(r -> Double.isFinite((double) r.get("reciprocalRank"))));
    }
    private Map<String, Object> row(String id, String route, String query, List<Evidence> hits,
                                    Set<String> relevant, Set<String> forbidden) {
        Set<String> titles = new LinkedHashSet<>(); hits.forEach(e -> titles.add(e.title()));
        double rr = 0; int rank = 0;
        for (String title : titles) { rank++; if (relevant.contains(title)) { rr = 1.0 / rank; break; } }
        double recall = relevant.isEmpty() ? 0 : (double) titles.stream().filter(relevant::contains).count() / relevant.size();
        return Map.of("id", id, "route", route, "query", query, "hits", hits,
                "recallAt3", recall, "reciprocalRank", rr,
                "forbiddenHit", titles.stream().anyMatch(forbidden::contains),
                "unanswerableFalsePositive", relevant.isEmpty() && !hits.isEmpty());
    }
}
