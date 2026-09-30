package com.aihospital.knowledge.infrastructure.qdrant;

import com.aihospital.knowledge.domain.KnowledgeCatalog;
import com.aihospital.knowledge.infrastructure.demo.InMemoryKnowledgeCatalog;
import com.aihospital.shared.model.Models.Evidence;
import com.aihospital.shared.model.Models.KnowledgeDocument;
import org.springframework.context.annotation.Primary;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Combines local lexical retrieval with optional provider embeddings in Qdrant. */
@Primary
@Component
public class HybridKnowledgeCatalog implements KnowledgeCatalog {
    private final InMemoryKnowledgeCatalog local;
    private final QdrantSemanticIndex semantic;
    @Value("${ai.retrieval.min-score:0.28}") private double lexicalMinScore = 0.28;
    @Value("${ai.retrieval.semantic-min-score:0.45}") private double semanticMinScore = 0.45;
    public HybridKnowledgeCatalog(InMemoryKnowledgeCatalog local, QdrantSemanticIndex semantic) {
        this.local = local; this.semantic = semantic;
    }
    @Override public List<KnowledgeDocument> documents() { return local.documents(); }
    @Override public KnowledgeDocument addDocument(String title, String body) { return local.addDocument(title, body); }
    @Override public KnowledgeDocument approveDocument(String id) {
        KnowledgeDocument approved = local.approveDocument(id);
        semantic.ensureIndexed(local.approvedCorpus()); // Failure is logged; local retrieval stays available.
        return approved;
    }
    @Override public String retrievalMode() { return semantic.indexed() ? "HYBRID_QDRANT" : "LOCAL_LEXICAL_VECTOR"; }
    @Override public List<Evidence> search(String query) { return retrieve(query, 5, lexicalMinScore).evidence(); }
    @Override public Retrieval retrieve(String query, int maxResults, double minimumScore) {
        Retrieval lexical = local.retrieve(query, maxResults, minimumScore);
        if (!semantic.ensureIndexed(local.approvedCorpus())) return lexical;
        List<Evidence> semanticHits = semantic.search(query, maxResults, Math.max(semanticMinScore, minimumScore));
        Map<String,Evidence> merged = new LinkedHashMap<>();
        for (Evidence hit : semanticHits) merged.put(hit.title() + "|" + hit.excerpt(), hit);
        for (Evidence hit : lexical.evidence()) merged.putIfAbsent(hit.title() + "|" + hit.excerpt(), hit);
        List<Evidence> selected = merged.values().stream().limit(Math.max(1, maxResults)).toList();
        return new Retrieval(selected, !selected.isEmpty(), selected.isEmpty()
                ? "Qdrant 语义检索和本地检索均未命中达到阈值的片段"
                : "已联合 Qdrant 语义检索与本地检索，返回 " + selected.size() + " 个知识片段");
    }
}
