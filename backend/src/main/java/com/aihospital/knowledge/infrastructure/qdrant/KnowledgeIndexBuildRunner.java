package com.aihospital.knowledge.infrastructure.qdrant;

import com.aihospital.knowledge.domain.KnowledgeCatalog;
import com.aihospital.knowledge.infrastructure.demo.InMemoryKnowledgeCatalog;
import com.aihospital.shared.model.Models.Evidence;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.List;
import java.util.Map;

/** Opt-in offline command that reuses the same approved corpus and Qdrant upsert adapter as online retrieval. */
@Component
@ConditionalOnProperty(prefix = "ai.knowledge.offline-index", name = "enabled", havingValue = "true")
public class KnowledgeIndexBuildRunner implements ApplicationRunner {
    private static final Logger log = LoggerFactory.getLogger(KnowledgeIndexBuildRunner.class);
    private final KnowledgeCatalog catalog;
    private final InMemoryKnowledgeCatalog local;
    private final QdrantSemanticIndex semantic;
    private final ObjectMapper json;
    @Value("${ai.qdrant-collection:ai_hospital_knowledge_v1}") private String collection;
    @Value("${ai.embedding-model:}") private String embeddingModel;
    @Value("${ai.knowledge.offline-index.manifest-path:target/knowledge-index-manifest.json}") private String manifestPath;

    public KnowledgeIndexBuildRunner(KnowledgeCatalog catalog, InMemoryKnowledgeCatalog local,
                                     QdrantSemanticIndex semantic, ObjectMapper json) {
        this.catalog = catalog;
        this.local = local;
        this.semantic = semantic;
        this.json = json;
    }

    @Override public void run(ApplicationArguments args) throws Exception {
        List<Evidence> corpus = local.approvedCorpus();
        if (corpus.isEmpty()) throw new IllegalStateException("Offline index build refused: approved corpus is empty");

        Map<String, String> result = catalog.syncIndex();
        if (!"true".equals(result.get("success")))
            throw new IllegalStateException("Offline index build failed: " + result.getOrDefault("status", "UNKNOWN"));

        int indexedCount = semantic.indexedCount(corpus);
        if (indexedCount != corpus.size())
            throw new IllegalStateException("Offline index build incomplete: indexed chunk count does not match corpus");

        var manifest = KnowledgeIndexBuildManifest.create(collection, embeddingModel, local.chunkingProfile(),
                corpus, indexedCount, semantic.collectionConfiguration());
        writeManifest(Path.of(manifestPath), manifest, json);
        log.info("Offline knowledge index built collection={} chunks={} corpusSha256={} manifest={}",
                collection, manifest.chunkCount(), manifest.corpusSha256(), Path.of(manifestPath).toAbsolutePath().normalize());
    }

    static void writeManifest(Path output, KnowledgeIndexBuildManifest manifest, ObjectMapper json) throws Exception {
        Path target = output.toAbsolutePath().normalize();
        Path parent = target.getParent();
        if (parent == null || target.getFileName() == null)
            throw new IllegalArgumentException("Manifest path must identify a file");
        Files.createDirectories(parent);
        String prefix = target.getFileName().toString();
        if (prefix.length() < 3) prefix = (prefix + "___").substring(0, 3);
        Path temporary = Files.createTempFile(parent, prefix, ".tmp");
        try {
            json.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), manifest);
            try {
                Files.move(temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

}
