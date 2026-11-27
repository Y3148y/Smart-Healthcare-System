package com.aihospital.knowledge;

import com.aihospital.knowledge.domain.MarkdownChunker;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class MarkdownChunkerTest {
    @Test void wholeParagraphCheckReusesHeadingAndUnicodeBoundariesWithoutChangingChunks() {
        String body="# Root\r\nComplete 🧠 condition.\r\n\r\nSecond paragraph.\r\n## Next\r\nOther context.";
        var split=chunker.split("doc","Title","source",body);
        assertEquals(2,split.size());
        for(var chunk:split)assertTrue(MarkdownChunker.completeParagraphRange(body,chunk));
        var longSplit=chunker.split("doc","Title","source","🧠".repeat(800));
        assertTrue(longSplit.stream().noneMatch(c->MarkdownChunker.completeParagraphRange("🧠".repeat(800),c)));
    }
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
            assertTrue(MarkdownChunker.tokenCount(chunk.text()) <= MarkdownChunker.MAX_TOKENS);
            int from = body.offsetByCodePoints(0, chunk.start());
            int to = body.offsetByCodePoints(0, chunk.end());
            assertEquals(body.substring(from, to), chunk.text());
            assertEquals("unicode_code_point", chunk.positionUnit());
            for (int i = chunk.start(); i < chunk.end(); i++) covered[i] = true;
        }
        assertTrue(chunks.size() > 1);
        String overlap = body.substring(body.offsetByCodePoints(0, chunks.get(1).start()),
                body.offsetByCodePoints(0, chunks.get(0).end()));
        assertFalse(overlap.isEmpty());
        assertTrue(MarkdownChunker.tokenCount(overlap) <= MarkdownChunker.OVERLAP_TOKENS);
        for (boolean point : covered) assertTrue(point, "source content must not disappear between windows");
    }

    @Test void identityIsStableButBodyOrSourceRevisionInvalidatesIt() {
        String body = "## First\nSame paragraph\n## Second\nSame paragraph";
        var original = chunker.split("doc", "Title", "source", body);
        assertEquals(original, chunker.split("doc", "Title", "source", body));
        assertNotEquals(original.get(0).chunkId(), original.get(1).chunkId());
        assertNotEquals(original.get(0).documentVersion(),
                chunker.split("doc", "Title", "other-source", body).get(0).documentVersion());
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

    @Test void shortConditionListAndExceptionStayTogetherWithinSection() {
        String body = "## Scope\nUse only if:\n\n- condition A\n\n- condition B\n\nNot suitable when C.\n## Other\nUnrelated content.";
        var chunks = chunker.split("doc", "Title", "source", body);
        assertEquals(2, chunks.size());
        assertTrue(chunks.get(0).text().contains("Use only if:"));
        assertTrue(chunks.get(0).text().contains("condition B"));
        assertTrue(chunks.get(0).text().contains("Not suitable when C."));
        assertFalse(chunks.get(0).text().contains("Unrelated"));
    }

    @Test void packingPreservesSourceOffsetsAndNeverExceedsWindow() {
        String body = "## Scope\n" + ("🧠".repeat(43) + "\n\n").repeat(5);
        var chunks = chunker.split("doc", "Title", "source", body);
        assertEquals(2, chunks.size());
        for (var chunk : chunks) {
            assertTrue(MarkdownChunker.tokenCount(chunk.text()) <= MarkdownChunker.MAX_TOKENS);
            assertEquals(body.substring(body.offsetByCodePoints(0, chunk.start()), body.offsetByCodePoints(0, chunk.end())), chunk.text());
        }
    }

    @Test void countsBpeTokensRatherThanCharactersAndKeepsLiteralSpecialTokens() {
        assertEquals(2, MarkdownChunker.tokenCount("hello world"));
        String body = "hello ".repeat(300).strip();
        var chunks = chunker.split("doc", "Title", "source", body);
        assertEquals(1, chunks.size());
        assertTrue(body.length() > MarkdownChunker.MAX_TOKENS);
        assertEquals(body, chunks.get(0).text());
        String literal = "source <|endoftext|> text";
        assertEquals(literal, chunker.split("doc", "Title", "source", literal).get(0).text());
    }

    @Test void multilingualWindowsPreserveEverySourcePointAndActualTokenBudget() {
        var encoding = com.knuddels.jtokkit.Encodings.newLazyEncodingRegistry()
                .getEncoding(com.knuddels.jtokkit.api.EncodingType.CL100K_BASE);
        for (String sample : List.of("急性心肌梗死🧠。", "hello world; ", " e\u0301𠀀👩‍⚕️\r\n", "<|endoftext|>")) {
            String body = sample.repeat(180).strip();
            var chunks = chunker.split("doc", "Title", "source", body);
            int previousEnd = 0;
            for (var chunk : chunks) {
                assertTrue(encoding.countTokensOrdinary(chunk.text()) <= 420);
                assertTrue(chunk.start() <= previousEnd, "no missing source between windows");
                assertTrue(chunk.end() > previousEnd, "windows must advance");
                assertEquals(body.substring(body.offsetByCodePoints(0, chunk.start()),
                        body.offsetByCodePoints(0, chunk.end())).replace('\r', ' '), chunk.text());
                previousEnd = chunk.end();
            }
            assertEquals(body.codePointCount(0, body.length()), previousEnd);
        }
    }
}
