package com.aihospital.knowledge.domain;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/** Deterministic paragraph/heading splitting; no medical facts or classifications are inferred. */
public final class MarkdownChunker {
    public static final String VERSION = "markdown-section-pack-codepoint-v3";
    public static final int MAX_LENGTH = 420;
    public static final int OVERLAP = 60;
    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.+?)\\s*$");
    private record Block(int start, int end, List<String> headings) {}

    public List<KnowledgeChunk> split(String id, String title, String source, String body) {
        if (id == null || title == null || source == null || body == null)
            throw new IllegalArgumentException("Chunk input is incomplete");
        String version = sha256(title + "\u0000" + source + "\u0000" + body);
        List<KnowledgeChunk> result = new ArrayList<>();
        List<Block> blocks = new ArrayList<>();
        List<String> headings = new ArrayList<>();
        List<Integer> headingLevels = new ArrayList<>();
        int paragraph = -1, offset = 0;
        boolean fenced = false;
        String fence = "";
        while (offset < body.length()) {
            int newline = body.indexOf('\n', offset);
            int lineEnd = newline < 0 ? body.length() : newline;
            String line = body.substring(offset, lineEnd).strip();
            boolean fenceLine = line.startsWith("```") || line.startsWith("~~~");
            var heading = HEADING.matcher(line);
            if (!fenced && heading.matches()) {
                if (paragraph >= 0) blocks.add(new Block(paragraph, offset, List.copyOf(headings)));
                paragraph = -1;
                int level = heading.group(1).length();
                while (!headingLevels.isEmpty() && headingLevels.get(headingLevels.size() - 1) >= level) {
                    headingLevels.remove(headingLevels.size() - 1);
                    headings.remove(headings.size() - 1);
                }
                // Missing hierarchy levels are not filled with invented headings.
                headings.add(heading.group(2));
                headingLevels.add(level);
            } else if (!fenced && line.isBlank()) {
                if (paragraph >= 0) blocks.add(new Block(paragraph, offset, List.copyOf(headings)));
                paragraph = -1;
            } else if (paragraph < 0) paragraph = offset;
            if (fenceLine) {
                String marker = line.substring(0, 3);
                if (!fenced) { fenced = true; fence = marker; }
                else if (marker.equals(fence)) fenced = false;
            }
            offset = newline < 0 ? body.length() : newline + 1;
        }
        if (paragraph >= 0) blocks.add(new Block(paragraph, body.length(), List.copyOf(headings)));
        Block packed = null;
        for (Block block : blocks) {
            if (packed != null && packed.headings().equals(block.headings())
                    && body.codePointCount(packed.start(), block.end()) <= MAX_LENGTH) {
                packed = new Block(packed.start(), block.end(), packed.headings());
            } else {
                if (packed != null) append(result, id, version, source, body, packed.start(), packed.end(), packed.headings());
                packed = block;
            }
        }
        if (packed != null) append(result, id, version, source, body, packed.start(), packed.end(), packed.headings());
        return List.copyOf(result);
    }

    private void append(List<KnowledgeChunk> result, String id, String version, String source,
            String body, int start, int end, List<String> headings) {
        while (start < end && Character.isWhitespace(body.charAt(start))) start++;
        while (end > start && Character.isWhitespace(body.charAt(end - 1))) end--;
        while (start < end) {
            int remaining = body.codePointCount(start, end);
            int stop = body.offsetByCodePoints(start, Math.min(MAX_LENGTH, remaining));
            if (stop < end) {
                int boundary = Math.max(body.lastIndexOf('。', stop - 1), body.lastIndexOf('；', stop - 1));
                if (boundary >= start && body.codePointCount(start, boundary + 1) > 120) stop = boundary + 1;
            }
            int trimmedStart = start, trimmedEnd = stop;
            while (trimmedStart < trimmedEnd && Character.isWhitespace(body.charAt(trimmedStart))) trimmedStart++;
            while (trimmedEnd > trimmedStart && Character.isWhitespace(body.charAt(trimmedEnd - 1))) trimmedEnd--;
            if (trimmedEnd > trimmedStart) {
                String text = body.substring(trimmedStart, trimmedEnd).replace('\r', ' ');
                int from = body.codePointCount(0, trimmedStart), to = body.codePointCount(0, trimmedEnd);
                String chunkId = UUID.nameUUIDFromBytes((id + "|" + version + "|" + VERSION + "|" + from + "|" + to)
                        .getBytes(StandardCharsets.UTF_8)).toString();
                result.add(new KnowledgeChunk(chunkId, id, version, result.size(), headings, from, to,
                        "unicode_code_point", text, sha256(text), source, VERSION));
            }
            if (stop >= end) break;
            int overlap = Math.min(OVERLAP, body.codePointCount(start, stop) - 1);
            start = body.offsetByCodePoints(stop, -overlap);
        }
    }

    public static String sha256(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException failure) { throw new IllegalStateException(failure); }
    }
}
