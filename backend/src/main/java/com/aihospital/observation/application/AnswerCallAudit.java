package com.aihospital.observation.application;

import com.aihospital.shared.model.Models.CallLog;
import com.aihospital.shared.model.Models.ToolTrace;
import com.aihospital.shared.model.Models.TokenUsage;
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
                measured(generation == null ? null : generation.outputTokens()), Math.max(0, elapsedMs), success, trace, id,
                generationUsage(answer));
        if (review == null) return List.of(call);
        // Separate usage: do not conceal an unknown review count in a partially known aggregate.
        var reviewCall = new CallLog(id + "-review", call.time(), "回答依据核对", actor, answer.modelName(),
                measured(review.inputTokens()), measured(review.outputTokens()),
                review.elapsedMs() == null ? 0 : Math.max(0, review.elapsedMs()),
                "PASSED".equals(review.status()), List.of(), id,
                review.failure()!=null&&"ContextAssembler".equals(review.failure().causeType())?TokenUsage.local()
                        :TokenUsage.reported(review.inputTokens(),review.outputTokens()));
        return List.of(call, reviewCall);
    }

    // Retain the old integer columns for old writers; explicit usage metadata is authoritative.
    private static int measured(Integer value) { return value == null || value < 0 ? 0 : value; }
    private static TokenUsage generationUsage(NarrationModel.Answer answer) {
        var generation=answer.diagnostics()==null?null:answer.diagnostics().generation();
        if(generation!=null){
            if("NOT_RUN".equals(generation.phase())||"PROMPT_PREPARATION".equals(generation.phase()))return TokenUsage.local();
            return TokenUsage.reported(generation.inputTokens(),generation.outputTokens());
        }
        return Set.of("SAFETY_RULE","DEMO","DEMO_UNGROUNDED").contains(answer.status())
                ?TokenUsage.local():TokenUsage.reported(null,null);
    }
}
