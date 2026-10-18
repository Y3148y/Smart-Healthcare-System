package com.aihospital.triage.application;

import com.aihospital.knowledge.domain.KnowledgeCatalog.Retrieval;
import com.aihospital.observation.application.AnswerCallAudit;
import com.aihospital.observation.domain.CallLogStore;
import com.aihospital.shared.model.Models.Evidence;
import com.aihospital.tools.application.HospitalToolExecutor;
import com.aihospital.triage.domain.NarrationModel;
import com.aihospital.triage.domain.TriageEngine;
import com.aihospital.triage.domain.TriageProgress;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

/** Ordinary consultation retrieval, narration and audit. Fixed safety/refusal replies stay outside. */
public final class GuidanceAnswerService {
    private final HospitalToolExecutor tools;
    private final NarrationModel narration;
    private final CallLogStore calls;
    private final com.aihospital.knowledge.domain.KnowledgeCatalog knowledge;

    public GuidanceAnswerService(HospitalToolExecutor tools, NarrationModel narration, CallLogStore calls) {
        this(tools, narration, calls, null);
    }
    public GuidanceAnswerService(HospitalToolExecutor tools, NarrationModel narration, CallLogStore calls,
            com.aihospital.knowledge.domain.KnowledgeCatalog knowledge) {
        this.tools = tools;
        this.narration = narration;
        this.calls = calls;
        this.knowledge = knowledge;
    }

    public TriageEngine.Guidance answer(String text, String query, String departments,
            List<NarrationModel.Turn> history, Consumer<TriageProgress> progress,
            TriageEngine.PreparedEvidence prepared, Function<List<Evidence>, String> fallback) {
        long started = System.nanoTime();
        progress.accept(TriageProgress.KNOWLEDGE_RETRIEVAL);
        var execution = prepared == null ? tools.execute("medical_knowledge_retrieve", Map.of("query", query))
                : new HospitalToolExecutor.Execution(prepared.retrieval(), prepared.trace());
        Retrieval retrieval = execution.data() instanceof Retrieval found ? found
                : new Retrieval(List.of(), false, execution.trace().error());
        List<Evidence> evidence = retrieval.evidence();
        String fallbackText = fallback.apply(evidence);
        progress.accept(TriageProgress.ANSWER_GENERATION);
        var answer = narration.answerWithEvidence(text, "", departments, evidence, fallbackText, history,
                new NarrationModel.ServiceContext(prepared == null ? List.of() : prepared.services(), false), true,
                retrievalStatus(retrieval, execution.trace().success()));
        var checked = AnswerEvidenceFreshness.check(knowledge, evidence, answer);
        answer = checked.answer();
        long elapsed = (System.nanoTime() - started) / 1_000_000;
        AnswerCallAudit.records("预问诊引导", "患者", answer, elapsed, List.of(execution.trace())).forEach(calls::record);
        return new TriageEngine.Guidance(answer.text(), answer.status(), checked.blocked() ? 0 : evidence.size(), 1,
                execution.trace().success() ? 0 : 1, answer.diagnostics());
    }

    public static String retrievalStatus(Retrieval retrieval, boolean success) {
        if (!success || retrieval == null) return "DEPENDENCY_UNAVAILABLE";
        String message = retrieval.message() == null ? "" : retrieval.message();
        if (message.startsWith("DEPENDENCY_BLOCKED") || message.contains("SEARCH_UNAVAILABLE"))
            return "DEPENDENCY_UNAVAILABLE";
        return retrieval.evidence().isEmpty() ? "NO_MATCH" : "MATCHED";
    }
}
