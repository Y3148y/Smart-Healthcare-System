package com.aihospital.observation.infrastructure.mybatis;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Mapper
public interface CallLogMapper {
    @Insert("INSERT INTO agent_call_log(id,called_at,purpose,actor,model,input_tokens,output_tokens,elapsed_ms,success,tools_json) "
            + "VALUES(#{id},#{calledAt},#{purpose},#{actor},#{model},#{inputTokens},#{outputTokens},#{elapsedMs},#{success},#{toolsJson})")
    int insert(@Param("id") String id, @Param("calledAt") LocalDateTime calledAt,
               @Param("purpose") String purpose, @Param("actor") String actor,
               @Param("model") String model, @Param("inputTokens") int inputTokens,
               @Param("outputTokens") int outputTokens, @Param("elapsedMs") long elapsedMs,
               @Param("success") boolean success, @Param("toolsJson") String toolsJson);

    @Select("SELECT id,called_at,purpose,actor,model,input_tokens,output_tokens,elapsed_ms,success,tools_json "
            + "FROM agent_call_log ORDER BY called_at DESC LIMIT 200")
    List<Map<String,Object>> recent();
}
