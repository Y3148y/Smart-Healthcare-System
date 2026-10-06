package com.aihospital.knowledge;

import com.aihospital.knowledge.domain.KnowledgeCatalog;
import com.aihospital.knowledge.domain.KnowledgeDocumentStore;
import com.aihospital.knowledge.domain.KnowledgeMetadata;
import com.aihospital.knowledge.infrastructure.demo.InMemoryKnowledgeCatalog;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import java.time.Instant;
import java.time.LocalDateTime;
import com.aihospital.knowledge.domain.StoredKnowledgeDocument;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(properties = "spring.datasource.url=jdbc:h2:mem:knowledge_metadata_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE")
class KnowledgeMetadataPersistenceTest {
    @Autowired KnowledgeCatalog catalog;
    @Autowired KnowledgeDocumentStore store;

    private KnowledgeMetadata metadata(String permission, String proof) {
        return new KnowledgeMetadata(1, "zh-CN", "source_extract",
                List.of(new KnowledgeMetadata.Source("synthetic", "Test fixture",
                        "https://example.invalid/fixture", Instant.parse("2026-10-06T00:00:00Z"), "a".repeat(64))),
                List.of("fixture"), List.of("adult"), List.of(), List.of(),
                List.of("general_information"), permission, proof);
    }

    @Test void declaredSourceIsPersistedButNeverAutoApproved() {
        var declared = metadata("permitted", "Synthetic test fixture only");
        var document = catalog.addDocument("Metadata fixture", "# Fixture\n\nNon-medical test content.", declared);
        assertEquals("PENDING_REVIEW", document.status());
        assertEquals(declared, store.find(document.id()).metadata());
        var pendingCache = new InMemoryKnowledgeCatalog();
        pendingCache.restorePersistedDocuments(store.loadOrSeed(List.of()));
        assertFalse(pendingCache.approvedCorpus().stream().anyMatch(e -> e.title().equals(document.title())));
        catalog.approveDocument(document.id());
        var restored = new InMemoryKnowledgeCatalog();
        restored.restorePersistedDocuments(store.loadOrSeed(List.of()));
        assertEquals(declared, restored.documentMetadata(document.id()));
        assertEquals("READY", restored.documentDetails(document.id()).document().status());
        assertEquals(catalog.documentChunks(document.id()), restored.documentChunks(document.id()));
    }

    @Test void unresolvedOrRestrictedPermissionCannotPublish() {
        for (String permission : List.of("pending", "restricted")) {
            var document = catalog.addDocument("Permission " + permission, "Fixture body.", metadata(permission, null));
            assertThrows(IllegalArgumentException.class, () -> catalog.approveDocument(document.id()));
            assertEquals("PENDING_REVIEW", store.find(document.id()).status());
            var restored = new InMemoryKnowledgeCatalog();
            restored.restorePersistedDocuments(store.loadOrSeed(List.of()));
            assertThrows(IllegalArgumentException.class, () -> restored.approveDocument(document.id()));
        }
    }

    @Test void legacyDocumentsDoNotAcquireInventedProvenance() {
        var document = catalog.addDocument("Legacy fixture", "Legacy body.");
        assertNull(catalog.documentMetadata(document.id()));
        assertNull(store.find(document.id()).metadata());
    }

    @Test void invalidPermissionAndSourceClaimsAreRejected() {
        assertThrows(IllegalArgumentException.class, () -> metadata("permitted", null));
        assertThrows(IllegalArgumentException.class, () -> metadata("approved", "proof"));
        assertThrows(IllegalArgumentException.class, () -> new KnowledgeMetadata.Source("fixture", "publisher",
                "file:///secret", null, null));
        assertThrows(IllegalArgumentException.class, () -> new KnowledgeMetadata.Source("fixture", "publisher",
                "https://user:password@example.invalid/path", null, null));
        assertThrows(IllegalArgumentException.class, () -> new KnowledgeMetadata.Source("fixture", "publisher",
                "https://example.invalid/path", null, "not-a-snapshot-hash"));
    }

    @Test void inconsistentPublishedPermissionFailsClosedDuringRestore() {
        var restored = new InMemoryKnowledgeCatalog();
        var corrupted = new StoredKnowledgeDocument("fixture", "Fixture", "Fixture body.",
                "https://example.invalid/fixture", "READY", 1, LocalDateTime.now(), metadata("restricted", null));
        assertThrows(IllegalStateException.class, () -> restored.restorePersistedDocuments(List.of(corrupted)));
    }
}
