package com.aihospital.knowledge;

import com.aihospital.knowledge.infrastructure.demo.InMemoryKnowledgeCatalog;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class KnowledgeIntakeContentTest {
    @Test void headingsOnlyCannotCreateAnEmptySearchDocument() {
        var catalog = new InMemoryKnowledgeCatalog();
        int before = catalog.documents().size();
        assertThrows(IllegalArgumentException.class, () -> catalog.addDocument("Fixture", "# Heading\n\n## Another heading"));
        assertEquals(before, catalog.documents().size());
        var valid = catalog.addDocument("Fixture", "# Heading\n\nActual non-medical fixture content.");
        assertEquals(1, catalog.documentChunks(valid.id()).size());
        assertEquals("PENDING_REVIEW", valid.status());
    }
}
