package com.aihospital.knowledge.domain;

import java.time.LocalDateTime;
import java.util.List;

/** Durable store for admin-curated knowledge documents and their approval state. */
public interface KnowledgeDocumentStore {
    List<StoredKnowledgeDocument> loadOrSeed(List<StoredKnowledgeDocument> bundledDocuments);
    StoredKnowledgeDocument find(String id);
    void insert(StoredKnowledgeDocument document);
    StoredKnowledgeDocument approve(String id, int chunkCount, LocalDateTime updatedAt);
    void refreshChunkCount(String id, int chunkCount);
    default StoredKnowledgeDocument updatePendingMetadata(String id, KnowledgeMetadata metadata, LocalDateTime updatedAt) {
        throw new UnsupportedOperationException("Pending metadata update is unavailable");
    }
}
