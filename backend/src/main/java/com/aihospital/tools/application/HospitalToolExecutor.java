package com.aihospital.tools.application;

import com.aihospital.catalog.domain.DoctorDirectory;
import com.aihospital.knowledge.domain.KnowledgeCatalog;
import com.aihospital.shared.model.Models.Doctor;
import com.aihospital.shared.model.Models.Tool;
import com.aihospital.shared.model.Models.ToolTrace;
import com.aihospital.tools.domain.ToolRegistry;
import com.aihospital.observation.domain.CallLogStore;
import com.aihospital.shared.model.Models.CallLog;
import com.aihospital.triage.domain.TriageSafetyPolicy;
import org.springframework.stereotype.Service;
import org.springframework.beans.factory.annotation.Value;

import java.util.List;
import java.util.Map;
import java.time.LocalDateTime;
import java.util.UUID;

/** One execution boundary for both admin trial runs and the triage agent. */
@Service
public class HospitalToolExecutor {
    public record Execution(Object data, ToolTrace trace) {}
    private final ToolRegistry registry;
    private final DoctorDirectory doctors;
    private final KnowledgeCatalog knowledge;
    private final TriageSafetyPolicy safety;
    private final CallLogStore calls;
    @Value("${ai.retrieval.min-score:0.28}") private double retrievalMinScore = 0.28;

    public HospitalToolExecutor(ToolRegistry registry, DoctorDirectory doctors,
                                KnowledgeCatalog knowledge, TriageSafetyPolicy safety, CallLogStore calls) {
        this.registry = registry;
        this.doctors = doctors;
        this.knowledge = knowledge;
        this.safety = safety;
        this.calls = calls;
    }

    public Execution execute(String code, Map<String, String> arguments) {
        long started = System.nanoTime();
        String input = code + "：参数内容已从审计摘要脱敏";
        try {
            Tool tool = registry.tools().stream().filter(item -> item.code().equals(code)).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("工具不存在"));
            if (!tool.enabled()) throw new IllegalStateException("工具已停用");
            Map<String, String> args = arguments == null ? Map.of() : arguments;
            Object result = switch (code) {
                case "symptom_tag_search" -> safety.assess(required(args, "query"));
                case "medical_knowledge_retrieve" -> knowledge.retrieve(required(args, "query"), 3, retrievalMinScore);
                case "department_search" -> {
                    String department = required(args, "department");
                    yield Map.of("department", department, "exists", !doctors.doctors(department).isEmpty());
                }
                case "doctor_schedule_search" -> {
                    String department = required(args, "department");
                    yield doctors.doctors(department).stream().filter(doctor -> doctor.remaining() > 0).toList();
                }
                default -> throw new IllegalArgumentException("工具类型未实现");
            };
            String outcome = summarize(code, result);
            Execution execution = new Execution(result, new ToolTrace(code, tool.name(), input, outcome, elapsed(started), true, ""));
            record(execution);
            return execution;
        } catch (RuntimeException ex) {
            Execution execution = new Execution(null, new ToolTrace(code, code, input, "工具执行失败", elapsed(started), false,
                    ex.getClass().getSimpleName() + ": " + ex.getMessage()));
            record(execution);
            return execution;
        }
    }

    private void record(Execution execution) {
        calls.record(new CallLog(UUID.randomUUID().toString(), LocalDateTime.now(), execution.trace().tool(),
                "工具调用", "LOCAL_TOOL", 0, 0, execution.trace().elapsedMs(), execution.trace().success(),
                List.of(execution.trace())));
    }

    private String required(Map<String, String> arguments, String key) {
        String value = arguments.get(key);
        if (value == null || value.isBlank() || value.length() > 2200)
            throw new IllegalArgumentException("参数 " + key + " 不能为空且不得超过 2200 字");
        return value.trim();
    }

    private String summarize(String code, Object result) {
        return switch (code) {
            case "symptom_tag_search" -> "已完成症状安全规则检查";
            case "medical_knowledge_retrieve" -> ((KnowledgeCatalog.Retrieval) result).message();
            case "department_search" -> Boolean.TRUE.equals(((Map<?, ?>) result).get("exists")) ? "科室存在" : "科室不存在";
            case "doctor_schedule_search" -> "返回 " + ((List<?>) result).size() + " 个模拟可用号源";
            default -> "工具执行完成";
        };
    }

    private long elapsed(long started) { return Math.max(1, (System.nanoTime() - started) / 1_000_000); }
}
