package com.aihospital.triage.infrastructure.mybatis;

import org.apache.ibatis.annotations.*;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Mapper
public interface TriageTurnMapper {
    @Select("SELECT CURRENT_TIMESTAMP") LocalDateTime now();
    @Select("SELECT * FROM triage_turn WHERE session_id=#{session} AND request_id=#{request}")
    Map<String,Object> find(@Param("session") String session, @Param("request") String request);
    @Select("SELECT t.* FROM triage_turn t JOIN triage_message_order o ON o.message_id=t.user_message_id WHERE t.session_id=#{session} ORDER BY o.sequence_number DESC LIMIT 1")
    Map<String,Object> latest(String session);
    @Select("SELECT * FROM triage_turn WHERE session_id=#{session} AND status='RUNNING'")
    List<Map<String,Object>> running(String session);
    @Insert("INSERT INTO triage_turn(id,session_id,request_id,content_hash,status,user_message_id,started_at,expires_at) VALUES(#{id},#{session},#{request},#{hash},'RUNNING',#{message},#{now},#{expires})")
    int insert(@Param("id") String id,@Param("session") String session,@Param("request") String request,
               @Param("hash") String hash,@Param("message") String message,@Param("now") LocalDateTime now,
               @Param("expires") LocalDateTime expires);
    @Update("UPDATE triage_turn SET status=#{status},assistant_message_id=#{assistant},finished_at=#{now},failure_code=#{failure} WHERE id=#{id} AND status='RUNNING'")
    int finish(@Param("id") String id,@Param("status") String status,@Param("assistant") String assistant,
               @Param("now") LocalDateTime now,@Param("failure") String failure);
}
