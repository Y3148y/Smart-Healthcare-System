package com.aihospital.triage.domain;

import com.aihospital.shared.model.Models.TriageResult;
import java.time.LocalDateTime;
import java.util.List;

/** Stable conversation snapshots returned by the triage use case. */
public final class TriageRecords {
    private TriageRecords() {}
    public record Session(String id, String title, String preview, String status,
                          LocalDateTime createdAt, LocalDateTime updatedAt) {}
    public record Message(String id, String role, String content, LocalDateTime createdAt) {}
    public record Assessment(int version, TriageResult result, LocalDateTime createdAt, String assistantMessageId) {}
    public record Conversation(Session session, List<Message> messages, List<Assessment> assessments) {}
    public record TimelineEvent(String sessionId, String sessionTitle, String messageId,
                                String content, String source, LocalDateTime occurredAt) {}
    public record Eligibility(boolean adultConfirmed, boolean forSelfConfirmed, boolean notPregnantConfirmed) {}
}
