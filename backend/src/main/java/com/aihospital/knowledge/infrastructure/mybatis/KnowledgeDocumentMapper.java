package com.aihospital.knowledge.infrastructure.mybatis;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Mapper
public interface KnowledgeDocumentMapper {
    @Select("SELECT d.*,m.metadata_json FROM knowledge_document d LEFT JOIN knowledge_document_metadata m ON m.document_id=d.id ORDER BY d.updated_at DESC,d.id")
    List<Map<String, Object>> findAll();

    @Select("SELECT d.*,m.metadata_json FROM knowledge_document d LEFT JOIN knowledge_document_metadata m ON m.document_id=d.id WHERE d.id=#{id}")
    Map<String, Object> find(@Param("id") String id);
    @Select("SELECT id FROM knowledge_document WHERE id=#{id} FOR UPDATE")
    String lockDocument(@Param("id") String id);

    @Select("SELECT COUNT(*) FROM knowledge_document")
    long count();
    @Insert("INSERT INTO knowledge_document_metadata(document_id,metadata_json) VALUES(#{id},#{metadata})")
    int insertMetadata(@Param("id") String id, @Param("metadata") String metadata);

    @Update("UPDATE knowledge_document_metadata SET metadata_json=#{metadata} WHERE document_id=#{id}")
    int updateMetadata(@Param("id") String id, @Param("metadata") String metadata);

    @Update("UPDATE knowledge_document SET source=#{source},updated_at=#{updatedAt} WHERE id=#{id} AND status='PENDING_REVIEW'")
    int updatePendingSource(@Param("id") String id, @Param("source") String source, @Param("updatedAt") LocalDateTime updatedAt);

    @Insert("INSERT INTO knowledge_document(id,title,body,source,status,chunk_count,created_at,updated_at) VALUES(#{id},#{title},#{body},#{source},#{status},#{chunkCount},#{updatedAt},#{updatedAt})")
    int insert(@Param("id") String id, @Param("title") String title, @Param("body") String body,
               @Param("source") String source, @Param("status") String status,
               @Param("chunkCount") int chunkCount, @Param("updatedAt") LocalDateTime updatedAt);

    @Update("UPDATE knowledge_document SET status='READY',chunk_count=#{chunkCount},updated_at=#{updatedAt} WHERE id=#{id} AND status='PENDING_REVIEW'")
    int approvePending(@Param("id") String id, @Param("chunkCount") int chunkCount,
                       @Param("updatedAt") LocalDateTime updatedAt);

    @Update("UPDATE knowledge_document SET chunk_count=#{chunkCount} WHERE id=#{id}")
    int refreshChunkCount(@Param("id") String id, @Param("chunkCount") int chunkCount);

    @Update("UPDATE knowledge_document SET status='PENDING_REVIEW',updated_at=#{updatedAt} WHERE id=#{id} AND status='READY'")
    int withdrawReady(@Param("id") String id, @Param("updatedAt") LocalDateTime updatedAt);
}
