package com.aihospital.observation.application;

import com.aihospital.shared.model.Models.CallLog;
import com.aihospital.shared.model.Models.ToolTrace;
import com.aihospital.triage.domain.NarrationModel;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/** Maps measured answer diagnostics to existing audit storage; never estimates provider usage. */
public final class AnswerCallAudit {
    private AnswerCallAudit() {}
    private static final Set<String> COMPLETED = Set.of("LIVE", "LIVE_UNGROUNDED", "DEMO", "DEMO_UNGROUNDED", "SAFETY_RULE");

    public static List<CallLog> records(String purpose, String actor, NarrationModel.Answer answer,
                                        long elapsedMs, List<ToolTrace> tools) {
        var diagnostics = answer.diagnostics();
        String id = UUID.randomUUID().toString();
        if (diagnostics != null && diagnostics.traceId() != null) {
            try { id = UUID.fromString(diagnostics.traceId()).toString(); }
            catch (IllegalArgumentException ignored) { /* Do not publish arbitrary text as a trace identifier. */ }
        }
        var generation = diagnostics == null ? null : diagnostics.generation();
        var review = diagnostics == null ? null : diagnostics.supportReview();
        var trace = tools == null ? List.<ToolTrace>of() : List.copyOf(tools);
        boolean success = COMPLETED.contains(answer.status()) && trace.stream().allMatch(ToolTrace::success)
                && (diagnostics == null || diagnostics.failure() == null)
                && (review == null || "PASSED".equals(review.status()));
        String model = answer.modelName() == null || answer.modelName().isBlank() ? answer.status()
                : answer.modelName() + "/" + answer.status();
        var call = new CallLog(id, LocalDateTime.now(), purpose, actor, model,
                measured(generation == null ? null : generation.inputTokens()),
                measured(generation == null ? null : generation.outputTokens()), Math.max(0, elapsedMs), success, trace, id);
        if (review == null) return List.of(call);
        // Separate usage: do not conceal an unknown review count in a partially known aggregate.
        var reviewCall = new CallLog(id + "-review", call.time(), "回答依据核对", actor, answer.modelName(),
                measured(review.inputTokens()), measured(review.outputTokens()),
                review.elapsedMs() == null ? 0 : Math.max(0, review.elapsedMs()),
                "PASSED".equals(review.status()), List.of(), id);
        return List.of(call, reviewCall);
    }

    // Existing schema uses zero for unknown; the technical API projects it to null.
    private static int measured(Integer value) { return value == null || value < 0 ? 0 : value; }
}
