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

    @Insert("INSERT INTO agent_call_trace(call_id,trace_id) VALUES(#{id},#{traceId})")
    int insertTrace(@Param("id") String id, @Param("traceId") String traceId);
    @Insert("INSERT INTO agent_call_usage(call_id,usage_json) VALUES(#{id},#{usage})")
    int insertUsage(@Param("id") String id,@Param("usage") String usage);

    @Select("SELECT l.id,l.called_at,l.purpose,l.actor,l.model,l.input_tokens,l.output_tokens,l.elapsed_ms,l.success,l.tools_json,t.trace_id,u.usage_json "
            + "FROM agent_call_log l LEFT JOIN agent_call_trace t ON t.call_id=l.id LEFT JOIN agent_call_usage u ON u.call_id=l.id ORDER BY l.called_at DESC LIMIT 200")
    List<Map<String,Object>> recent();
    @Select("SELECT l.id,l.called_at,l.purpose,l.actor,l.model,l.input_tokens,l.output_tokens,l.elapsed_ms,l.success,l.tools_json,t.trace_id,u.usage_json "
            + "FROM agent_call_log l JOIN agent_call_trace t ON t.call_id=l.id LEFT JOIN agent_call_usage u ON u.call_id=l.id WHERE t.trace_id=#{traceId} ORDER BY l.called_at DESC LIMIT 200")
    List<Map<String,Object>> byTrace(@Param("traceId") String traceId);
}
