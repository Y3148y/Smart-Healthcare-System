package com.aihospital.triage;

import com.aihospital.triage.application.WorkflowRouter;
import com.aihospital.triage.domain.RouteDecision;
import com.aihospital.triage.domain.TriageEngine;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class WorkflowRouterTest {
    @Test void emergencyDoesNotWaitForReviewOrClarification() {
        var engine = mock(TriageEngine.class);
        when(engine.requiresImmediateCare("history")).thenReturn(true);
        assertEquals(RouteDecision.Reason.IMMEDIATE_CARE, new WorkflowRouter(engine).decide("history", "latest").reason());
        verify(engine, never()).requiresReview(anyString());
        verify(engine, never()).needsClarification(anyString(), anyString());
    }

    @Test void urgentCannotBecomeClarification() {
        var engine = mock(TriageEngine.class);
        when(engine.requiresReview("history")).thenReturn(true);
        var decision = new WorkflowRouter(engine).decide("history", "latest");
        assertEquals(RouteDecision.Route.ASSESSMENT, decision.route());
        assertEquals(RouteDecision.Reason.HUMAN_REVIEW, decision.reason());
        verify(engine, never()).needsClarification(anyString(), anyString());
    }

    @Test void ordinaryRouteUsesCurrentRequestWithoutDroppingSafetyHistory() {
        var engine = mock(TriageEngine.class);
        when(engine.needsClarification("history", "latest")).thenReturn(true);
        assertEquals(RouteDecision.Route.GUIDANCE, new WorkflowRouter(engine).decide("history", "latest").route());
        when(engine.needsClarification("history", "latest")).thenReturn(false);
        assertEquals(RouteDecision.Reason.READY_FOR_ASSESSMENT, new WorkflowRouter(engine).decide("history", "latest").reason());
    }
}
