package com.aihospital.observation.api;

import com.aihospital.shared.model.Models.CallLog;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;

/** Allowlisted administrator projection, not a serialization of the sensitive persisted log. */
public record TechnicalCallView(String id, LocalDateTime time, String purpose, String model,
        Integer inputTokens, Integer outputTokens, long elapsedMs, boolean success, List<ToolStatus> tools) {
    private static final Set<String> PURPOSES = Set.of("合规拒答", "预问诊追问", "预问诊引导", "结构化分诊决策", "分诊Agent", "分诊工作流");
    private static final Set<String> TOOLS = Set.of("symptom_tag_search", "medical_knowledge_retrieve", "department_search", "doctor_schedule_search");
    public record ToolStatus(String tool, long elapsedMs, boolean success, boolean errorPresent) {}

    public static TechnicalCallView from(CallLog call) {
        var tools = call.tools() == null ? List.<ToolStatus>of() : call.tools().stream()
                .map(t -> new ToolStatus(t.tool() != null && TOOLS.contains(t.tool()) ? t.tool() : "OTHER", t.elapsedMs(), t.success(),
                        t.error() != null && !t.error().isBlank())).toList();
        String purpose = call.purpose() != null && (PURPOSES.contains(call.purpose()) || TOOLS.contains(call.purpose())) ? call.purpose() : "其他调用";
        if ("分诊Agent".equals(purpose)) purpose = "分诊工作流";
        return new TechnicalCallView(call.id(), call.time(), purpose, call.model(),
                known(call.inputTokens()), known(call.outputTokens()), call.elapsedMs(), call.success(), tools);
    }
    // Legacy zero is a placeholder: do not present it as measured usage.
    private static Integer known(int value) { return value > 0 ? value : null; }
}
