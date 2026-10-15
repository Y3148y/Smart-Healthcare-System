package com.aihospital.triage.domain;

import java.time.LocalDateTime;

/** Durable request identity; no model draft or tool arguments in the operational state. */
public record TriageTurn(String id, String sessionId, String requestId, String status,
                         String userMessageId, String assistantMessageId, LocalDateTime startedAt,
                         LocalDateTime expiresAt, LocalDateTime finishedAt, String failureCode) {
    public record Claim(TriageTurn turn, String outcome) {}
}
