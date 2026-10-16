package com.aihospital.knowledge;

import com.aihospital.knowledge.domain.*;
import com.aihospital.knowledge.infrastructure.demo.InMemoryKnowledgeCatalog;
import com.aihospital.knowledge.infrastructure.lexical.MemoryLexicalSearch;
import com.aihospital.knowledge.infrastructure.qdrant.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import java.time.LocalDateTime;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@SpringBootTest
class KnowledgeRevisionCoherenceTest {
    @Autowired KnowledgeDocumentStore store;
    @Autowired javax.sql.DataSource dataSource;
    @Autowired org.springframework.transaction.PlatformTransactionManager transactions;
    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        String supplied = System.getProperty("ai.test.knowledge-db-url");
        if (supplied != null && !supplied.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/ai_hospital_revision_test"))
            throw new IllegalArgumentException("Explicit isolated knowledge test database required");
        if (supplied != null && (System.getenv("KNOWLEDGE_TEST_PASSWORD") == null || System.getenv("KNOWLEDGE_TEST_PASSWORD").isBlank()))
            throw new IllegalArgumentException("KNOWLEDGE_TEST_PASSWORD is required for isolated MySQL");
        properties.add("spring.datasource.url", () -> supplied == null
                ? "jdbc:h2:mem:knowledge_revision_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1" : supplied);
        properties.add("spring.datasource.username", () -> supplied == null ? "sa" : "root");
        properties.add("spring.datasource.password", () -> supplied == null ? "" : System.getenv("KNOWLEDGE_TEST_PASSWORD"));
    }

    private HybridKnowledgeCatalog node() {
        var semantic = mock(QdrantSemanticIndex.class);
        when(semantic.status()).thenReturn("NOT_CONFIGURED");
        var node = new HybridKnowledgeCatalog(new InMemoryKnowledgeCatalog(), semantic, null, store);
        node.validateConfiguration();
        return node;
    }

    @Test void independentlyCachedNodesObservePendingApprovalAndWithdrawal() throws Exception {
        if (System.getProperty("ai.test.knowledge-db-url") != null) {
            try (var connection = dataSource.getConnection()) {
                assertEquals("MySQL", connection.getMetaData().getDatabaseProductName());
            }
        }
        var first = node(); var second = node();
        String query = "unique" + UUID.randomUUID().toString().replace("-", "");
        var document = first.addDocument(query, query + " synthetic fixture");
        assertTrue(second.documents().stream().anyMatch(item -> item.id().equals(document.id())));
        assertTrue(second.retrieve(query, 3, 0).evidence().isEmpty());
        first.approveDocument(document.id());
        assertTrue(second.retrieve(query, 3, 0).evidence().stream().anyMatch(item -> item.title().equals(query)));
        second.withdrawDocument(document.id());
        assertTrue(first.retrieve(query, 3, 0).evidence().isEmpty());
    }

    @Test void failedImportRollsBackRevisionAndLosingDocumentTogether() {
        var now = LocalDateTime.now();
        String title = UUID.randomUUID().toString(), body = "Synthetic exact retry fixture";
        String key = KnowledgeImportIdentity.key(title, body);
        var winner = new StoredKnowledgeDocument(UUID.randomUUID().toString(), title, body, "synthetic",
                "PENDING_REVIEW", 1, now, null);
        store.insertImported(winner, key, "a".repeat(64));
        long before = store.revision();
        var loser = new StoredKnowledgeDocument(UUID.randomUUID().toString(), title, body, "synthetic",
                "PENDING_REVIEW", 1, now, null);
        assertThrows(RuntimeException.class, () -> store.insertImported(loser, key, "a".repeat(64)));
        assertEquals(before, store.revision());
        assertNull(store.find(loser.id()));
        assertEquals(before, store.snapshot().revision());
    }

    @Test void repeatedApprovalAndWithdrawalDoNotAdvanceRevisionAgain() {
        var catalog = node();
        var document = catalog.addDocument("revision-" + UUID.randomUUID(), "Synthetic content");
        catalog.approveDocument(document.id());
        long approved = store.revision();
        catalog.approveDocument(document.id());
        assertEquals(approved, store.revision());
        catalog.withdrawDocument(document.id());
        long withdrawn = store.revision();
        assertTrue(withdrawn > approved);
        catalog.withdrawDocument(document.id());
        assertEquals(withdrawn, store.revision());
    }

    @Test void metadataCorrectedOnOtherNodeIsLoadedBeforeApproval() {
        var first = node(); var second = node();
        var pending = new KnowledgeMetadata(1, "zh-CN", "source_extract",
                List.of(new KnowledgeMetadata.Source("fixture", "Synthetic", "https://example.invalid/old", null, null)),
                List.of(), List.of(), List.of(), List.of(), List.of("general_information"), "pending", null);
        var document = first.addDocument("metadata-" + UUID.randomUUID(), "Synthetic content", pending);
        var corrected = new KnowledgeMetadata(1, "zh-CN", "source_extract",
                List.of(new KnowledgeMetadata.Source("fixture", "Synthetic", "https://example.invalid/new", null, null)),
                List.of(), List.of(), List.of(), List.of(), List.of("general_information"), "permitted", "Synthetic test only");
        second.updatePendingMetadata(document.id(), corrected);
        assertEquals("READY", first.approveDocument(document.id()).status());
        assertEquals(corrected, first.documentMetadata(document.id()));
        assertEquals("https://example.invalid/new", first.documentDetails(document.id()).source());
    }

