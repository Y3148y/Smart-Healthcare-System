package com.aihospital.knowledge;

import com.aihospital.knowledge.domain.KnowledgeCatalog;
import com.aihospital.knowledge.domain.KnowledgeDocumentStore;
import com.aihospital.knowledge.infrastructure.demo.InMemoryKnowledgeCatalog;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:knowledge_persistence_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE")
class KnowledgeDocumentPersistenceTest {
    @Autowired KnowledgeCatalog catalog;
    @Autowired KnowledgeDocumentStore store;

    @Test void adminDocumentAndApprovalSurviveRebuildingTheSearchCache() {
        var seeded = store.loadOrSeed(List.of());
        assertEquals(11, seeded.size(), "bundled knowledge is seeded once into an empty persistent catalog");
        assertTrue(seeded.stream().allMatch(document -> "READY".equals(document.status())));

        String title = "persistence-test-" + UUID.randomUUID();
        String body = "内容在进程重启后仍需存在。\n\n审核后才进入检索。";
        var pending = catalog.addDocument(title, body);

        var storedPending = store.find(pending.id());
        assertNotNull(storedPending);
        assertEquals("PENDING_REVIEW", storedPending.status());
        assertEquals(body, storedPending.body());

        var rebuiltBeforeApproval = new InMemoryKnowledgeCatalog();
        rebuiltBeforeApproval.restorePersistedDocuments(store.loadOrSeed(List.of()));
        assertEquals("PENDING_REVIEW", rebuiltBeforeApproval.documentDetails(pending.id()).document().status());
        assertFalse(rebuiltBeforeApproval.approvedCorpus().stream().anyMatch(e -> e.title().equals(title)));

        var approved = catalog.approveDocument(pending.id());
        assertEquals("READY", approved.status());
        assertEquals("READY", store.find(pending.id()).status());

        var rebuiltAfterApproval = new InMemoryKnowledgeCatalog();
        rebuiltAfterApproval.restorePersistedDocuments(store.loadOrSeed(List.of()));
        assertEquals("READY", rebuiltAfterApproval.documentDetails(pending.id()).document().status());
        assertTrue(rebuiltAfterApproval.approvedCorpus().stream().anyMatch(e -> e.title().equals(title)));
    }
}
