package com.aihospital.knowledge.domain;

import com.aihospital.shared.model.Models.Evidence;
import com.aihospital.shared.model.Models.KnowledgeDocument;
import java.util.List;

public interface KnowledgeCatalog {
    List<KnowledgeDocument> documents();
    KnowledgeDocument addDocument(String title, String body);
    List<Evidence> search(String query);
}
