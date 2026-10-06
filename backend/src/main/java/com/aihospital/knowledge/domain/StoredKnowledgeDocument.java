package com.aihospital.knowledge.domain;

import java.time.LocalDateTime;

/** Persisted admin-owned knowledge document state; it contains no patient conversation data. */
public record StoredKnowledgeDocument(String id, String title, String body, String source,
                                      String status, int chunkCount, LocalDateTime updatedAt,
                                      KnowledgeMetadata metadata) {
    public StoredKnowledgeDocument(String id, String title, String body, String source,
            String status, int chunkCount, LocalDateTime updatedAt) {
        this(id, title, body, source, status, chunkCount, updatedAt, null);
    }
}
