package com.aihospital.triage;

import com.aihospital.triage.application.*;
import com.aihospital.triage.domain.*;
import com.aihospital.triage.domain.TriageRecords.*;
import com.aihospital.catalog.application.CatalogManagementService;
import com.aihospital.knowledge.domain.KnowledgeCatalog;
import com.aihospital.observation.domain.CallLogStore;
import com.aihospital.shared.model.Models.*;
import com.aihospital.tools.application.*;
import com.aihospital.tools.domain.AgentTool;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class AgentWorkflowCoordinatorTest {
    private final IntentExtractor extractor=mock(IntentExtractor.class);
    private final AgentModel model=mock(AgentModel.class);
    private final CatalogManagementService catalog=mock(CatalogManagementService.class);
    private final KnowledgeCatalog knowledge=mock(KnowledgeCatalog.class);
    private final TriageStore store=mock(TriageStore.class);
    private final CallLogStore logs=mock(CallLogStore.class);
    private final TriageSafetyPolicy safety=mock(TriageSafetyPolicy.class);
    private final HospitalToolExecutor executor=mock(HospitalToolExecutor.class);
    private final LocalDateTime now=LocalDateTime.now();
    private final Conversation conversation=new Conversation(new Session("session","title","preview","处理中",now,now),
            List.of(new Message("u1","USER","合成测试问题",now,null)),List.of(),null);
    private final IntentExtraction intent=new IntentExtraction(1,List.of(IntentExtraction.Intent.GENERAL_HEALTH),List.of(),List.of(),List.of());
    private AgentWorkflowCoordinator coordinator(AgentWorkflowSettings.Mode mode,AgentToolGateway gateway) {
        when(safety.assess(anyString())).thenReturn(new SafetyAssessment("p","ROUTINE",false,false,List.of(),List.of()));
        when(extractor.extract(any())).thenReturn(new IntentExtractor.Result(IntentExtractor.Status.ACCEPTED,intent,"test","",10,5,1));
        when(store.sessions("patient")).thenReturn(List.of(conversation.session()));
        when(store.messages("session")).thenReturn(conversation.messages());
        when(knowledge.documents()).thenReturn(List.of(new KnowledgeDocument("doc","title","body",1,"READY",now)));
        when(catalog.departments()).thenReturn(List.of());
        return new AgentWorkflowCoordinator(new AgentWorkflowSettings(mode,8,2048),extractor,model,gateway,catalog,knowledge,store,logs,safety);
    }
    @Test void offAndShadowDoNotPlanOrExecuteTools() {
        try(var gateway=new AgentToolGateway(executor);var coordinator=coordinator(AgentWorkflowSettings.Mode.OFF,gateway)) {
            assertNull(coordinator.prepare(conversation,"patient","合成测试问题"));verifyNoInteractions(extractor,model,executor);
        }
        try(var gateway=new AgentToolGateway(executor);var coordinator=coordinator(AgentWorkflowSettings.Mode.SHADOW,gateway)) {
            assertNull(coordinator.prepare(conversation,"patient","合成测试问题"));verify(extractor).extract(any());verifyNoInteractions(model,executor);
            verify(logs).record(argThat(log->log.purpose().equals("意图抽取") && !log.model().contains("合成测试问题")));
        }
    }
    @Test void enabledReturnsActualRetrievalNotTheModelFinalText() {
        try(var gateway=new AgentToolGateway(executor);var coordinator=coordinator(AgentWorkflowSettings.Mode.ENABLED,gateway)) {
            var retrieval=new KnowledgeCatalog.Retrieval(List.of(),false,"NO_MATCH");
            when(executor.execute(anyString(),anyMap())).thenReturn(new HospitalToolExecutor.Execution(retrieval,
                    new ToolTrace("medical_knowledge_retrieve","检索","redacted","NO_MATCH",1,true,"")));
            var step=new AtomicInteger();
            when(model.decide(any(),any(),anySet(),anySet(),anySet(),anyList(),anyInt())).thenAnswer(call->{
                Set<AgentTool> allowed=call.getArgument(2);
                if(step.getAndIncrement()==0) {
                    assertEquals(Set.of(AgentTool.MEDICAL_KNOWLEDGE),allowed);
                    return new AgentModel.Step(AgentModel.Status.TOOLS,List.of(new AgentModel.ToolCall("c1","medical_knowledge_retrieve","{\"query\":\"合成测试问题\"}")),20,10,1);
                }
                assertTrue(allowed.isEmpty());
                return new AgentModel.Step(AgentModel.Status.DONE,List.of(),30,5,1);
            });
            var prepared=coordinator.prepare(conversation,"patient","合成测试问题");
            assertNotNull(prepared);assertSame(retrieval,prepared.retrieval());assertFalse(prepared.retrieval().grounded());
            verify(executor,times(1)).execute(eq("medical_knowledge_retrieve"),anyMap());
        }
    }
    @Test void urgentAndRestrictedRequestsNeverEnterModelPlanning() {
        try(var gateway=new AgentToolGateway(executor);var coordinator=coordinator(AgentWorkflowSettings.Mode.ENABLED,gateway)) {
            when(safety.assess(anyString())).thenReturn(new SafetyAssessment("p","URGENT",false,true,List.of(),List.of()));
            assertNull(coordinator.prepare(conversation,"patient","合成测试问题"));verifyNoInteractions(extractor,model,executor);
        }
    }
    @Test void changedCorpusOrSessionStopsExecutionAndFallsBack() {
        try(var gateway=new AgentToolGateway(executor);var coordinator=coordinator(AgentWorkflowSettings.Mode.ENABLED,gateway)) {
            when(model.decide(any(),any(),anySet(),anySet(),anySet(),anyList(),anyInt())).thenAnswer(call->{
                when(knowledge.documents()).thenReturn(List.of());
                return new AgentModel.Step(AgentModel.Status.TOOLS,List.of(new AgentModel.ToolCall("c1","medical_knowledge_retrieve","{\"query\":\"合成测试问题\"}")),null,null,1);
            });
            assertNull(coordinator.prepare(conversation,"patient","合成测试问题"));verifyNoInteractions(executor);
            verify(logs,atLeastOnce()).record(argThat(log->log.purpose().equals("Agent执行") && log.model().equals("STALE_SCOPE")));
        }
    }

    @Test void conflictingFactCannotBecomeAnAffirmativeSymptomQuery() {
        try (var gateway = new AgentToolGateway(executor);
             var coordinator = coordinator(AgentWorkflowSettings.Mode.ENABLED, gateway)) {
            var source = List.of(new IntentExtraction.SourceRef("u1", "合成测试问题"));
            var affirmative = new IntentExtraction.SymptomEvent("合成", null, null, null, null, null,
                    IntentExtraction.Subject.SELF, IntentExtraction.Temporality.CURRENT,
                    IntentExtraction.Assertion.AFFIRMED, source);
            var negative = new IntentExtraction.SymptomEvent("合成", null, null, null, null, null,
                    IntentExtraction.Subject.SELF, IntentExtraction.Temporality.CURRENT,
                    IntentExtraction.Assertion.NEGATED, source);
            var proposed = new IntentExtraction(1, List.of(IntentExtraction.Intent.SYMPTOM_CONSULT),
                    List.of(affirmative, negative), List.of(), List.of());
            when(extractor.extract(any())).thenReturn(new IntentExtractor.Result(IntentExtractor.Status.ACCEPTED,
                    proposed, "test", "", 10, 5, 1));
            when(model.decide(any(), any(), anySet(), anySet(), anySet(), anyList(), anyInt())).thenReturn(
                    new AgentModel.Step(AgentModel.Status.TOOLS, List.of(new AgentModel.ToolCall("c1",
                            "medical_knowledge_retrieve", "{\"query\":\"合成\"}")), 20, 10, 1));
            assertNull(coordinator.prepare(conversation, "patient", "合成测试问题"));
            verifyNoInteractions(executor);
            verify(logs, atLeastOnce()).record(argThat(log -> log.purpose().equals("Agent执行")
                    && log.model().equals("TOOL_BLOCKED")));
        }
    }
}
