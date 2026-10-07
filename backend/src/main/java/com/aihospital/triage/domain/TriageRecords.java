package com.aihospital.triage.domain;

import com.aihospital.shared.model.Models.TriageResult;
import java.time.LocalDateTime;
import java.util.List;

/** Stable conversation snapshots returned by the triage use case. */
public final class TriageRecords {
    private TriageRecords() {}
    public record Session(String id, String title, String preview, String status,
                          LocalDateTime createdAt, LocalDateTime updatedAt) {}
    public record ResponseProvenance(String modelStatus, int knowledgeHits, int localToolCalls, int toolFailures,
                                     AnswerEvidence.Diagnostics answerEvidence) {
        public ResponseProvenance(String modelStatus, int knowledgeHits, int localToolCalls, int toolFailures) {
            this(modelStatus, knowledgeHits, localToolCalls, toolFailures, null);
        }
    }
    public record Message(String id, String role, String content, LocalDateTime createdAt,
                          ResponseProvenance provenance) {}
    public record Assessment(int version, TriageResult result, LocalDateTime createdAt, String assistantMessageId) {}
    public record HumanReview(String id, String sessionId, String patient, String reason, String status,
                              LocalDateTime createdAt) {}
    public record Conversation(Session session, List<Message> messages, List<Assessment> assessments,
                               HumanReview humanReview) {}
    public record TimelineEvent(String sessionId, String sessionTitle, String messageId,
                                String content, String source, LocalDateTime occurredAt) {}
    public record Eligibility(boolean adultConfirmed, boolean forSelfConfirmed, boolean notPregnantConfirmed) {}
}
