package com.aihospital.triage.application;

import com.aihospital.triage.domain.RouteDecision;
import com.aihospital.triage.domain.TriageEngine;
import static com.aihospital.triage.domain.RouteDecision.Route.*;
import static com.aihospital.triage.domain.RouteDecision.Reason.*;

/** Preserves the existing safety-first branch ordering without adding clinical rules. */
public final class WorkflowRouter {
    private final TriageEngine engine;

    public WorkflowRouter(TriageEngine engine) {
        this.engine = engine;
    }

    public RouteDecision decide(String accumulated, String currentRequest) {
        if (engine.requiresImmediateCare(accumulated)) return new RouteDecision(ASSESSMENT, IMMEDIATE_CARE);
        if (engine.requiresReview(accumulated)) return new RouteDecision(ASSESSMENT, HUMAN_REVIEW);
        if (engine.needsClarification(accumulated, currentRequest)) return new RouteDecision(GUIDANCE, NEEDS_INFORMATION);
        return new RouteDecision(ASSESSMENT, READY_FOR_ASSESSMENT);
    }
}
