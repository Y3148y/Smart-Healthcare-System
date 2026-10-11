package com.aihospital.tools.domain;

import java.util.Map;

/** Patient-agent read-only allowlist. Safety and write operations are workflow responsibilities. */
public enum AgentTool {
    MEDICAL_KNOWLEDGE("medical_knowledge_retrieve", "query", 2200),
    DEPARTMENT("department_search", "department", 100),
    SCHEDULE("doctor_schedule_search", "department", 100);

    private final String code, parameter;
    private final int maxLength;
    AgentTool(String code, String parameter, int maxLength) {
        this.code = code; this.parameter = parameter; this.maxLength = maxLength;
    }
    public String code() { return code; }
    public String parameter() { return parameter; }
    public int maxLength() { return maxLength; }
    public Map<String, Object> parameterSchema() {
        return Map.of("type", "object", "additionalProperties", false, "required", java.util.List.of(parameter),
                "properties", Map.of(parameter, Map.of("type", "string", "minLength", 1, "maxLength", maxLength)));
    }
    public static AgentTool fromCode(String code) {
        for (var tool : values()) if (tool.code.equals(code)) return tool;
        throw new IllegalArgumentException("TOOL_NOT_ALLOWED");
    }
}
