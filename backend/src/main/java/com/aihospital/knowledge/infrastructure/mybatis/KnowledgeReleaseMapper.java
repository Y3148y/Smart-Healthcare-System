package com.aihospital.knowledge.infrastructure.mybatis;

import org.apache.ibatis.annotations.*;
import java.util.List;
import java.util.Map;

@Mapper
public interface KnowledgeReleaseMapper {
    @Select("SELECT id FROM knowledge_release WHERE build_status='READY' ORDER BY created_at DESC,id DESC LIMIT 3")
    List<String> latestReady();
    @Select("SELECT active_release_id,pointer_version FROM knowledge_release_pointer WHERE id=1")
    Map<String,Object> pointer();
    @Select("SELECT active_release_id,pointer_version FROM knowledge_release_pointer WHERE id=1 FOR UPDATE")
    Map<String,Object> lockPointer();
    @Select("SELECT * FROM knowledge_release WHERE id=#{id}")
    Map<String,Object> find(@Param("id") String id);
    @Select("SELECT id,source_revision,base_version,snapshot_sha256,build_status,created_at FROM knowledge_release ORDER BY created_at DESC,id DESC LIMIT 100")
    List<Map<String,Object>> list();
    @Insert("INSERT INTO knowledge_release(id,source_revision,base_version,snapshot_json,snapshot_sha256,build_status,created_at) VALUES(#{id},#{revision},#{base},#{snapshot},#{hash},'BUILDING',CURRENT_TIMESTAMP)")
    int insert(@Param("id") String id,@Param("revision") long revision,@Param("base") long base,@Param("snapshot") String snapshot,@Param("hash") String hash);
    @Update("UPDATE knowledge_release SET build_status=#{status},build_receipt=#{receipt} WHERE id=#{id} AND build_status='BUILDING'")
    int finish(@Param("id") String id,@Param("status") String status,@Param("receipt") String receipt);
    @Update("UPDATE knowledge_release_pointer SET active_release_id=#{id},pointer_version=pointer_version+1 WHERE id=1 AND pointer_version=#{expected}")
    int activate(@Param("id") String id,@Param("expected") long expected);
    @Delete("DELETE FROM knowledge_release WHERE id=#{id} AND created_at<CURRENT_TIMESTAMP - INTERVAL '7' DAY AND id NOT IN (SELECT active_release_id FROM knowledge_release_pointer WHERE active_release_id IS NOT NULL)")
    int deleteExpired(@Param("id") String id);
}
