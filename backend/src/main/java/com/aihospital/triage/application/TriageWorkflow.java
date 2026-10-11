package com.aihospital.triage.application;

import com.aihospital.shared.diagnostics.TurnTraceContext;
import com.aihospital.shared.model.Models.TriageResult;
import com.aihospital.triage.domain.NarrationModel;
import com.aihospital.triage.domain.RouteDecision;
import com.aihospital.triage.domain.TriageEngine;
import com.aihospital.triage.domain.TriageProgress;
import com.aihospital.triage.domain.TriageRecords.Conversation;
import java.util.List;
import java.util.function.Consumer;

/** Computes one turn. Identity, concurrent submission and persistence belong to the use-case service. */
public final class TriageWorkflow {
    public sealed interface Outcome permits GuidanceOutcome, AssessmentOutcome {}
    public record GuidanceOutcome(TriageEngine.Guidance guidance) implements Outcome {}
    public record AssessmentOutcome(TriageResult result) implements Outcome {}

    private final TriageEngine engine;
    private final WorkflowRouter router;
    private final AgentWorkflowCoordinator agent;

    public TriageWorkflow(TriageEngine engine, AgentWorkflowCoordinator agent) {
        this.engine = engine;
        this.router = new WorkflowRouter(engine);
        this.agent = agent;
    }

    public Outcome execute(Conversation conversation, String patient, String accumulated, String currentRequest,
            List<NarrationModel.Turn> history, Consumer<TriageProgress> updates, boolean reportProgress,
            TurnTraceContext trace) {
        updates.accept(TriageProgress.SAFETY_CHECK);
        RouteDecision decision = router.decide(accumulated, currentRequest);
        if (decision.route() == RouteDecision.Route.GUIDANCE) {
            trace.route("GUIDANCE");
            var prepared = agent == null ? null : agent.prepare(conversation, patient, accumulated);
            var guidance = prepared != null ? engine.clarificationPrompt(accumulated, history, updates, prepared)
                    : reportProgress ? engine.clarificationPrompt(accumulated, history, updates)
                    : engine.clarificationPrompt(accumulated, history);
            return new GuidanceOutcome(guidance);
        }
        trace.route("ASSESSMENT");
        String sessionId = conversation.session().id();
        var result = reportProgress ? engine.triage(sessionId, accumulated, patient, history, updates)
                : engine.triage(sessionId, accumulated, patient, history);
        return new AssessmentOutcome(result);
    }
}
