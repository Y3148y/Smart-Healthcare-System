package com.aihospital.knowledge.infrastructure.mybatis;

import com.aihospital.knowledge.domain.KnowledgeDocumentStore;
import com.aihospital.knowledge.domain.StoredKnowledgeDocument;
import com.aihospital.knowledge.domain.KnowledgeMetadata;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Repository
public class MybatisKnowledgeDocumentStore implements KnowledgeDocumentStore {
    private final KnowledgeDocumentMapper mapper;
    private final ObjectMapper json;

    public MybatisKnowledgeDocumentStore(KnowledgeDocumentMapper mapper, ObjectMapper json) {
        this.mapper = mapper; this.json = json;
    }

    @Override @Transactional
    public List<StoredKnowledgeDocument> loadOrSeed(List<StoredKnowledgeDocument> bundledDocuments) {
        if (mapper.count() == 0) {
            for (StoredKnowledgeDocument document : bundledDocuments) insert(document);
        }
        return mapper.findAll().stream().map(this::convert).toList();
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
        if (document.metadata() != null) {
            try {
                if (mapper.insertMetadata(document.id(), json.writeValueAsString(document.metadata())) != 1)
                    throw new IllegalStateException("Knowledge metadata was not persisted");
            } catch (JsonProcessingException invalid) {
                throw new IllegalStateException("Knowledge metadata cannot be serialized", invalid);
            }
        }
    }

    @Override @Transactional
    public StoredKnowledgeDocument approve(String id, int chunkCount, LocalDateTime updatedAt) {
        if (mapper.lockDocument(id) == null) throw new IllegalArgumentException("知识资料不存在");
        StoredKnowledgeDocument candidate = find(id);
        if (candidate == null) throw new IllegalArgumentException("知识资料不存在");
        if (candidate.metadata() != null && !candidate.metadata().mayPublish())
            throw new IllegalArgumentException("Knowledge usage permission must be resolved before approval");
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

    @Override @Transactional
    public StoredKnowledgeDocument withdraw(String id, LocalDateTime updatedAt) {
        if (mapper.lockDocument(id) == null) throw new IllegalArgumentException("知识资料不存在");
        mapper.withdrawReady(id, updatedAt);
        StoredKnowledgeDocument existing = find(id);
        if (existing == null || !"PENDING_REVIEW".equals(existing.status()))
            throw new IllegalStateException("Knowledge withdrawal state changed concurrently");
        return existing;
    }

    @Override @Transactional
    public StoredKnowledgeDocument updatePendingMetadata(String id, KnowledgeMetadata supplied, LocalDateTime updatedAt) {
        if (supplied == null) throw new IllegalArgumentException("Knowledge metadata is required");
        if (mapper.updatePendingSource(id, supplied.sourceLabel(), updatedAt) != 1)
            throw new IllegalArgumentException("Only pending knowledge metadata may be corrected");
        try {
            String encoded = json.writeValueAsString(supplied);
            if (mapper.updateMetadata(id, encoded) == 0 && mapper.insertMetadata(id, encoded) != 1)
                throw new IllegalStateException("Knowledge metadata correction was not persisted");
        } catch (JsonProcessingException invalid) {
            throw new IllegalStateException("Knowledge metadata cannot be serialized", invalid);
        }
        return find(id);
    }

    private StoredKnowledgeDocument convert(Map<String, Object> row) {
        Object timestamp = row.get("updated_at");
        LocalDateTime updatedAt = timestamp instanceof LocalDateTime localDateTime ? localDateTime
                : timestamp instanceof Timestamp sqlTimestamp ? sqlTimestamp.toLocalDateTime()
                : LocalDateTime.parse(String.valueOf(timestamp));
        return new StoredKnowledgeDocument(String.valueOf(row.get("id")), String.valueOf(row.get("title")),
                String.valueOf(row.get("body")), String.valueOf(row.get("source")), String.valueOf(row.get("status")),
                ((Number) row.get("chunk_count")).intValue(), updatedAt, metadata(row.get("metadata_json")));
    }
    private KnowledgeMetadata metadata(Object value) {
        if (value == null) return null;
        try { return json.readValue(String.valueOf(value), KnowledgeMetadata.class); }
        catch (JsonProcessingException invalid) { throw new IllegalStateException("Stored knowledge metadata is invalid", invalid); }
    }
}
