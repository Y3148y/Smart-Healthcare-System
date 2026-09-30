package com.aihospital.knowledge.domain;

import com.aihospital.shared.model.Models.Evidence;
import com.aihospital.shared.model.Models.KnowledgeDocument;
import java.util.List;

public interface KnowledgeCatalog {
    record Retrieval(List<Evidence> evidence, boolean grounded, String message) {}
    List<KnowledgeDocument> documents();
    KnowledgeDocument addDocument(String title, String body);
    KnowledgeDocument approveDocument(String id);
    Retrieval retrieve(String query, int maxResults, double minimumScore);
    default List<Evidence> search(String query) { return retrieve(query, 5, 0.28).evidence(); }
    default String retrievalMode() { return "LOCAL_LEXICAL_VECTOR"; }
}
