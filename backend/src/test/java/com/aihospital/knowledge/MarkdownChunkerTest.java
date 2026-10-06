package com.aihospital.knowledge;

import com.aihospital.knowledge.domain.MarkdownChunker;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class MarkdownChunkerTest {
    private final MarkdownChunker chunker = new MarkdownChunker();

    @Test void headingsWithoutBlankLinesKeepParagraphsInTheirOwnSections() {
        String body = "## Scope\nOnly adults.\n## Limitations\nConditions are unknown.";
        var chunks = chunker.split("doc", "Title", "source", body);
        assertEquals(2, chunks.size());
        assertEquals("Only adults.", chunks.get(0).text());
        assertEquals(List.of("Scope"), chunks.get(0).sectionPath());
        assertEquals(List.of("Limitations"), chunks.get(1).sectionPath());
        assertFalse(chunks.get(0).text().contains("unknown"));
    }

    @Test void sparseHeadingLevelsDoNotMixSiblingSections() {
        String body = "# Root\n### First\nA\n### Second\nB\n## Parent\nC";
        var chunks = chunker.split("doc", "Title", "source", body);
        assertEquals(List.of("Root", "First"), chunks.get(0).sectionPath());
        assertEquals(List.of("Root", "Second"), chunks.get(1).sectionPath());
        assertEquals(List.of("Root", "Parent"), chunks.get(2).sectionPath());
    }

    @Test void longUnicodeParagraphHasBoundedWindowsAndCompleteCoverage() {
        String body = "\ud83e\udde0文本".repeat(400);
        var chunks = chunker.split("doc", "Title", "source", body);
        boolean[] covered = new boolean[body.codePointCount(0, body.length())];
        for (var chunk : chunks) {
            assertTrue(chunk.text().codePointCount(0, chunk.text().length()) <= 420);
            int from = body.offsetByCodePoints(0, chunk.start());
            int to = body.offsetByCodePoints(0, chunk.end());
            assertEquals(body.substring(from, to), chunk.text());
            assertEquals("unicode_code_point", chunk.positionUnit());
            for (int i = chunk.start(); i < chunk.end(); i++) covered[i] = true;
        }
        assertTrue(chunks.size() > 1);
        assertEquals(60, chunks.get(0).end() - chunks.get(1).start());
        for (boolean point : covered) assertTrue(point, "source content must not disappear between windows");
    }

    @Test void identityIsStableButBodyOrSourceRevisionInvalidatesIt() {
        var original = chunker.split("doc", "Title", "source", "Same paragraph\n\nSame paragraph");
        assertEquals(original, chunker.split("doc", "Title", "source", "Same paragraph\n\nSame paragraph"));
        assertNotEquals(original.get(0).chunkId(), original.get(1).chunkId());
        assertNotEquals(original.get(0).documentVersion(),
                chunker.split("doc", "Title", "other-source", "Same paragraph\n\nSame paragraph").get(0).documentVersion());
        assertNotEquals(original.get(0).documentVersion(),
                chunker.split("doc", "Title", "source", "Revised paragraph").get(0).documentVersion());
        assertEquals(MarkdownChunker.sha256(original.get(0).text()), original.get(0).contentSha256());
    }

    @Test void headingMarkersInsideCodeFenceAreContentRatherThanSectionBoundaries() {
        String body = "## Example\n```text\n## literal heading\n\nvalue\n```";
        var chunks = chunker.split("doc", "Title", "source", body);
        assertEquals(1, chunks.size());
        assertEquals(List.of("Example"), chunks.get(0).sectionPath());
        assertTrue(chunks.get(0).text().contains("## literal heading"));
    }
}
