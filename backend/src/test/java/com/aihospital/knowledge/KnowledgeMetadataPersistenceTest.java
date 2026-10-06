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
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
class KnowledgeMetadataPersistenceTest {
    @Autowired KnowledgeCatalog catalog;
    @Autowired KnowledgeDocumentStore store;
    @Autowired org.springframework.test.web.servlet.MockMvc mvc;
    @Autowired com.fasterxml.jackson.databind.ObjectMapper json;

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

    @Test void pendingPermissionCanBeCorrectedWithoutChangingBodyOrPublishing() {
        var document = catalog.addDocument("Correction fixture", "Nonmedical correction fixture body.", metadata("pending", null));
        var corrected = metadata("permitted", "Synthetic fixture only");
        var updated = catalog.updatePendingMetadata(document.id(), corrected);
        assertEquals("PENDING_REVIEW", updated.status());
        assertEquals(document.body(), updated.body());
        assertEquals(corrected, store.find(document.id()).metadata());
        catalog.approveDocument(document.id());
        assertThrows(IllegalArgumentException.class, () -> catalog.updatePendingMetadata(document.id(), metadata("restricted", null)));
        assertThrows(IllegalArgumentException.class, () -> store.updatePendingMetadata(document.id(), metadata("restricted", null), LocalDateTime.now()));
        assertEquals(corrected, store.find(document.id()).metadata());
    }

    @Test void approvalCannotRacePastARestrictivePermissionCorrection() throws Exception {
        var document = catalog.addDocument("Race fixture", "Nonmedical concurrency fixture body.",
                metadata("permitted", "Synthetic fixture only"));
        var start = new java.util.concurrent.CountDownLatch(1);
        var pool = java.util.concurrent.Executors.newFixedThreadPool(2);
        try {
            var approval = pool.submit(() -> {
                start.await();
                try { store.approve(document.id(), 1, LocalDateTime.now()); return true; }
                catch (IllegalArgumentException expected) { return false; }
            });
            var correction = pool.submit(() -> {
                start.await();
                try { store.updatePendingMetadata(document.id(), metadata("restricted", null), LocalDateTime.now()); return true; }
                catch (IllegalArgumentException expected) { return false; }
            });
            start.countDown();
            boolean approved = approval.get(10, java.util.concurrent.TimeUnit.SECONDS);
            boolean corrected = correction.get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertNotEquals(approved, corrected, "exactly one conflicting transition may succeed");
            var persisted = store.find(document.id());
            assertFalse("READY".equals(persisted.status()) && !persisted.metadata().mayPublish());
        } finally { pool.shutdownNow(); }
    }

    @Test void correctionEndpointRejectsUnauthenticatedAndPatientRoles() throws Exception {
        String request = json.writeValueAsString(metadata("permitted", "Synthetic fixture only"));
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/admin/knowledge/fixture/metadata")
                .contentType("application/json").content(request))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
        String token = new com.aihospital.shared.security.JwtService().issue("fixture-patient", "PATIENT");
        mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/admin/knowledge/fixture/metadata")
                .header("Authorization", "Bearer " + token).contentType("application/json").content(request))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
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
