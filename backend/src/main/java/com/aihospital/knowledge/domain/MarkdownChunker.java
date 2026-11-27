package com.aihospital.knowledge.domain;

import com.knuddels.jtokkit.Encodings;
import com.knuddels.jtokkit.api.Encoding;
import com.knuddels.jtokkit.api.EncodingType;
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
    public static final String TOKENIZER = "cl100k_base";
    public static final String VERSION = "markdown-section-pack-cl100k_base-jtokkit1.1.0-v4";
    public static final int MAX_TOKENS = 420;
    public static final int OVERLAP_TOKENS = 60;
    // Packing only: leave room under the answer's 1200 UTF-16 character display gate.
    // A single large source paragraph is still token-split; display guards remain independent.
    public static final int MAX_PACKED_CHARS = 1000;
    private static final Encoding ENCODING = Encodings.newLazyEncodingRegistry().getEncoding(EncodingType.CL100K_BASE);
    private static final Pattern HEADING = Pattern.compile("^(#{1,6})\\s+(.+?)\\s*$");
    private record Block(int start, int end, List<String> headings) {}

    public List<KnowledgeChunk> split(String id, String title, String source, String body) {
        if (id == null || title == null || source == null || body == null)
            throw new IllegalArgumentException("Chunk input is incomplete");
        String version = sha256(title + "\u0000" + source + "\u0000" + body);
        List<KnowledgeChunk> result = new ArrayList<>();
        Block packed = null;
        for (Block block : blocks(body)) {
            if (packed != null && packed.headings().equals(block.headings())
                    && normalized(body, packed.start(), block.end()).length() <= MAX_PACKED_CHARS
                    && tokenCount(normalized(body, packed.start(), block.end())) <= MAX_TOKENS) {
                packed = new Block(packed.start(), block.end(), packed.headings());
            } else {
                if (packed != null) append(result, id, version, source, body, packed.start(), packed.end(), packed.headings());
                packed = block;
            }
        }
        if (packed != null) append(result, id, version, source, body, packed.start(), packed.end(), packed.headings());
        return List.copyOf(result);
    }

    private static List<Block> blocks(String body) {
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
        return blocks;
    }

    /** Reuse the splitter's source boundaries; a long paragraph's overlapping slices are not whole paragraphs. */
    public static boolean completeParagraphRange(String body, KnowledgeChunk chunk) {
        if(body==null||chunk==null||!"unicode_code_point".equals(chunk.positionUnit())||chunk.start()<0
                ||chunk.end()<=chunk.start()||chunk.end()>body.codePointCount(0,body.length()))return false;
        int start=body.offsetByCodePoints(0,chunk.start()),end=body.offsetByCodePoints(0,chunk.end());
        if(!body.substring(start,end).replace('\r',' ').equals(chunk.text())||!sha256(chunk.text()).equals(chunk.contentSha256()))return false;
        boolean starts=false,ends=false;
        for(var block:blocks(body)) {
            int from=block.start(),to=block.end();
            while(from<to&&Character.isWhitespace(body.charAt(from)))from++;
            while(to>from&&Character.isWhitespace(body.charAt(to-1)))to--;
            if(from==start)starts=true;
            if(to==end)ends=true;
        }
        return starts&&ends;
    }

    private void append(List<KnowledgeChunk> result, String id, String version, String source,
            String body, int start, int end, List<String> headings) {
        while (start < end && Character.isWhitespace(body.charAt(start))) start++;
        while (end > start && Character.isWhitespace(body.charAt(end - 1))) end--;
        while (start < end) {
            int stop = prefixEnd(body, start, end, MAX_TOKENS);
            if (stop < end) {
                int boundary = Math.max(body.lastIndexOf('。', stop - 1), body.lastIndexOf('；', stop - 1));
                if (boundary >= start) {
                    int tokens = tokenCount(normalized(body, start, boundary + 1));
                    if (tokens > 120 && tokens <= MAX_TOKENS) stop = boundary + 1;
                }
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
            start = overlapStart(body, start, stop);
        }
    }

    /** Ordinary text treats special-token spellings in documents as literal source content. */
    public static int tokenCount(String text) {
        return ENCODING.countTokensOrdinary(text);
    }

    private static String normalized(String body, int start, int end) {
        return body.substring(start, end).strip().replace('\r', ' ');
    }

    private static int prefixEnd(String body, int start, int end, int budget) {
        if (tokenCount(normalized(body, start, end)) <= budget) return end;
        int low = 1, high = body.codePointCount(start, end), accepted = start;
        // BPE counts need not be monotone: retain only tested, fitting boundaries.
        // This seeks a bounded window, not a mathematically maximal prefix.
        while (low <= high) {
            int middle = low + (high - low) / 2;
            int candidate = body.offsetByCodePoints(start, middle);
            if (tokenCount(normalized(body, start, candidate)) <= budget) {
                accepted = candidate;
                low = middle + 1;
            } else high = middle - 1;
        }
        if (accepted == start) throw new IllegalStateException("Tokenizer cannot fit a source code point");
        return accepted;
    }

    private static int overlapStart(String body, int start, int stop) {
        int low = 1, high = body.codePointCount(start, stop) - 1, accepted = stop;
        while (low <= high) {
            int middle = low + (high - low) / 2;
            int candidate = body.offsetByCodePoints(stop, -middle);
            if (tokenCount(body.substring(candidate, stop).replace('\r', ' ')) <= OVERLAP_TOKENS) {
                accepted = candidate;
                low = middle + 1;
            } else high = middle - 1;
        }
        return accepted;
    }

    public static String sha256(String text) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(text.getBytes(StandardCharsets.UTF_8))); }
        catch (NoSuchAlgorithmException failure) { throw new IllegalStateException(failure); }
    }
}
