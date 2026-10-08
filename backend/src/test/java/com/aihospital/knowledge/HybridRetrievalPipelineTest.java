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
    @Test void selectedSectionsComeFromApprovedChunkNotUntrustedVectorPayload() {
        var local = new InMemoryKnowledgeCatalog(false);
        var document = local.addDocument("合成资料", "# 合成主题\n\n## 检查限定\n\n这是一段合成测试正文，不是医学资料。");
        local.approveDocument(document.id());
        var chunk = local.documentChunks(document.id()).get(0);
        var hit = local.approvedCorpus().get(0);
        var semantic = mock(QdrantSemanticIndex.class);
        when(semantic.ensureIndexed(anyList())).thenReturn(true);
        when(semantic.searchDetailed(anyString(), anyInt(), anyDouble())).thenReturn(
                new QdrantSemanticIndex.SearchResult(List.of(new Evidence(hit.title(), hit.source(), hit.excerpt(), .9,
                        List.of("伪造的向量标题"))), "READY"));
        var catalog = new HybridKnowledgeCatalog(local, semantic);
        ReflectionTestUtils.setField(catalog, "rerankEnabled", false);
        var selected = catalog.inspect("合成测试正文", 1, .28).retrieval().evidence().get(0);
        assertEquals(chunk.sectionPath(), selected.sectionPath());
        assertTrue(selected.sectionPath().contains("检查限定"));
        var reference = com.aihospital.triage.domain.AnswerEvidence.references(List.of(selected)).get(0);
        assertEquals(selected.sectionPath(), reference.sectionPath());
        assertEquals("UNKNOWN", reference.applicability());
    }

    @Test void conflictingSectionsAreNotArbitrarilyAssignedToIdenticalText() {
        var local = new InMemoryKnowledgeCatalog(false);
        for (String heading : List.of("甲背景", "乙背景")) {
            var document = local.addDocument("相同资料标题", "# " + heading + "\n\n完全相同的合成测试正文。");
            local.approveDocument(document.id());
        }
        var semantic = mock(QdrantSemanticIndex.class);
        when(semantic.ensureIndexed(anyList())).thenReturn(true);
        when(semantic.searchDetailed(anyString(), anyInt(), anyDouble())).thenReturn(
                new QdrantSemanticIndex.SearchResult(List.of(local.approvedCorpus().get(0)), "READY"));
        var catalog = new HybridKnowledgeCatalog(local, semantic);
        ReflectionTestUtils.setField(catalog, "rerankEnabled", false);
        assertTrue(catalog.inspect("合成测试正文", 1, .28).retrieval().evidence().get(0).sectionPath().isEmpty());
    }
    @Test void explicitBypassReservesDenseRouteBeforeRrfWithoutInventingOrDuplicatingEvidence() {
        var local = new InMemoryKnowledgeCatalog(false);
        for (String name : List.of("dense-one", "dense-two", "dual-one", "dual-two")) {
            var document = local.addDocument(name, name.startsWith("dual") ? "目标任务目标任务" : "不同的测试正文" + name);
            local.approveDocument(document.id());
        }
        var corpus = local.approvedCorpus();
        var dense = List.of("dense-one", "dense-two", "dual-one", "dual-two").stream()
                .map(name -> corpus.stream().filter(e -> name.equals(e.title())).findFirst().orElseThrow()).toList();
        var semantic = mock(QdrantSemanticIndex.class);
        when(semantic.ensureIndexed(anyList())).thenReturn(true);
        when(semantic.searchDetailed(anyString(), anyInt(), anyDouble()))
                .thenReturn(new QdrantSemanticIndex.SearchResult(dense, "READY"));
        var reranker = mock(BailianReranker.class);
        var catalog = new HybridKnowledgeCatalog(local, semantic, reranker);
        ReflectionTestUtils.setField(catalog, "rerankEnabled", false);
        ReflectionTestUtils.setField(catalog, "requireSemantic", true);
        var report = catalog.inspect("目标任务", 3, 0.28);
        assertEquals(List.of("dense-one", "dense-two"), report.retrieval().evidence().stream()
                .limit(2).map(com.aihospital.shared.model.Models.Evidence::title).toList());
        assertEquals(3, report.retrieval().evidence().size());
        assertTrue(report.retrieval().evidence().get(2).title().startsWith("dual"));
        assertEquals(3, report.retrieval().evidence().stream().map(com.aihospital.knowledge.domain.Bm25Retriever::key).distinct().count());
        assertEquals(2, report.candidates().stream().filter(c -> c.kept()
                && "selected_semantic_reserve_without_rerank".equals(c.reason())).count());
        assertEquals("dense-one", catalog.inspect("目标任务", 1, 0.28).retrieval().evidence().get(0).title());
        verifyNoInteractions(reranker);
        // Ordinary rerank remains authoritative; bypass diversity is not applied.
        ReflectionTestUtils.setField(catalog, "rerankEnabled", true);
        when(reranker.rank(anyString(), anyList(), anyInt()))
                .thenReturn(new BailianReranker.Result(List.of(dense.get(3)), "OK"));
        assertEquals(List.of(dense.get(3)), catalog.inspect("目标任务", 3, 0.28).retrieval().evidence());
    }

    @Test void explicitBypassDoesNotCallRerankAndCannotBypassRequiredSemantic() {
        var local = new InMemoryKnowledgeCatalog();
        var confirmed = local.approvedCorpus().stream().filter(e -> e.title().contains("呼吸")).findFirst().orElseThrow();
        var semantic = mock(QdrantSemanticIndex.class);
        when(semantic.ensureIndexed(anyList())).thenReturn(true);
        when(semantic.status()).thenReturn("READY");
        when(semantic.searchDetailed(anyString(), anyInt(), anyDouble()))
                .thenReturn(new QdrantSemanticIndex.SearchResult(List.of(confirmed), "READY"));
        var reranker = mock(BailianReranker.class);
        var catalog = new HybridKnowledgeCatalog(local, semantic, reranker);
        ReflectionTestUtils.setField(catalog, "rerankEnabled", false);
        ReflectionTestUtils.setField(catalog, "requireSemantic", true);
        var report = catalog.inspect("咳嗽", 3, 0.28);
        assertEquals("DISABLED_BY_CONFIGURATION", report.rerankStatus());
        assertEquals("HYBRID_QDRANT_UNRERANKED", report.mode());
        assertTrue(report.retrieval().grounded());
        verifyNoInteractions(reranker);
        when(semantic.searchDetailed(anyString(), anyInt(), anyDouble()))
                .thenReturn(new QdrantSemanticIndex.SearchResult(List.of(), "READY"));
        var lexicalOnly = catalog.inspect("咳嗽",3,0.28);
        assertFalse(lexicalOnly.retrieval().grounded());
        assertFalse(lexicalOnly.candidates().isEmpty());
        assertTrue(lexicalOnly.candidates().stream().allMatch(c ->
                "unconfirmed_lexical_only_without_rerank".equals(c.reason())));
        ReflectionTestUtils.setField(catalog, "requireRerank", true);
        assertThrows(IllegalStateException.class, catalog::validateConfiguration);
        assertFalse(catalog.inspect("咳嗽", 3, 0.28).retrieval().grounded());
        ReflectionTestUtils.setField(catalog, "requireRerank", false);
        when(semantic.searchDetailed(anyString(), anyInt(), anyDouble()))
                .thenReturn(new QdrantSemanticIndex.SearchResult(List.of(), "SEARCH_UNAVAILABLE"));
        assertFalse(catalog.inspect("咳嗽", 3, 0.28).retrieval().grounded());
    }
    @Test void reusesLexicalIndexUntilApprovedCorpusChanges() {
        var local = new InMemoryKnowledgeCatalog();
        var semantic = mock(QdrantSemanticIndex.class);
        when(semantic.ensureIndexed(anyList())).thenReturn(true);
        when(semantic.status()).thenReturn("READY");
        when(semantic.searchDetailed(anyString(), anyInt(), anyDouble()))
                .thenReturn(new QdrantSemanticIndex.SearchResult(List.of(), "READY"));
        var catalog = new HybridKnowledgeCatalog(local, semantic);
        catalog.inspect("流鼻涕", 3, 0.28);
        Object first = ReflectionTestUtils.getField(catalog, "lexicalIndex");
        catalog.inspect("胸痛", 3, 0.28);
        assertSame(first, ReflectionTestUtils.getField(catalog, "lexicalIndex"));

        var pending = catalog.addDocument("测试新资料", "新的测试内容");
        catalog.approveDocument(pending.id());
        catalog.inspect("新的测试内容", 3, 0.28);
        assertNotSame(first, ReflectionTestUtils.getField(catalog, "lexicalIndex"));
    }

    @Test void mergesBothRoutesBeforeTruncationAndReranksAgainstOriginalQuery() {
        var local = new InMemoryKnowledgeCatalog();
        // Deliberate second candidate: do not depend on a seed document splitting
        // into multiple tiny paragraphs for the pre-truncation fusion contract.
        var additional = local.addDocument("融合测试资料", "流鼻涕测试候选，用于验证截断前融合，不是医学语料。");
        local.approveDocument(additional.id());
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
