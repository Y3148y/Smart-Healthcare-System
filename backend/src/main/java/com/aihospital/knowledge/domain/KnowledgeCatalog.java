package com.aihospital.knowledge.domain;

import com.aihospital.shared.model.Models.Evidence;
import com.aihospital.shared.model.Models.KnowledgeDocument;
import java.util.List;
import java.util.Map;

public interface KnowledgeCatalog {
    enum EvidenceState { CURRENT, CHANGED, UNAVAILABLE, UNTRACKED }
    /** Exact approved-source membership, not a clinical support judgement or save-time lock. */
    default EvidenceState evidenceState(List<Evidence> evidence) { return EvidenceState.UNTRACKED; }
    static boolean sameSource(Evidence a, Evidence b) {
        return java.util.Objects.equals(a.title(), b.title()) && java.util.Objects.equals(a.source(), b.source())
                && java.util.Objects.equals(a.excerpt(), b.excerpt()) && a.sectionPath().equals(b.sectionPath());
    }
    record Retrieval(List<Evidence> evidence, boolean grounded, String message) {}
    record DocumentDetail(KnowledgeDocument document, String source, List<Evidence> segments,
                          String indexStatus, int indexedChunks, String indexNote) {}
    List<KnowledgeDocument> documents();
    KnowledgeDocument addDocument(String title, String body);
    default KnowledgeDocument addDocument(String title, String body, KnowledgeMetadata metadata) {
        throw new UnsupportedOperationException("Structured knowledge import is unavailable");
    }
    default KnowledgeDocument addDocument(String title, String body, KnowledgeMetadata metadata, String idempotencyKey) {
        throw new UnsupportedOperationException("Idempotent structured knowledge import is unavailable");
    }
    default KnowledgeMetadata documentMetadata(String id) { return null; }
    default KnowledgeDocument updatePendingMetadata(String id, KnowledgeMetadata metadata) {
        throw new UnsupportedOperationException("Pending metadata update is unavailable");
    }
    KnowledgeDocument approveDocument(String id);
    default KnowledgeDocument withdrawDocument(String id) {
        throw new UnsupportedOperationException("Knowledge withdrawal is unavailable");
    }
    Retrieval retrieve(String query, int maxResults, double minimumScore);
    default List<Evidence> search(String query) { return retrieve(query, 5, 0.28).evidence(); }
    default String retrievalMode() { return "LOCAL_LEXICAL_VECTOR"; }
    default Map<String, String> runtimeDetails() { return Map.of("mode", retrievalMode()); }
    default Object retrievalDetails(String query) { return retrieve(query, 3, 0.28); }
    default DocumentDetail documentDetails(String id) { throw new IllegalArgumentException("知识资料不存在"); }
    default List<KnowledgeChunk> documentChunks(String id) { throw new IllegalArgumentException("知识资料不存在"); }
    default Map<String, String> syncIndex() { return Map.of("status", "UNSUPPORTED"); }
    default List<?> retrievalEvents() { return List.of(); }
}
