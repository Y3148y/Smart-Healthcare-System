package com.aihospital.knowledge;

import com.aihospital.knowledge.domain.KnowledgeDocumentStore;
import com.aihospital.knowledge.infrastructure.demo.InMemoryKnowledgeCatalog;
import com.aihospital.knowledge.infrastructure.qdrant.HybridKnowledgeCatalog;
import com.aihospital.knowledge.infrastructure.qdrant.QdrantSemanticIndex;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class EmptyKnowledgeBootstrapTest {
    @Test void emptyDatabaseSnapshotClearsFixturesWithoutRestoringBundledEvidence() {
        var local = new InMemoryKnowledgeCatalog();
        assertFalse(local.approvedCorpus().isEmpty());
        local.restorePersistedDocuments(List.of());
        assertTrue(local.documents().isEmpty());
        assertTrue(local.persistedDocuments().isEmpty());
        assertTrue(local.approvedCorpus().isEmpty());
    }

    @Test void missingSnapshotStillFailsWithoutDiscardingExistingRecords() {
        var local = new InMemoryKnowledgeCatalog();
        var before = local.persistedDocuments();
        assertThrows(IllegalArgumentException.class, () -> local.restorePersistedDocuments(null));
        assertEquals(before, local.persistedDocuments());
    }

    @Test void freshDatabaseCanInitializeWithoutSeedingLegacyDocuments() {
        var local = new InMemoryKnowledgeCatalog();
        local.restorePersistedDocuments(List.of());
        var store = mock(KnowledgeDocumentStore.class);
        when(store.loadOrSeed(List.of())).thenReturn(List.of());
        var catalog = new HybridKnowledgeCatalog(local, mock(QdrantSemanticIndex.class), null, store);
        assertDoesNotThrow(catalog::validateConfiguration);
        assertTrue(catalog.documents().isEmpty());
        assertTrue(local.approvedCorpus().isEmpty());
        verify(store).loadOrSeed(List.of());
        verifyNoMoreInteractions(store);
    }
}
