package com.aihospital.knowledge;

import com.aihospital.knowledge.domain.LexicalSearch;
import com.aihospital.knowledge.infrastructure.demo.InMemoryKnowledgeCatalog;
import com.aihospital.knowledge.infrastructure.qdrant.*;
import com.aihospital.shared.model.Models.Evidence;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class UnrerankedDocumentDiversityTest {
    private InMemoryKnowledgeCatalog corpus(boolean additional) {
        var local = new InMemoryKnowledgeCatalog(false);
        var first = local.addDocument("alpha", "# alpha\n\n## first\n\nSynthetic alpha one.\n\n## second\n\nSynthetic alpha two.\n\n## third\n\nSynthetic alpha three.");
        local.approveDocument(first.id());
        if (additional) for (String title : List.of("beta", "gamma")) {
            var document = local.addDocument(title, "# " + title + "\n\nSynthetic " + title + " context.");
            local.approveDocument(document.id());
        }
        return local;
    }
    private List<Evidence> ordered(InMemoryKnowledgeCatalog local) {
        return local.approvedCorpus().stream().sorted(Comparator.comparing(Evidence::title)
                .thenComparing(Evidence::excerpt)).toList();
    }
    private HybridKnowledgeCatalog catalog(InMemoryKnowledgeCatalog local, List<Evidence> lexical, List<Evidence> dense) {
        var semantic = mock(QdrantSemanticIndex.class);
        when(semantic.status()).thenReturn(dense.isEmpty() ? "NOT_CONFIGURED" : "READY");
        when(semantic.modelName()).thenReturn("synthetic-embedding");
        when(semantic.ensureIndexed(anyList())).thenReturn(true);
        when(semantic.searchDetailed(anyString(), anyInt(), anyDouble()))
                .thenReturn(new QdrantSemanticIndex.SearchResult(dense, dense.isEmpty() ? "NOT_CONFIGURED" : "READY"));
        LexicalSearch search = (query, corpus, limit, minimum) -> new LexicalSearch.Result(lexical, "MEMORY_BM25_READY", Map.of());
        var catalog = new HybridKnowledgeCatalog(local, semantic, null, null, new ObjectMapper(), search);
        ReflectionTestUtils.setField(catalog, "rerankEnabled", false);
        ReflectionTestUtils.setField(catalog, "documentDiversity", true);
        return catalog;
    }
    @Test void repeatedTopChunksDoNotOccupyAllSlotsWhileOtherDocumentsAreEligible() {
        var local = corpus(true); var ranked = ordered(local);
        assertEquals(List.of("alpha", "alpha", "alpha", "beta", "gamma"), ranked.stream().map(Evidence::title).toList());
        var report = catalog(local, ranked, List.of()).inspect("Synthetic", 3, 0);
        assertEquals(List.of("alpha", "beta", "gamma"), report.retrieval().evidence().stream().map(Evidence::title).toList());
        assertEquals(ranked.get(0).excerpt(), report.retrieval().evidence().get(0).excerpt());
        assertEquals(5, report.candidates().size(), "Candidate ranking remains available unchanged");
        var defaultCatalog = catalog(local, ranked, List.of());
        ReflectionTestUtils.setField(defaultCatalog, "documentDiversity", false);
        assertEquals(List.of("alpha", "alpha", "alpha"), defaultCatalog.inspect("Synthetic", 3, 0)
                .retrieval().evidence().stream().map(Evidence::title).toList(), "Default selection remains unchanged");
        assertEquals("false", defaultCatalog.runtimeDetails().get("unrerankedDocumentDiversity"));
    }
    @Test void oneEligibleDocumentCanStillSupplyMultipleDistinctSections() {
        var local = corpus(false); var ranked = ordered(local);
        var selected = catalog(local, ranked, List.of()).inspect("Synthetic", 3, 0).retrieval().evidence();
        assertEquals(3, selected.size());
        assertEquals(ranked.stream().map(Evidence::excerpt).toList(), selected.stream().map(Evidence::excerpt).toList());
        assertEquals(3, selected.stream().map(Evidence::excerpt).distinct().count());
    }
    @Test void preservesTwoDenseReservationsFromSameDocumentWithoutAdmittingUnconfirmedLexicalContent() {
        var local = corpus(true); var ranked = ordered(local);
        var dense = List.of(ranked.get(0), ranked.get(1), ranked.get(3));
        var catalog = catalog(local, ranked, dense);
        ReflectionTestUtils.setField(catalog, "requireSemantic", true);
        var report = catalog.inspect("Synthetic", 3, 0);
        assertEquals(List.of("alpha", "alpha", "beta"), report.retrieval().evidence().stream().map(Evidence::title).toList());
        assertEquals(2, report.candidates().stream().filter(c -> c.kept()
                && "selected_semantic_reserve_without_rerank".equals(c.reason())).count());
        assertFalse(report.retrieval().evidence().stream().anyMatch(e -> e.title().equals("gamma")));
    }
}
