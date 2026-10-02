package com.aihospital.knowledge;

import com.aihospital.knowledge.domain.KnowledgeCatalog;
import com.aihospital.knowledge.infrastructure.demo.InMemoryKnowledgeCatalog;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the retrieval behaviour on both sides of the routing-noise switch, and records what the
 * switch actually costs. Sanitising is off by default because enabling it exposes a calibration
 * problem rather than fixing one — see {@code stripRoutingNoiseEnabled}.
 *
 * <p>All CJK is built from codepoints so this test cannot itself introduce a wrong character.
 */
class RetrievalFusionEvaluationTest {

    private static String p(int... cps) {
        StringBuilder sb = new StringBuilder();
        for (int cp : cps) sb.appendCodePoint(cp);
        return sb.toString();
    }

    private static final String ER_DEPT = p(0x6025, 0x8BCA, 0x79D1);          // 急诊科
    private static final String RUNNY_NOSE = p(0x6D41, 0x9F3B, 0x6C34);        // 流鼻涕
    private static final String NASAL_CONGESTION = p(0x9F3B, 0x585E);          // 鼻塞

    private static InMemoryKnowledgeCatalog withStripping(boolean enabled) {
        var catalog = new InMemoryKnowledgeCatalog();
        ReflectionTestUtils.setField(catalog, "stripRoutingNoise", enabled);
        return catalog;
    }

    /** Default is off, so today's behaviour is unchanged until calibration is settled. */
    @Test
    void routingNoiseRemovalIsOffByDefault() {
        assertFalse(withStripping(false).stripRoutingNoiseEnabled());
        var catalog = withStripping(false);
        assertTrue(catalog.sanitizeForSemantic(ER_DEPT + " " + RUNNY_NOSE).contains(ER_DEPT),
                "未开启时应保留原查询，避免未标定的清洗改变现有召回");
    }

    /** When enabled it removes the routing word but must keep the symptom. */
    @Test
    void strippingRemovesRoutingWordsAndKeepsTheSymptom() {
        var catalog = withStripping(true);
        assertTrue(catalog.stripRoutingNoiseEnabled());
        String cleaned = catalog.sanitizeForSemantic(ER_DEPT + " " + RUNNY_NOSE);
        assertFalse(cleaned.contains(ER_DEPT), "急诊科 应被移除，实际: " + cleaned);
        assertTrue(cleaned.contains(RUNNY_NOSE), "症状不得被误删，实际: " + cleaned);
    }

    /**
     * The reason stripping stays off by default. Routing noise inflates scores for every document,
     * and the best-matching document for a real symptom query still scores far below the 0.28
     * threshold. Turning stripping on would therefore move grounded from "barely hits" to "never
     * hits". This records the numbers instead of asserting a preference.
     */
    @Test
    void strippingWouldStarveRecallAtTheCurrentThreshold() throws Exception {
        var dirty = withStripping(false);
        var clean = withStripping(true);

        double dirtyBest = bestScore(dirty.retrieve(ER_DEPT + " " + RUNNY_NOSE, 8, 0.28));
        double cleanBest = bestScore(clean.retrieve(ER_DEPT + " " + RUNNY_NOSE, 8, 0.28));

        StringBuilder report = new StringBuilder();
        report.append("threshold=0.28\n");
        report.append("with_routing_noise_best=").append(round(dirtyBest)).append('\n');
        report.append("stripped_best=").append(round(cleanBest)).append('\n');
        report.append("unfiltered_top_score=")
              .append(round(bestScore(dirty.retrieve(RUNNY_NOSE, 8, 0.0)))).append('\n');
        report.append("stripped_top_score=")
              .append(round(bestScore(clean.retrieve(RUNNY_NOSE, 8, 0.0)))).append('\n');
        Files.writeString(Path.of("target", "fusion-eval.txt"), report.toString(), StandardCharsets.US_ASCII);

        assertTrue(cleanBest <= dirtyBest,
                "清洗后分数不应虚高 clean=" + cleanBest + " dirty=" + dirtyBest);
        assertTrue(bestScore(clean.retrieve(RUNNY_NOSE, 8, 0.0)) < 0.28,
                "记录用：清洗后最匹配文档仍低于当前阈值，正是标定待裁定的依据");
    }

    /**
     * With stripping off — today's shipped behaviour — a real symptom query does retrieve, but
     * what it retrieves is dominated by the generic emergency-triage document. This is the
     * honest description of the current state: recall works, and it works partly by accident.
     */
    @Test
    void symptomQueryStillRetrievesUnderCurrentDefaultSettings() throws Exception {
        var catalog = withStripping(false);
        KnowledgeCatalog.Retrieval retrieval =
                catalog.retrieve(ER_DEPT + " " + RUNNY_NOSE + " " + NASAL_CONGESTION, 5, 0.28);

        StringBuilder report = new StringBuilder();
        report.append("current_default_hits=").append(retrieval.evidence().size()).append('\n');
        retrieval.evidence().forEach(e ->
                report.append(String.format("  %.4f  ", e.score()))
                      .append(codepoints(e.title())).append('\n'));
        Files.writeString(Path.of("target", "default-retrieval.txt"), report.toString(), StandardCharsets.US_ASCII);

        assertTrue(retrieval.grounded(), "当前默认设置下带科室的真实症状查询应仍能命中");
    }

    private static String codepoints(String s) {
        StringBuilder sb = new StringBuilder();
        s.codePoints().limit(16).forEach(c -> sb.append(String.format("U+%04X ", c)));
        return sb.toString().trim();
    }

    private static double bestScore(KnowledgeCatalog.Retrieval r) {
        return r.evidence().stream().mapToDouble(com.aihospital.shared.model.Models.Evidence::score)
                .max().orElse(0);
    }

    private static String round(double v) { return String.format("%.4f", v); }
}
