package com.aihospital.knowledge;

import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class RagStageMetricsTest {
    @Test void measuresRecallRankAndExplicitUnrelatedControlsAtEachStage() {
        var metrics = RagQualityGate.stageMetrics(
                List.of("unrelated", "primary", "primary"), Set.of("primary"), Set.of("unrelated"), 3);

        assertEquals(2, metrics.candidateCount());
        assertEquals(1.0, metrics.recallAtK());
        assertEquals(0.5, metrics.meanReciprocalRank());
        assertTrue(metrics.unrelatedHit());
    }

    @Test void representsNoAnswerWithoutInventingARelevantHit() {
        var metrics = RagQualityGate.stageMetrics(List.of("not-annotated"), Set.of(), Set.of(), 3);

        assertEquals(1, metrics.candidateCount());
        assertEquals(0.0, metrics.recallAtK());
        assertEquals(0.0, metrics.meanReciprocalRank());
        assertFalse(metrics.unrelatedHit());
    }

    @Test void appliesTopKAfterStableDuplicateRemoval() {
        var metrics = RagQualityGate.stageMetrics(
                List.of("noise", "noise", "primary", "later"), Set.of("primary"), Set.of(), 2);

        assertEquals(3, metrics.candidateCount());
        assertEquals(1.0, metrics.recallAtK());
        assertEquals(0.5, metrics.meanReciprocalRank());
    }
}
