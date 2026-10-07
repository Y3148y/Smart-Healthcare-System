package com.aihospital.knowledge;

import com.aihospital.knowledge.infrastructure.demo.InMemoryKnowledgeCatalog;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class BundledKnowledgeApprovalTest {
    @Test void liveBootstrapKeepsLegacyBodyButDoesNotPublishIt() {
        var catalog = new InMemoryKnowledgeCatalog(false);
        assertEquals(11, catalog.documents().size());
        assertTrue(catalog.documents().stream().allMatch(d -> "PENDING_REVIEW".equals(d.status()) && !d.body().isBlank()));
        assertTrue(catalog.approvedCorpus().isEmpty());
        var restart = new InMemoryKnowledgeCatalog(false);
        restart.restorePersistedDocuments(catalog.persistedDocuments());
        assertTrue(restart.approvedCorpus().isEmpty());
    }
    @Test void demoCompatibilityAndPersistedWithdrawalAreRetained() {
        var demo = new InMemoryKnowledgeCatalog();
        assertFalse(demo.approvedCorpus().isEmpty());
        demo.restorePersistedDocuments(new InMemoryKnowledgeCatalog(false).persistedDocuments());
        assertTrue(demo.approvedCorpus().isEmpty());
    }
}
