package com.aihospital.knowledge.infrastructure.qdrant;

import com.aihospital.shared.model.Models.Evidence;

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
        String corpusSha256,
        int chunkCount,
        int indexedChunkCount) {

    public static KnowledgeIndexBuildManifest create(
            String collection, String embeddingModel, List<Evidence> corpus, int indexedChunkCount) {
        if (collection == null || collection.isBlank() || embeddingModel == null || embeddingModel.isBlank())
            throw new IllegalArgumentException("Index collection and embedding model are required");
        if (corpus == null || corpus.isEmpty() || corpus.stream().anyMatch(KnowledgeIndexBuildManifest::invalid))
            throw new IllegalArgumentException("Approved corpus must contain complete chunks");
        if (indexedChunkCount < 0 || indexedChunkCount > corpus.size())
            throw new IllegalArgumentException("Indexed chunk count is outside corpus bounds");
        return new KnowledgeIndexBuildManifest(1, collection, embeddingModel,
                fingerprint(corpus), corpus.size(), indexedChunkCount);
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
