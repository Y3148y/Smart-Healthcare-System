package com.aihospital.knowledge.domain;

import java.time.LocalDateTime;
import java.util.List;

/** Durable store for admin-curated knowledge documents and their approval state. */
public interface KnowledgeDocumentStore {
    List<StoredKnowledgeDocument> loadOrSeed(List<StoredKnowledgeDocument> bundledDocuments);
    StoredKnowledgeDocument find(String id);
    default KnowledgeImportReceipt findImport(String idempotencyKey) { return null; }
    void insert(StoredKnowledgeDocument document);
    default void insertImported(StoredKnowledgeDocument document, String idempotencyKey, String requestHash) {
        throw new UnsupportedOperationException("Durable knowledge import idempotency is unavailable");
    }
    StoredKnowledgeDocument approve(String id, int chunkCount, LocalDateTime updatedAt);
    void refreshChunkCount(String id, int chunkCount);
    default StoredKnowledgeDocument withdraw(String id, LocalDateTime updatedAt) {
        throw new UnsupportedOperationException("Knowledge withdrawal is unavailable");
    }
    default StoredKnowledgeDocument updatePendingMetadata(String id, KnowledgeMetadata metadata, LocalDateTime updatedAt) {
        throw new UnsupportedOperationException("Pending metadata update is unavailable");
    }
}
