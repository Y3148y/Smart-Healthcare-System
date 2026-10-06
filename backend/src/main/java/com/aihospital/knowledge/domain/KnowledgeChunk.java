package com.aihospital.knowledge.domain;

import java.util.List;

/** Offsets refer to the persisted source body's Unicode code points, end exclusive. */
public record KnowledgeChunk(String chunkId, String documentId, String documentVersion,
        int ordinal, List<String> sectionPath, int start, int end, String positionUnit,
        String text, String contentSha256, String source, String chunkingVersion) {
    public KnowledgeChunk { sectionPath = List.copyOf(sectionPath); }
}
