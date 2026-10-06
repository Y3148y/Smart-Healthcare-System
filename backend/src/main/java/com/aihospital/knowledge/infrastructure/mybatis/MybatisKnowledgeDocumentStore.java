package com.aihospital.knowledge.infrastructure.mybatis;

import com.aihospital.knowledge.domain.KnowledgeDocumentStore;
import com.aihospital.knowledge.domain.StoredKnowledgeDocument;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Repository
public class MybatisKnowledgeDocumentStore implements KnowledgeDocumentStore {
    private final KnowledgeDocumentMapper mapper;

    public MybatisKnowledgeDocumentStore(KnowledgeDocumentMapper mapper) { this.mapper = mapper; }

    @Override @Transactional
    public List<StoredKnowledgeDocument> loadOrSeed(List<StoredKnowledgeDocument> bundledDocuments) {
        if (mapper.count() == 0) {
            for (StoredKnowledgeDocument document : bundledDocuments) insert(document);
        }
        return mapper.findAll().stream().map(MybatisKnowledgeDocumentStore::convert).toList();
    }

    @Override public StoredKnowledgeDocument find(String id) {
        Map<String, Object> row = mapper.find(id);
        return row == null ? null : convert(row);
    }

    @Override @Transactional
    public void insert(StoredKnowledgeDocument document) {
        if (mapper.insert(document.id(), document.title(), document.body(), document.source(),
                document.status(), document.chunkCount(), document.updatedAt()) != 1)
            throw new IllegalStateException("Knowledge document was not persisted");
    }

    @Override @Transactional
    public StoredKnowledgeDocument approve(String id, int chunkCount, LocalDateTime updatedAt) {
        if (mapper.approvePending(id, chunkCount, updatedAt) == 1) return find(id);
        StoredKnowledgeDocument existing = find(id);
        if (existing == null) throw new IllegalArgumentException("知识资料不存在");
        if ("READY".equals(existing.status())) return existing;
        throw new IllegalStateException("Knowledge document approval state changed concurrently");
    }

    @Override @Transactional
    public void refreshChunkCount(String id, int chunkCount) {
        if (mapper.refreshChunkCount(id, chunkCount) != 1)
            throw new IllegalArgumentException("知识资料不存在");
    }

    private static StoredKnowledgeDocument convert(Map<String, Object> row) {
        Object timestamp = row.get("updated_at");
        LocalDateTime updatedAt = timestamp instanceof LocalDateTime localDateTime ? localDateTime
                : timestamp instanceof Timestamp sqlTimestamp ? sqlTimestamp.toLocalDateTime()
                : LocalDateTime.parse(String.valueOf(timestamp));
        return new StoredKnowledgeDocument(String.valueOf(row.get("id")), String.valueOf(row.get("title")),
                String.valueOf(row.get("body")), String.valueOf(row.get("source")), String.valueOf(row.get("status")),
                ((Number) row.get("chunk_count")).intValue(), updatedAt);
    }
}
