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
        var first = KnowledgeIndexBuildManifest.create("triage-v3", "embedding-v1", List.of(one, two), 2);
        var reordered = KnowledgeIndexBuildManifest.create("triage-v3", "embedding-v1", List.of(two, one), 2);
        var changed = KnowledgeIndexBuildManifest.create("triage-v3", "embedding-v1",
                List.of(one, new Evidence("胸痛资料", "https://source.invalid/b", "正文已更新", 0)), 2);

        assertEquals(first.corpusSha256(), reordered.corpusSha256());
        assertNotEquals(first.corpusSha256(), changed.corpusSha256());
        assertEquals(64, first.corpusSha256().length());
        assertEquals(2, first.chunkCount());
    }

    @Test void successfulOfflineBuildWritesOnlyNonSensitiveManifest(@TempDir Path outputDir) throws Exception {
        var corpus = List.of(new Evidence("流鼻涕资料", "https://source.invalid/nose", "本地知识正文不得写入清单", 1));
        var catalog = mock(KnowledgeCatalog.class);
        when(catalog.syncIndex()).thenReturn(Map.of("success", "true", "status", "INDEXED"));
        var local = mock(InMemoryKnowledgeCatalog.class);
        when(local.approvedCorpus()).thenReturn(corpus);
        var semantic = mock(QdrantSemanticIndex.class);
        when(semantic.indexedCount(corpus)).thenReturn(1);
        var runner = new KnowledgeIndexBuildRunner(catalog, local, semantic, new ObjectMapper());
        Path manifest = outputDir.resolve("build.json");
        ReflectionTestUtils.setField(runner, "collection", "triage-v3");
        ReflectionTestUtils.setField(runner, "embeddingModel", "embedding-v1");
        ReflectionTestUtils.setField(runner, "manifestPath", manifest.toString());

        runner.run(null);

        String content = Files.readString(manifest);
        assertTrue(content.contains("corpusSha256"));
        assertTrue(content.contains("embedding-v1"));
        assertFalse(content.contains("本地知识正文不得写入清单"));
        assertFalse(content.contains("API_KEY"));
        verify(catalog).syncIndex();
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
}
