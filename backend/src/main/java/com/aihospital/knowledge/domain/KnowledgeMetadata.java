package com.aihospital.knowledge.domain;

import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Set;

/** Operator-supplied provenance and scope. Declared permission is not a legal/clinical certification. */
public record KnowledgeMetadata(int schemaVersion, String language, String contentKind,
        List<Source> sources, List<String> topics, List<String> population,
        List<String> exclusions, List<String> prerequisites, List<String> evidenceUses,
        String permissionStatus, String permissionEvidence) {
    public record Source(String sourceId, String publisher, String url, Instant fetchedAt,
                         String rawSha256) {
        public Source {
            bounded(sourceId, 128); bounded(publisher, 240); bounded(url, 2000);
            URI uri;
            try { uri = URI.create(url); }
            catch (RuntimeException invalid) { throw new IllegalArgumentException("Invalid knowledge source URL"); }
            if (uri.getScheme() == null || !Set.of("https", "http").contains(uri.getScheme()) || uri.getHost() == null
                    || uri.getUserInfo() != null) throw new IllegalArgumentException("Invalid knowledge source URL");
            if (rawSha256 != null && !rawSha256.matches("[a-f0-9]{64}"))
                throw new IllegalArgumentException("Invalid source snapshot hash");
        }
    }

    public KnowledgeMetadata {
        if (schemaVersion != 1) throw new IllegalArgumentException("Unsupported knowledge metadata schema");
        bounded(language, 32);
        if (contentKind == null || !Set.of("source_extract", "reviewed_summary").contains(contentKind))
            throw new IllegalArgumentException("Invalid knowledge content kind");
        if (sources == null || sources.isEmpty() || sources.size() > 8 || sources.stream().anyMatch(s -> s == null))
            throw new IllegalArgumentException("One to eight source references are required");
        sources = List.copyOf(sources);
        if (sources.stream().mapToInt(s -> s.url().length()).sum() + (sources.size() - 1) * 3 > 4000)
            throw new IllegalArgumentException("Combined source references are too long");
        topics = terms(topics); population = terms(population); exclusions = terms(exclusions);
        prerequisites = terms(prerequisites); evidenceUses = terms(evidenceUses);
        if (evidenceUses.isEmpty() || !Set.of("general_information", "direction_reference", "warning_reference")
                .containsAll(evidenceUses)) throw new IllegalArgumentException("Invalid evidence uses");
        if (permissionStatus == null || !Set.of("pending", "permitted", "restricted").contains(permissionStatus))
            throw new IllegalArgumentException("Invalid permission status");
        if (permissionEvidence != null && permissionEvidence.length() > 2000)
            throw new IllegalArgumentException("Permission evidence is too long");
        if ("permitted".equals(permissionStatus)) bounded(permissionEvidence, 2000);
    }

    public boolean mayPublish() { return "permitted".equals(permissionStatus); }
    public String sourceLabel() { return sources.stream().map(Source::url).reduce((a, b) -> a + " | " + b).orElseThrow(); }
    private static List<String> terms(List<String> values) {
        if (values == null) return List.of();
        if (values.size() > 32) throw new IllegalArgumentException("Too many metadata values");
        values.forEach(value -> bounded(value, 240));
        return List.copyOf(values);
    }
    private static void bounded(String value, int maximum) {
        if (value == null || value.isBlank() || value.length() > maximum)
            throw new IllegalArgumentException("Invalid knowledge metadata field");
    }
}
