package com.aihospital.knowledge;

import com.aihospital.knowledge.infrastructure.demo.InMemoryKnowledgeCatalog;
import com.aihospital.knowledge.infrastructure.qdrant.*;
import com.aihospital.shared.model.Models.Evidence;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class HybridRetrievalPipelineTest {
    @Test void mergesBothRoutesBeforeTruncationAndReranksAgainstOriginalQuery() {
        var local = new InMemoryKnowledgeCatalog();
        var semantic = mock(QdrantSemanticIndex.class);
        var reranker = mock(BailianReranker.class);
        var nasal = local.approvedCorpus().stream().filter(e -> e.title().contains("流鼻涕")).findFirst().orElseThrow();
        when(semantic.ensureIndexed(anyList())).thenReturn(true);
        when(semantic.status()).thenReturn("READY");
        when(semantic.searchDetailed(eq("流鼻涕"), eq(20), eq(0.45)))
                .thenReturn(new QdrantSemanticIndex.SearchResult(List.of(nasal), "READY"));
        when(reranker.rank(eq("流鼻涕"), anyList(), eq(1)))
                .thenReturn(new BailianReranker.Result(List.of(nasal), "OK"));
        var catalog = new HybridKnowledgeCatalog(local, semantic, reranker);
        var report = catalog.inspect("流鼻涕", 1, 0.28);
        assertEquals("HYBRID_QDRANT_RERANKED", report.mode());
        assertEquals(1, report.retrieval().evidence().size());
        assertTrue(report.candidates().stream().anyMatch(c -> c.lexicalRank() > 0 && c.semanticRank() > 0));
        assertTrue(report.candidates().size() > report.retrieval().evidence().size());
        verify(semantic).searchDetailed("流鼻涕", 20, 0.45);
    }

    @Test void persistentOrphanVectorCannotBecomeEvidence() {
        var semantic = mock(QdrantSemanticIndex.class);
        when(semantic.ensureIndexed(anyList())).thenReturn(true);
        when(semantic.status()).thenReturn("READY");
        when(semantic.searchDetailed(anyString(), anyInt(), anyDouble())).thenReturn(new QdrantSemanticIndex.SearchResult(List.of(
                new Evidence("撤回的资料", "https://old.invalid", "火星建筑", 0.99)), "READY"));
        var report = new HybridKnowledgeCatalog(new InMemoryKnowledgeCatalog(), semantic).inspect("火星建筑", 3, 0.28);
        assertFalse(report.retrieval().grounded());
        assertTrue(report.candidates().isEmpty());
    }

    @Test void requiredRerankFailureDoesNotPublishUnfilteredCandidates() {
        var semantic = mock(QdrantSemanticIndex.class);
        when(semantic.status()).thenReturn("NOT_CONFIGURED");
        var reranker = mock(BailianReranker.class);
        when(reranker.rank(anyString(), anyList(), anyInt())).thenReturn(new BailianReranker.Result(List.of(), "HTTP_429"));
        var catalog = new HybridKnowledgeCatalog(new InMemoryKnowledgeCatalog(), semantic, reranker);
        ReflectionTestUtils.setField(catalog, "requireRerank", true);
        var report = catalog.inspect("咳嗽", 3, 0.28);
        assertFalse(report.retrieval().grounded());
        assertFalse(report.candidates().isEmpty());
        assertTrue(report.candidates().stream().noneMatch(HybridKnowledgeCatalog.FusionRecord::kept));
        assertEquals("HTTP_429", report.rerankStatus());
    }

    @Test void strictProfileRejectsMissingConfigurationAndSemanticFailure() {
        var semantic = mock(QdrantSemanticIndex.class);
        when(semantic.status()).thenReturn("SEARCH_UNAVAILABLE");
        var catalog = new HybridKnowledgeCatalog(new InMemoryKnowledgeCatalog(), semantic);
        ReflectionTestUtils.setField(catalog, "requireSemantic", true);
        assertThrows(IllegalStateException.class, catalog::validateConfiguration);
        assertFalse(catalog.inspect("咳嗽", 3, 0.28).retrieval().grounded());
    }

    @Test void sameContentDoesNotGainScoreMerelyByUsingHttps() {
        var http = new Evidence("咳嗽", "http://example.invalid", "咳嗽持续时间", 0);
        var https = new Evidence("咳嗽", "https://example.invalid", "咳嗽持续时间", 0);
        var hits = com.aihospital.knowledge.domain.Bm25Retriever.search("咳嗽", List.of(http, https), 10, 0);
        assertEquals(2, hits.size());
        assertEquals(hits.get(0).score(), hits.get(1).score());
    }
}