    @Test void withdrawalDuringRetrievalFencesPreviouslyCapturedEvidence() {
        var writer = node();
        String query = "fence" + UUID.randomUUID().toString().replace("-", "");
        var document = writer.addDocument(query, query + " synthetic fixture");
        writer.approveDocument(document.id());
        var memory = new MemoryLexicalSearch();
        LexicalSearch delayed = (input, corpus, limit, minimum) -> {
            writer.withdrawDocument(document.id());
            return memory.search(input, corpus, limit, minimum);
        };
        var reader = new HybridKnowledgeCatalog(new InMemoryKnowledgeCatalog(), mock(QdrantSemanticIndex.class),
                null, store, new ObjectMapper(), delayed);
        reader.validateConfiguration();
        var result = reader.inspect(query, 3, 0);
        assertEquals("KNOWLEDGE_SNAPSHOT_CHANGED", result.mode());
        assertFalse(result.candidates().isEmpty());
        assertTrue(result.retrieval().evidence().isEmpty());
        assertFalse(result.retrieval().grounded());
        assertTrue(result.candidates().stream().allMatch(candidate -> !candidate.kept()));
    }

    @Test void pendingDraftDoesNotInvalidatePublishedRetrieval() {
        var writer = node();
        String query = "draft" + UUID.randomUUID().toString().replace("-", "");
        var document = writer.addDocument(query, query + " synthetic fixture");
        writer.approveDocument(document.id());
        long published = store.publishedRevision();
        var memory = new MemoryLexicalSearch();
        LexicalSearch delayed = (input, corpus, limit, minimum) -> {
            writer.addDocument("pending-" + UUID.randomUUID(), "Synthetic draft, not published");
            return memory.search(input, corpus, limit, minimum);
        };
        var reader = new HybridKnowledgeCatalog(new InMemoryKnowledgeCatalog(), mock(QdrantSemanticIndex.class),
                null, store, new ObjectMapper(), delayed);
        reader.validateConfiguration();
        assertFalse(reader.inspect(query, 3, 0).retrieval().evidence().isEmpty());
        assertEquals(published, store.publishedRevision());
    }

    @Test void unavailableRevisionCannotReuseAnExistingApprovedCache() {
        var local = new InMemoryKnowledgeCatalog();
        var documents = local.persistedDocuments();
        var unavailable = mock(KnowledgeDocumentStore.class);
        when(unavailable.loadOrSeed(anyList())).thenReturn(documents);
        when(unavailable.supportsRevisionTracking()).thenReturn(true);
        when(unavailable.revision()).thenReturn(0L);
        when(unavailable.snapshot()).thenReturn(new KnowledgeDocumentStore.Snapshot(0, documents));
        var semantic = mock(QdrantSemanticIndex.class);
        var reader = new HybridKnowledgeCatalog(local, semantic, null, unavailable);
        reader.validateConfiguration();
        assertFalse(local.approvedCorpus().isEmpty());
        when(unavailable.revision()).thenThrow(new IllegalStateException("Synthetic database outage"));
        var result = reader.inspect("胸痛", 3, 0);
        assertEquals("KNOWLEDGE_STORE_UNAVAILABLE", result.mode());
        assertTrue(result.retrieval().evidence().isEmpty());
        assertEquals("KNOWLEDGE_STORE_UNAVAILABLE", reader.retrievalEvents().get(0).mode());
        verify(semantic, never()).ensureIndexed(anyList());
    }

    @Test void invalidSnapshotDoesNotPartiallyReplacePublishedLocalMaps() {
        var local = new InMemoryKnowledgeCatalog();
        var before = local.persistedDocuments(); var corpus = local.approvedCorpus();
        var corrupted = new ArrayList<>(before);
        corrupted.add(new StoredKnowledgeDocument("invalid", "", "fixture", "fixture", "READY", 1, LocalDateTime.now(), null));
        assertThrows(IllegalStateException.class, () -> local.restorePersistedDocuments(corrupted));
        assertEquals(before, local.persistedDocuments());
        assertEquals(corpus, local.approvedCorpus());
    }

    @Test void snapshotWaitsForWriterTransactionAndReadsCommittedRowsWithRevision() throws Exception {
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        var entered = new java.util.concurrent.CountDownLatch(1);
        var future = new java.util.concurrent.atomic.AtomicReference<java.util.concurrent.Future<KnowledgeDocumentStore.Snapshot>>();
        String id = UUID.randomUUID().toString();
        long before = store.revision();
        try {
            new org.springframework.transaction.support.TransactionTemplate(transactions).executeWithoutResult(status -> {
                store.insert(new StoredKnowledgeDocument(id, "Concurrent synthetic fixture", "Synthetic body", "fixture",
                        "PENDING_REVIEW", 1, LocalDateTime.now(), null));
                future.set(executor.submit(() -> { entered.countDown(); return store.snapshot(); }));
                try { assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS)); }
                catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); throw new RuntimeException(interrupted); }
                assertThrows(java.util.concurrent.TimeoutException.class,
                        () -> future.get().get(200, java.util.concurrent.TimeUnit.MILLISECONDS));
            });
            var snapshot = future.get().get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(before + 1, snapshot.revision());
            assertTrue(snapshot.documents().stream().anyMatch(document -> document.id().equals(id)));
        } finally { executor.shutdownNow(); }
    }
}
