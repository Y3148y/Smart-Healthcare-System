package com.aihospital.observation;

import com.aihospital.observation.application.AnswerCallAudit;
import com.aihospital.triage.domain.AnswerEvidence;
import com.aihospital.triage.domain.NarrationModel;
import com.aihospital.shared.model.Models.ToolTrace;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class AnswerCallAuditTest {
    private static final String TRACE = "f1b09121-3c90-4fe8-88df-8fbf8f8807d0";
    private NarrationModel.Answer answer(String status, AnswerEvidence.SupportReview review, AnswerEvidence.Failure failure) {
        return new NarrationModel.Answer("synthetic answer", status, "test-model", new AnswerEvidence.Diagnostics(
                TRACE, "MATCHED", "REFERENCE_INTEGRITY_PASSED", List.of(), List.of(), List.of(),
                "NOT_VERIFIED", failure, 10L,
                new AnswerEvidence.Generation("OUTPUT_VALIDATION", "STOP", 123, 45, 10), review, null));
    }
    @Test void linksExistingTraceAndSeparatesMeasuredGenerationAndReviewUsage() {
        var review = new AnswerEvidence.SupportReview("PASSED", List.of(), 3L, null, false, 67, 8);
        var rows = AnswerCallAudit.records("guide", "synthetic", answer("LIVE", review, null), 20, List.of());
        assertEquals(2, rows.size());
        assertEquals(TRACE, rows.get(0).id()); assertEquals(TRACE + "-review", rows.get(1).id());
        assertEquals(123, rows.get(0).inputTokens()); assertEquals(45, rows.get(0).outputTokens());
        assertEquals(67, rows.get(1).inputTokens()); assertEquals(8, rows.get(1).outputTokens());
        assertTrue(rows.get(0).success()); assertTrue(rows.get(1).success());
        assertEquals(20, rows.get(0).elapsedMs()); assertEquals(3, rows.get(1).elapsedMs());
    }
    @Test void modelFailureCannotBeRecordedAsSuccessfulRetrieval() {
        var rows = AnswerCallAudit.records("guide", "synthetic", answer("FALLBACK", null,
                new AnswerEvidence.Failure("MODEL_TIMEOUT", "Timeout", null)), 20, List.of());
        assertFalse(rows.get(0).success());
    }
    @Test void blockedAnswerAndFailedReviewAreUnsuccessfulDespiteMeasuredUsage() {
        var review = new AnswerEvidence.SupportReview("REJECTED", List.of("unsupported"), 4L, null, false, 30, 5);
        var rows = AnswerCallAudit.records("guide", "synthetic", answer("VALIDATION_BLOCKED", review, null), 20, List.of());
        assertFalse(rows.get(0).success()); assertFalse(rows.get(1).success());
        assertEquals(123, rows.get(0).inputTokens());
    }
    @Test void missingUsageRemainsUnknownAndDoesNotBorrowGenerationCounts() {
        var review = new AnswerEvidence.SupportReview("UNAVAILABLE", List.of(), null, null, false, null, null);
        var rows = AnswerCallAudit.records("guide", "synthetic", answer("FALLBACK", review, null), 20, List.of());
        assertEquals(0, rows.get(1).inputTokens()); assertEquals(0, rows.get(1).outputTokens());
        assertEquals(123, rows.get(0).inputTokens());
    }
    @Test void deterministicSafetyAndDemoKeepNoProviderUsage() {
        for (String status : List.of("SAFETY_RULE", "DEMO", "DEMO_UNGROUNDED")) {
            var row = AnswerCallAudit.records("guide", "synthetic", new NarrationModel.Answer("test", status, ""), 1, null).get(0);
            assertTrue(row.success()); assertEquals(0, row.inputTokens()); assertEquals(0, row.outputTokens());
        }
    }
    @Test void toolFailureCannotBeHiddenByLiveModelSuccess() {
        var tool = new ToolTrace("medical_knowledge_retrieve", "synthetic", "", "", 1, false, "synthetic failure");
        var rows = AnswerCallAudit.records("guide", "synthetic", answer("LIVE", null, null), 20, List.of(tool));
        assertFalse(rows.get(0).success());
    }
}
