package com.aihospital.triage;

import com.aihospital.shared.diagnostics.TurnTraceContext;
import com.aihospital.triage.application.AgentWorkflowCoordinator;
import com.aihospital.triage.application.TriageWorkflow;
import com.aihospital.triage.domain.NarrationModel;
import com.aihospital.triage.domain.TriageEngine;
import com.aihospital.triage.domain.TriageProgress;
import com.aihospital.triage.domain.TriageRecords.Conversation;
import com.aihospital.triage.domain.TriageRecords.Session;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TriageWorkflowTest {
    private final TriageEngine engine = mock(TriageEngine.class);
    private final AgentWorkflowCoordinator agent = mock(AgentWorkflowCoordinator.class);
    private final Conversation conversation = new Conversation(new Session("s", "title", "preview", "处理中",
            LocalDateTime.now(), LocalDateTime.now()), List.of(), List.of(), null);
    private final List<NarrationModel.Turn> history = List.of(new NarrationModel.Turn("USER", "latest"));

    @Test void ordinaryGuidancePreservesHistoryAndProgressAndNeverCreatesAssessment() {
        when(engine.needsClarification("history", "latest")).thenReturn(true);
        var guidance = new TriageEngine.Guidance("answer", "LIVE", 1, 1, 0);
        when(engine.clarificationPrompt(eq("history"), eq(history), any())).thenReturn(guidance);
        var stages = new ArrayList<TriageProgress>();
        try (var trace = TurnTraceContext.open("u", "policy")) {
            var result = new TriageWorkflow(engine, agent).execute(conversation, "patient", "history", "latest",
                    history, stages::add, true, trace);
            assertSame(guidance, ((TriageWorkflow.GuidanceOutcome) result).guidance());
            assertEquals(List.of(TriageProgress.SAFETY_CHECK), stages);
            assertEquals("GUIDANCE", TurnTraceContext.metadata().route());
            var route = TurnTraceContext.metadata().executions().get(0);
            assertEquals("ROUTE", route.kind());
            assertEquals("GUIDANCE", route.name());
            assertEquals("NEEDS_INFORMATION", route.status());
            assertEquals("WORKFLOW", route.initiator());
            assertFalse(route.toString().contains("latest"));
            verify(agent).prepare(conversation, "patient", "history");
            verify(engine, never()).triage(anyString(), anyString(), anyString(), anyList(), any());
        }
    }

    @Test void urgentBypassesAgentAndMaintainsLegacyNonStreamingCall() {
        when(engine.requiresReview("history")).thenReturn(true);
        try (var trace = TurnTraceContext.open("u", "policy")) {
            assertInstanceOf(TriageWorkflow.AssessmentOutcome.class, new TriageWorkflow(engine, agent).execute(
                    conversation, "patient", "history", "latest", history, stage -> {}, false, trace));
            verifyNoInteractions(agent);
            verify(engine).triage("s", "history", "patient", history);
            verify(engine, never()).needsClarification(anyString(), anyString());
            assertEquals("HUMAN_REVIEW", TurnTraceContext.metadata().executions().get(0).status());
        }
    }

    @Test void failedComputationPropagatesRatherThanInventingSuccess() {
        when(engine.triage("s", "history", "patient", history)).thenThrow(new IllegalStateException("synthetic failure"));
        try (var trace = TurnTraceContext.open("u", "policy")) {
            assertThrows(IllegalStateException.class, () -> new TriageWorkflow(engine, agent).execute(conversation,
                    "patient", "history", "latest", history, stage -> {}, false, trace));
            verifyNoInteractions(agent);
            assertEquals("READY_FOR_ASSESSMENT", TurnTraceContext.metadata().executions().get(0).status());
        }
    }

    @Test void emergencyRouteIsRecordedBeforeAssessmentWithoutModelDelegation() {
        when(engine.requiresImmediateCare("history")).thenReturn(true);
        try (var trace=TurnTraceContext.open("u", "policy")) {
            new TriageWorkflow(engine,agent).execute(conversation,"patient","history","latest",history,
                    stage->{},false,trace);
            var event=TurnTraceContext.metadata().executions().get(0);
            assertEquals("IMMEDIATE_CARE",event.status());
            assertEquals("ASSESSMENT",event.name());
            assertEquals("WORKFLOW",event.initiator());
            verifyNoInteractions(agent);
            verify(engine,never()).requiresReview(anyString());
        }
    }
}
