package com.aihospital.knowledge.infrastructure.qdrant;

import com.aihospital.knowledge.domain.KnowledgeCatalog;
import com.aihospital.knowledge.infrastructure.demo.InMemoryKnowledgeCatalog;
import com.aihospital.shared.model.Models.Evidence;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.test.util.ReflectionTestUtils;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class KnowledgeIndexBuildTest {
    @TempDir Path temp;

    @Test void corpusFingerprintIsStableAcrossOrderingAndChangesWithContent() {
        var one = new Evidence("头痛资料", "https://source.invalid/a", "头痛持续时间说明", 0);
        var two = new Evidence("胸痛资料", "https://source.invalid/b", "胸痛需要评估", 0);
        var configuration = new QdrantSemanticIndex.CollectionConfiguration("green", 2, 0, 1024, "Cosine", 16, 100, 10000);
        var first = KnowledgeIndexBuildManifest.create("triage-v3", "embedding-v1", "section-window-v1", List.of(one, two), 2, configuration);
        var reordered = KnowledgeIndexBuildManifest.create("triage-v3", "embedding-v1", "section-window-v1", List.of(two, one), 2, configuration);
        var changed = KnowledgeIndexBuildManifest.create("triage-v3", "embedding-v1", "section-window-v1",
                List.of(one, new Evidence("胸痛资料", "https://source.invalid/b", "正文已更新", 0)), 2, configuration);

        assertEquals(first.corpusSha256(), reordered.corpusSha256());
        assertNotEquals(first.corpusSha256(), changed.corpusSha256());
        assertEquals(64, first.corpusSha256().length());
        assertEquals(2, first.chunkCount());
        assertEquals(3, first.schemaVersion());
        assertEquals(0, first.collectionIndexedVectorsCount());
        assertEquals("section-window-v1", first.chunkingProfile());
        assertEquals(1024, first.vectorSize());
        assertEquals(10000, first.fullScanThresholdKb());
    }

    @Test void provenanceCannotDescribeDifferentOrDuplicateChunks() {
        var corpus = List.of(new Evidence("Fixture", "https://source.invalid", "Fixture body", 1));
        var configuration = new QdrantSemanticIndex.CollectionConfiguration("green", 2, 2, 1024, "Cosine", 16, 100, 10000);
        var chunks = new com.aihospital.knowledge.domain.MarkdownChunker().split("fixture", "Fixture",
                "https://source.invalid", "Fixture body");
        assertThrows(IllegalArgumentException.class, () -> KnowledgeIndexBuildManifest.createWithProvenance(
                "collection", "model", "profile", corpus, 1, configuration, List.of()));
        var unrelated = new com.aihospital.knowledge.domain.MarkdownChunker().split("fixture", "Fixture",
                "https://source.invalid", "Different body");
        assertThrows(IllegalArgumentException.class, () -> KnowledgeIndexBuildManifest.createWithProvenance(
                "collection", "model", "profile", corpus, 1, configuration, unrelated));
        assertThrows(IllegalArgumentException.class, () -> KnowledgeIndexBuildManifest.createWithProvenance(
                "collection", "model", "profile", List.of(corpus.get(0), corpus.get(0)), 2, configuration,
                List.of(chunks.get(0), chunks.get(0))));
    }

    @Test void successfulOfflineBuildWritesOnlyNonSensitiveManifest(@TempDir Path outputDir) throws Exception {
        var corpus = List.of(new Evidence("流鼻涕资料", "https://source.invalid/nose", "本地知识正文不得写入清单", 1));
        var catalog = mock(KnowledgeCatalog.class);
        when(catalog.syncIndex()).thenReturn(Map.of("success", "true", "status", "INDEXED"));
        var local = mock(InMemoryKnowledgeCatalog.class);
        when(local.approvedCorpus()).thenReturn(corpus);
        var document = new com.aihospital.shared.model.Models.KnowledgeDocument("fixture-doc", corpus.get(0).title(),
                corpus.get(0).excerpt(), 1, "READY", java.time.LocalDateTime.now());
        when(local.documents()).thenReturn(List.of(document));
        var chunks = new com.aihospital.knowledge.domain.MarkdownChunker().split(document.id(), document.title(),
                corpus.get(0).source(), document.body());
        when(local.documentChunks(document.id())).thenReturn(chunks);
        when(local.chunkingProfile()).thenReturn("test-chunk-profile-v1");
        var semantic = mock(QdrantSemanticIndex.class);
        when(semantic.indexedCount(corpus)).thenReturn(1);
        when(semantic.collectionConfiguration()).thenReturn(
                new QdrantSemanticIndex.CollectionConfiguration("green", 1, 1, 1024, "Cosine", 16, 100, 10000));
        var runner = new KnowledgeIndexBuildRunner(catalog, local, semantic, new ObjectMapper());
        Path manifest = outputDir.resolve("build.json");
        ReflectionTestUtils.setField(runner, "collection", "triage-v3");
        ReflectionTestUtils.setField(runner, "embeddingModel", "embedding-v1");
        ReflectionTestUtils.setField(runner, "manifestPath", manifest.toString());

        runner.run(null);

        String content = Files.readString(manifest);
        assertTrue(content.contains("corpusSha256"));
        assertTrue(content.contains("embedding-v1"));
        assertTrue(content.contains("chunkingProfile"));
        assertTrue(content.contains("hnswEfConstruct"));
        assertTrue(content.contains("vectorSize"));
        assertEquals(4, new ObjectMapper().readTree(content).path("schemaVersion").asInt());
        assertEquals(1, new ObjectMapper().readTree(content).path("collectionIndexedVectorsCount").asInt());
        assertEquals(chunks.get(0).chunkId(), new ObjectMapper().readTree(content).path("chunks").get(0).path("chunkId").asText());
        assertFalse(content.contains("本地知识正文不得写入清单"));
        assertFalse(content.contains("API_KEY"));
        verify(catalog).syncIndex();
        verify(semantic).collectionConfiguration();
    }

    @Test void failedIndexSyncDoesNotPublishManifest() {
        var catalog = mock(KnowledgeCatalog.class);
        when(catalog.syncIndex()).thenReturn(Map.of("success", "false", "status", "INDEX_UNAVAILABLE"));
        var local = mock(InMemoryKnowledgeCatalog.class);
        when(local.approvedCorpus()).thenReturn(List.of(new Evidence("标题", "来源", "正文", 1)));
        var runner = new KnowledgeIndexBuildRunner(catalog, local, mock(QdrantSemanticIndex.class), new ObjectMapper());
        Path manifest = temp.resolve("not-published.json");
        ReflectionTestUtils.setField(runner, "manifestPath", manifest.toString());

        assertThrows(IllegalStateException.class, () -> runner.run(null));
        assertFalse(Files.exists(manifest));
    }

    @Test void failedCollectionConfigurationReadDoesNotPublishManifest() throws Exception {
        var corpus = List.of(new Evidence("标题", "https://source.invalid/a", "正文", 1));
        var catalog = mock(KnowledgeCatalog.class);
        when(catalog.syncIndex()).thenReturn(Map.of("success", "true", "status", "INDEXED"));
        var local = mock(InMemoryKnowledgeCatalog.class);
        when(local.approvedCorpus()).thenReturn(corpus);
        when(local.chunkingProfile()).thenReturn("test-chunk-profile-v1");
        var semantic = mock(QdrantSemanticIndex.class);
        when(semantic.indexedCount(corpus)).thenReturn(1);
        when(semantic.collectionConfiguration()).thenThrow(new IllegalStateException("collection details unavailable"));
        var runner = new KnowledgeIndexBuildRunner(catalog, local, semantic, new ObjectMapper());
        Path manifest = temp.resolve("unverified-collection.json");
        ReflectionTestUtils.setField(runner, "manifestPath", manifest.toString());

        assertThrows(IllegalStateException.class, () -> runner.run(null));
        assertFalse(Files.exists(manifest));
    }
}
