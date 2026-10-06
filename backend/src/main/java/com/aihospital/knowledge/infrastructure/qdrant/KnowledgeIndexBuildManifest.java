package com.aihospital.knowledge.infrastructure.qdrant;

import com.aihospital.shared.model.Models.Evidence;
import com.aihospital.knowledge.domain.KnowledgeChunk;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;

/** Deterministic, non-sensitive fingerprint of the approved chunks used for a Qdrant build. */
public record KnowledgeIndexBuildManifest(
        int schemaVersion,
        String collection,
        String embeddingModel,
        String chunkingProfile,
        String corpusSha256,
        int chunkCount,
        int indexedChunkCount,
        int collectionPointCount,
        String collectionStatus,
        int vectorSize,
        String distance,
        int hnswM,
        int hnswEfConstruct,
        int fullScanThresholdKb,
        List<ChunkEntry> chunks) {

    public KnowledgeIndexBuildManifest { chunks = List.copyOf(chunks); }

    public record ChunkEntry(String chunkId, String documentId, String documentVersion, int ordinal,
            List<String> sectionPath, int start, int end, String positionUnit, String contentSha256,
            String chunkingVersion) {
        public ChunkEntry { sectionPath = List.copyOf(sectionPath); }
        static ChunkEntry from(KnowledgeChunk chunk) {
            return new ChunkEntry(chunk.chunkId(), chunk.documentId(), chunk.documentVersion(), chunk.ordinal(),
                    chunk.sectionPath(), chunk.start(), chunk.end(), chunk.positionUnit(),
                    chunk.contentSha256(), chunk.chunkingVersion());
        }
    }

    public static KnowledgeIndexBuildManifest createWithProvenance(
            String collection, String embeddingModel, String chunkingProfile, List<Evidence> corpus,
            int indexedChunkCount, QdrantSemanticIndex.CollectionConfiguration configuration,
            List<KnowledgeChunk> sourceChunks) {
        var base = create(collection, embeddingModel, chunkingProfile, corpus, indexedChunkCount, configuration);
        if (sourceChunks == null || sourceChunks.size() != corpus.size()
                || sourceChunks.stream().anyMatch(chunk -> chunk == null || chunk.chunkId() == null
                    || chunk.documentVersion() == null || chunk.contentSha256() == null || chunk.text() == null
                    || chunk.source() == null))
            throw new IllegalArgumentException("Index provenance is incomplete");
        if (sourceChunks.stream().map(KnowledgeChunk::chunkId).distinct().count() != sourceChunks.size())
            throw new IllegalArgumentException("Index provenance has duplicate chunk ids");
        record SourceText(String source, String text) {}
        var expected = corpus.stream().collect(java.util.stream.Collectors.groupingBy(
                evidence -> new SourceText(evidence.source(), evidence.excerpt()), java.util.stream.Collectors.counting()));
        var actual = sourceChunks.stream().collect(java.util.stream.Collectors.groupingBy(
                chunk -> new SourceText(chunk.source(), chunk.text()), java.util.stream.Collectors.counting()));
        if (!expected.equals(actual)) throw new IllegalArgumentException("Index provenance does not match the approved corpus");
        var entries = sourceChunks.stream().map(ChunkEntry::from)
                .sorted(java.util.Comparator.comparing(ChunkEntry::documentId).thenComparingInt(ChunkEntry::ordinal)).toList();
        return new KnowledgeIndexBuildManifest(3, base.collection(), base.embeddingModel(), base.chunkingProfile(),
                base.corpusSha256(), base.chunkCount(), base.indexedChunkCount(), base.collectionPointCount(),
                base.collectionStatus(), base.vectorSize(), base.distance(), base.hnswM(), base.hnswEfConstruct(),
                base.fullScanThresholdKb(), entries);
    }

    public static KnowledgeIndexBuildManifest create(
            String collection, String embeddingModel, String chunkingProfile, List<Evidence> corpus,
            int indexedChunkCount, QdrantSemanticIndex.CollectionConfiguration configuration) {
        if (collection == null || collection.isBlank() || embeddingModel == null || embeddingModel.isBlank()
                || chunkingProfile == null || chunkingProfile.isBlank() || configuration == null)
            throw new IllegalArgumentException("Index collection, embedding model, chunk profile, and collection details are required");
        if (corpus == null || corpus.isEmpty() || corpus.stream().anyMatch(KnowledgeIndexBuildManifest::invalid))
            throw new IllegalArgumentException("Approved corpus must contain complete chunks");
        if (indexedChunkCount < 0 || indexedChunkCount > corpus.size())
            throw new IllegalArgumentException("Indexed chunk count is outside corpus bounds");
        if (configuration.status() == null || configuration.status().isBlank()
                || configuration.pointCount() < indexedChunkCount || configuration.vectorSize() < 1
                || configuration.distance() == null || configuration.distance().isBlank() || configuration.hnswM() < 2
                || configuration.hnswEfConstruct() < 1 || configuration.fullScanThresholdKb() < 0)
            throw new IllegalArgumentException("Qdrant collection configuration is inconsistent with the index build");
        return new KnowledgeIndexBuildManifest(2, collection, embeddingModel, chunkingProfile,
                fingerprint(corpus), corpus.size(), indexedChunkCount, configuration.pointCount(), configuration.status(),
                configuration.vectorSize(), configuration.distance(), configuration.hnswM(),
                configuration.hnswEfConstruct(), configuration.fullScanThresholdKb(), List.of());
    }

    private static boolean invalid(Evidence item) {
        return item == null || item.title() == null || item.source() == null || item.excerpt() == null;
    }

    private static String fingerprint(List<Evidence> corpus) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            var chunks = corpus.stream()
                    .sorted(java.util.Comparator.comparing(Evidence::source)
                            .thenComparing(Evidence::title).thenComparing(Evidence::excerpt))
                    .toList();
            for (Evidence item : chunks) {
                update(digest, item.source());
                update(digest, item.title());
                update(digest, item.excerpt());
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static void update(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update((byte) (bytes.length >>> 24));
        digest.update((byte) (bytes.length >>> 16));
        digest.update((byte) (bytes.length >>> 8));
        digest.update((byte) bytes.length);
        digest.update(bytes);
    }
}
