package com.aihospital.knowledge;

import com.aihospital.knowledge.domain.KnowledgeCatalog;
import com.aihospital.knowledge.domain.KnowledgeDocumentStore;
import com.aihospital.knowledge.infrastructure.demo.InMemoryKnowledgeCatalog;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:knowledge_withdrawal;MODE=MySQL;DATABASE_TO_LOWER=TRUE")
class KnowledgeWithdrawalTest {
    @Autowired KnowledgeCatalog catalog;
    @Autowired KnowledgeDocumentStore store;

    @Test void withdrawalPreservesBodyAndProvenanceAcrossRestartAndRequiresReapproval() {
        var added = catalog.addDocument("withdrawal fixture", "Unique withdrawal fixture evidence.");
        catalog.approveDocument(added.id());
        assertTrue(catalog.retrieve("Unique withdrawal fixture", 8, 0.01).evidence().stream()
                .anyMatch(e -> e.title().equals(added.title())));
        var chunks = catalog.documentChunks(added.id());
        var references = chunks.stream().map(chunk -> new com.aihospital.shared.model.Models.Evidence(
                added.title(), chunk.source(), chunk.text(), .123, chunk.sectionPath())).toList();
        assertEquals(KnowledgeCatalog.EvidenceState.CURRENT, catalog.evidenceState(references));
        var withdrawn = catalog.withdrawDocument(added.id());
        assertEquals("PENDING_REVIEW", withdrawn.status());
        assertEquals(KnowledgeCatalog.EvidenceState.CHANGED, catalog.evidenceState(references));
        assertEquals(added.body(), withdrawn.body());
        assertEquals("PENDING_REVIEW", store.find(added.id()).status());
        assertTrue(catalog.retrieve("Unique withdrawal fixture", 8, 0.01).evidence().stream()
                .noneMatch(e -> e.title().equals(added.title())));
        assertEquals(withdrawn, catalog.withdrawDocument(added.id()));
        var rebuilt = new InMemoryKnowledgeCatalog();
        rebuilt.restorePersistedDocuments(store.loadOrSeed(List.of()));
        assertFalse(rebuilt.approvedCorpus().stream().anyMatch(e -> e.title().equals(added.title())));
        assertEquals(chunks, rebuilt.documentChunks(added.id()));
        catalog.approveDocument(added.id());
        assertEquals("READY", store.find(added.id()).status());
    }

    @Test void unknownDocumentCannotBeWithdrawn() {
        assertThrows(IllegalArgumentException.class, () -> catalog.withdrawDocument("not-existing"));
    }
}
