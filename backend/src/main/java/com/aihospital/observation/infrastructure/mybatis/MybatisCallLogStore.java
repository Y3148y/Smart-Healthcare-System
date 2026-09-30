package com.aihospital.observation.infrastructure.mybatis;

import com.aihospital.observation.domain.CallLogStore;
import com.aihospital.shared.model.Models.CallLog;
import com.aihospital.shared.model.Models.ToolTrace;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Repository;

import java.util.List;

import static com.aihospital.shared.infrastructure.mybatis.RowValues.*;

@Repository
public class MybatisCallLogStore implements CallLogStore {
    private final CallLogMapper mapper;
    private final ObjectMapper json;
    public MybatisCallLogStore(CallLogMapper mapper, ObjectMapper json) { this.mapper = mapper; this.json = json; }

    @Override public List<CallLog> calls() {
        return mapper.recent().stream().map(row -> new CallLog(string(row,"id"), dateTime(row,"called_at"),
                string(row,"purpose"), string(row,"actor"), string(row,"model"), integer(row,"input_tokens"),
                integer(row,"output_tokens"), ((Number)row.get("elapsed_ms")).longValue(),
                Boolean.TRUE.equals(row.get("success")) || Integer.valueOf(1).equals(row.get("success")),
                tools(string(row,"tools_json")))).toList();
    }

    @Override public void record(CallLog call) {
        try {
            mapper.insert(call.id(), call.time(), call.purpose(), call.user(), call.model(), call.inputTokens(),
                    call.outputTokens(), call.elapsedMs(), call.success(), json.writeValueAsString(call.tools()));
        } catch (JsonProcessingException ex) { throw new IllegalStateException("无法保存 Agent 审计日志", ex); }
    }

    private List<ToolTrace> tools(String value) {
        try { return json.readValue(value, new TypeReference<List<ToolTrace>>() {}); }
        catch (JsonProcessingException ex) { throw new IllegalStateException("Agent 审计日志损坏", ex); }
    }
}
