package com.aihospital.knowledge;

import com.aihospital.knowledge.infrastructure.demo.InMemoryKnowledgeCatalog;
import com.aihospital.knowledge.infrastructure.qdrant.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.test.util.ReflectionTestUtils;
import java.nio.file.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit, paid-provider opt-in; only synthetic queries in the fixed engineering dataset. */
@EnabledIfEnvironmentVariable(named = "AI_RAG_LIVE_TEST", matches = "true")
class RagLiveIntegrationTest {
    @Test void actualBailianEmbeddingQdrantAndRerankingAreMeasured() throws Exception {
        var json = new ObjectMapper();
        var index = new QdrantSemanticIndex(json);
        String key = System.getenv("AI_EMBEDDING_API_KEY");
        assertNotNull(key, "A process-scoped embedding key is required");
        ReflectionTestUtils.setField(index, "model", env("AI_EMBEDDING_MODEL", "qwen3.7-text-embedding"));
        ReflectionTestUtils.setField(index, "apiKey", key);
        ReflectionTestUtils.setField(index, "embeddingBaseUrl", env("AI_EMBEDDING_BASE_URL", "https://dashscope.aliyuncs.com/compatible-mode/v1"));
        ReflectionTestUtils.setField(index, "qdrantUrl", env("AI_QDRANT_URL", "http://127.0.0.1:6333"));
        ReflectionTestUtils.setField(index, "collection", env("AI_QDRANT_COLLECTION", "ai_hospital_knowledge_v2"));
        var reranker = new BailianReranker(json);
        ReflectionTestUtils.setField(reranker, "key", key);
        ReflectionTestUtils.setField(reranker, "model", env("AI_RERANK_MODEL", "qwen3.7-text-rerank"));
        ReflectionTestUtils.setField(reranker, "url", env("AI_RERANK_URL", "https://dashscope.aliyuncs.com/api/v1/services/rerank/text-rerank/text-rerank"));
        ReflectionTestUtils.setField(reranker, "minimum", Double.parseDouble(env("AI_RERANK_MIN_SCORE", "0.5")));
        var catalog = new HybridKnowledgeCatalog(new InMemoryKnowledgeCatalog(), index, reranker);
        ReflectionTestUtils.setField(catalog, "requireSemantic", true);
        ReflectionTestUtils.setField(catalog, "requireRerank", true);
        catalog.validateConfiguration();
        String dataset = env("AI_RAG_DATASET", "rag-relevance-cases.json");
        assertTrue(Set.of("rag-relevance-cases.json", "rag-holdout-cases.json").contains(dataset));
        var cases = json.readTree(getClass().getResourceAsStream("/" + dataset));
        assertNotNull(cases, "RAG evaluation dataset is missing");
        assertEquals(12, cases.size(), "The frozen engineering dataset must not silently shrink or grow");
        List<Map<String, Object>> rows = new ArrayList<>();
        List<RagQualityGate.Sample> qualitySamples = new ArrayList<>();
        Set<String> sampleIds = new HashSet<>();
        for (var sample : cases) {
            String id = sample.path("id").asText();
            assertFalse(id.isBlank(), "Every frozen RAG case needs a stable id");
            assertTrue(sampleIds.add(id), "Duplicate RAG evaluation id: " + id);
            String query = sample.path("query").asText();
            var report = catalog.inspect(query, 3, 0.28);
            Set<String> relevant = new HashSet<>(), forbidden = new HashSet<>();
            sample.path("relevant").forEach(n -> relevant.add(n.asText()));
            sample.path("forbidden").forEach(n -> forbidden.add(n.asText()));
            assertTrue(Collections.disjoint(relevant, forbidden), "Conflicting RAG labels for case: " + id);
            List<String> titles = report.retrieval().evidence().stream().map(e -> e.title()).distinct().toList();
            double rr = 0;
            for (int i = 0; i < titles.size(); i++) if (relevant.contains(titles.get(i))) { rr = 1.0 / (i + 1); break; }
            double recall = relevant.isEmpty() ? 0 : (double) titles.stream().filter(relevant::contains).count() / relevant.size();
            boolean forbiddenHit = titles.stream().anyMatch(forbidden::contains);
            boolean unanswerableFalsePositive = relevant.isEmpty() && !titles.isEmpty();
            var fusionRecords = report.candidates();
            List<String> lexicalTitles = fusionRecords.stream().filter(c -> c.lexicalRank() > 0)
                    .sorted(Comparator.comparingInt(HybridKnowledgeCatalog.FusionRecord::lexicalRank))
                    .map(HybridKnowledgeCatalog.FusionRecord::title).distinct().toList();
            List<String> semanticTitles = fusionRecords.stream().filter(c -> c.semanticRank() > 0)
                    .sorted(Comparator.comparingInt(HybridKnowledgeCatalog.FusionRecord::semanticRank))
                    .map(HybridKnowledgeCatalog.FusionRecord::title).distinct().toList();
            List<String> fusedTitles = fusionRecords.stream().map(HybridKnowledgeCatalog.FusionRecord::title)
                    .distinct().toList();
            Map<String, RagQualityGate.StageMetrics> stageMetrics = Map.of(
                    "bm25Candidates", RagQualityGate.stageMetrics(lexicalTitles, relevant, forbidden, 20),
                    "qdrantCandidates", RagQualityGate.stageMetrics(semanticTitles, relevant, forbidden, 20),
                    "rrfCandidates", RagQualityGate.stageMetrics(fusedTitles, relevant, forbidden, 20),
                    "finalSelected", RagQualityGate.stageMetrics(titles, relevant, forbidden, 3));
            rows.add(Map.of("id", id, "query", query, "report", report,
                    "recallAt3", recall, "reciprocalRank", rr,
                    "forbiddenHit", forbiddenHit,
                    "unanswerableFalsePositive", unanswerableFalsePositive,
                    "stageMetrics", stageMetrics));
            qualitySamples.add(new RagQualityGate.Sample(id, !relevant.isEmpty(),
                    recall, rr, forbiddenHit, unanswerableFalsePositive));
            // Persist completed samples even if a later provider call fails. Never persist credentials.
            json.writerWithDefaultPrettyPrinter().writeValue(Path.of("target/rag-live.json").toFile(), rows);
        }
        assertTrue(rows.stream().allMatch(r -> {
            var report = (HybridKnowledgeCatalog.Report) r.get("report");
            return report.mode().equals("HYBRID_QDRANT_RERANKED") || report.mode().equals("HYBRID_QDRANT_NO_CANDIDATES");
        }), "One or more samples did not execute the required live retrieval path; see target/rag-live.json");
        var qualitySummary = RagQualityGate.summarize(qualitySamples);
        json.writerWithDefaultPrettyPrinter().writeValue(Path.of("target/rag-live-summary.json").toFile(),
                Map.of("dataset", dataset, "sampleCount", qualitySummary.samples(),
                        "answerableSamples", qualitySummary.answerableSamples(),
                        "meanRecallAt3Answerable", qualitySummary.meanRecallAt3(),
                        "meanMrrAnswerable", qualitySummary.meanMrr(),
                        "forbiddenHits", qualitySummary.forbiddenHits(),
                        "unanswerableFalsePositives", qualitySummary.unanswerableFalsePositives(),
                        "gateFailures", qualitySummary.failures()));
        assertTrue(qualitySummary.failures().isEmpty(), () -> "RAG engineering quality gate failed: "
                + String.join("; ", qualitySummary.failures()) + ". See target/rag-live.json and target/rag-live-summary.json");
    }
    private static String env(String name, String fallback) { return System.getenv().getOrDefault(name, fallback); }
}
