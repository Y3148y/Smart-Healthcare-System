package com.aihospital.knowledge;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class RagQualityGateTest {
    @Test void acceptsMeasuredEngineeringBaselineWithoutTreatingUnanswerableAsRecallMiss() {
        var samples = List.of(
                new RagQualityGate.Sample("answerable", true, 1.0, 1.0, false, false),
                new RagQualityGate.Sample("unanswerable", false, 0.0, 0.0, false, false));

        assertTrue(RagQualityGate.failures(samples).isEmpty());
    }

    @Test void rejectsMissedOrMisrankedEvidenceForbiddenDocumentsAndUnanswerableHits() {
        var samples = List.of(
                new RagQualityGate.Sample("missed", true, 0.0, 0.0, false, false),
                new RagQualityGate.Sample("misranked", true, 1.0, 0.5, false, false),
                new RagQualityGate.Sample("forbidden", true, 1.0, 1.0, true, false),
                new RagQualityGate.Sample("oov", false, 0.0, 0.0, false, true));

        var failures = RagQualityGate.failures(samples);
        assertEquals(5, failures.size());
        assertTrue(failures.stream().anyMatch(value -> value.contains("missed")));
        assertTrue(failures.stream().anyMatch(value -> value.contains("misranked")));
        assertTrue(failures.stream().anyMatch(value -> value.contains("forbidden")));
        assertTrue(failures.stream().anyMatch(value -> value.contains("oov")));
    }

    @Test void rejectsEmptyOrAnswerableFreeDataset() {
        assertEquals(List.of("dataset has no samples"), RagQualityGate.failures(List.of()));
        assertEquals(List.of("dataset has no answerable samples"), RagQualityGate.failures(List.of(
                new RagQualityGate.Sample("oov", false, 0.0, 0.0, false, false))));
    }

    @Test void summarizesOnlyAnswerableQueriesForRecallAndMrr() {
        var summary = RagQualityGate.summarize(List.of(
                new RagQualityGate.Sample("answerable", true, 1.0, 1.0, false, false),
                new RagQualityGate.Sample("unanswerable", false, 0.0, 0.0, false, false)));

        assertEquals(2, summary.samples());
        assertEquals(1, summary.answerableSamples());
        assertEquals(1.0, summary.meanRecallAt3());
        assertEquals(1.0, summary.meanMrr());
        assertEquals(0, summary.forbiddenHits());
        assertEquals(0, summary.unanswerableFalsePositives());
        assertTrue(summary.failures().isEmpty());
    }
}
