package com.aihospital.knowledge;

import com.aihospital.knowledge.domain.KnowledgeDocumentStore;
import com.aihospital.knowledge.infrastructure.demo.InMemoryKnowledgeCatalog;
import com.aihospital.knowledge.infrastructure.qdrant.HybridKnowledgeCatalog;
import com.aihospital.knowledge.infrastructure.qdrant.QdrantSemanticIndex;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class KnowledgeCatalogPersistenceFailureTest {
    @Test void failedInsertDoesNotLeaveADocumentVisibleInTheCache() {
        var local = new InMemoryKnowledgeCatalog();
        var store = mock(KnowledgeDocumentStore.class);
        var semantic = mock(QdrantSemanticIndex.class);
        var catalog = new HybridKnowledgeCatalog(local, semantic, null, store);
        int originalSize = catalog.documents().size();
        doThrow(new IllegalStateException("database unavailable")).when(store).insert(any());

        assertThrows(IllegalStateException.class,
                () -> catalog.addDocument("Unpersisted document", "Synthetic content"));

        assertEquals(originalSize, catalog.documents().size());
        assertTrue(catalog.documents().stream().noneMatch(d -> d.title().equals("Unpersisted document")));
        verifyNoInteractions(semantic);
    }

    @Test void failedApprovalCannotPublishPendingContentToEitherIndex() {
        var local = new InMemoryKnowledgeCatalog();
        var pending = local.addDocument("Pending document", "Synthetic content awaiting approval");
        var store = mock(KnowledgeDocumentStore.class);
        var semantic = mock(QdrantSemanticIndex.class);
        var catalog = new HybridKnowledgeCatalog(local, semantic, null, store);
        when(store.approve(eq(pending.id()), anyInt(), any()))
                .thenThrow(new IllegalStateException("database unavailable"));

        assertThrows(IllegalStateException.class, () -> catalog.approveDocument(pending.id()));

        assertEquals("PENDING_REVIEW", local.documentDetails(pending.id()).document().status());
        assertTrue(local.approvedCorpus().stream().noneMatch(e -> e.title().equals(pending.title())));
        verifyNoInteractions(semantic);
    }
}
