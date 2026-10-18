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
    @Test void localPlanningBudgetFailureIsNotRecordedAsAProviderRequest(){
        try(var gateway=new AgentToolGateway(executor);var coordinator=coordinator(AgentWorkflowSettings.Mode.ENABLED,gateway)){
            when(model.decide(any(),any(),anySet(),anySet(),anySet(),anyList(),anyInt())).thenReturn(
                    new AgentModel.Step(AgentModel.Status.UNAVAILABLE,List.of(),null,null,1,"CONTEXT_INPUT_BUDGET_EXCEEDED"));
            assertNull(coordinator.prepare(conversation,"patient","合成测试问题"));
            verifyNoInteractions(executor);
            verify(logs).record(argThat(log->log.purpose().equals("Agent工具规划")
                    &&log.usage().source().equals("NOT_APPLICABLE")&&!log.success()));
        }
    }
    @Test void extractionBudgetFailureIsLocalAndCannotReachPlannerOrTools(){
        try(var gateway=new AgentToolGateway(executor);var coordinator=coordinator(AgentWorkflowSettings.Mode.ENABLED,gateway)){
            when(extractor.extract(any())).thenReturn(new IntentExtractor.Result(IntentExtractor.Status.UNAVAILABLE,null,
                    "synthetic","INTENT_CONTEXT_INPUT_BUDGET_EXCEEDED",null,null,1));
            assertNull(coordinator.prepare(conversation,"patient","合成测试问题"));
            verifyNoInteractions(model,executor,durableFacts);
            verify(logs).record(argThat(log->log.purpose().equals("意图抽取")
                    &&log.usage().source().equals("NOT_APPLICABLE")&&!log.success()));
        }
    }
    private final IntentExtractor extractor=mock(IntentExtractor.class);
    private final AgentModel model=mock(AgentModel.class);
    private final CatalogManagementService catalog=mock(CatalogManagementService.class);
    private final KnowledgeCatalog knowledge=mock(KnowledgeCatalog.class);
    private final TriageStore store=mock(TriageStore.class);
    private final CallLogStore logs=mock(CallLogStore.class);
    private final TriageSafetyPolicy safety=mock(TriageSafetyPolicy.class);
    private final SessionFactStore durableFacts=mock(SessionFactStore.class);
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
        return new AgentWorkflowCoordinator(new AgentWorkflowSettings(mode,8,2048),extractor,model,gateway,catalog,knowledge,store,logs,safety,durableFacts);
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
            assertNotNull(prepared);assertSame(retrieval,prepared.retrieval().orElseThrow());assertFalse(prepared.retrieval().orElseThrow().grounded());
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

    @Test void persistenceFailureFallsBackWithoutPlanningOrTools() {
        try(var gateway=new AgentToolGateway(executor);var coordinator=coordinator(AgentWorkflowSettings.Mode.ENABLED,gateway)){
            when(durableFacts.stage(anyString(),any(),any(),any())).thenThrow(new IllegalStateException("Synthetic state conflict"));
            assertNull(coordinator.prepare(conversation,"patient","合成测试问题"));
            verifyNoInteractions(model,executor);
        }
    }
    @Test void historicalObservationCannotExpandAuthorizedModelOrToolWindow() {
        try(var gateway=new AgentToolGateway(executor);var coordinator=coordinator(AgentWorkflowSettings.Mode.ENABLED,gateway)){
            var historical=new IntentExtraction.SymptomEvent("SyntheticHistoricalOnly",null,null,null,null,null,
                    IntentExtraction.Subject.SELF,IntentExtraction.Temporality.CURRENT,IntentExtraction.Assertion.AFFIRMED,
                    List.of(new IntentExtraction.SourceRef("old-message","SyntheticHistoricalOnly")));
            when(durableFacts.stage(anyString(),any(),any(),any())).thenReturn(new SessionFacts(99,List.of(historical),List.of()));
            when(model.decide(any(),any(),anySet(),anySet(),anySet(),anyList(),anyInt())).thenAnswer(call->{
                IntentInput source=call.getArgument(0);Set<String> queries=call.getArgument(4);
                assertEquals(1,source.messages().size());assertEquals(Set.of("合成测试问题"),queries);
                return new AgentModel.Step(AgentModel.Status.DONE,List.of(),1,1,1);
            });
            assertNull(coordinator.prepare(conversation,"patient","合成测试问题"));
            verify(model).decide(any(),any(),anySet(),anySet(),anySet(),anyList(),anyInt());verifyNoInteractions(executor);
        }
    }
    @Test void conflictingFactStopsAgentQueryBeforePlanning() {
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
            assertNull(coordinator.prepare(conversation, "patient", "合成测试问题"));
            verifyNoInteractions(model, executor);
        }
    }

    @Test void negatedExtractedSymptomDoesNotAuthorizeRawUtteranceAsAgentQuery() {
        try (var gateway = new AgentToolGateway(executor);
             var coordinator = coordinator(AgentWorkflowSettings.Mode.ENABLED, gateway)) {
            var negated = new IntentExtraction.SymptomEvent("合成测试问题", null, null, null, null, null,
                    IntentExtraction.Subject.SELF, IntentExtraction.Temporality.CURRENT,
                    IntentExtraction.Assertion.NEGATED,
                    List.of(new IntentExtraction.SourceRef("u1", "合成测试问题")));
            var proposal = new IntentExtraction(1, List.of(IntentExtraction.Intent.SYMPTOM_CONSULT),
                    List.of(negated), List.of(), List.of());
            when(extractor.extract(any())).thenReturn(new IntentExtractor.Result(IntentExtractor.Status.ACCEPTED,
                    proposal, "test", "", 10, 5, 1));
            assertNull(coordinator.prepare(conversation, "patient", "合成测试问题"));
            verifyNoInteractions(model, executor);
        }
    }

    @Test void affirmativeExtractedSymptomIsTheOnlyAuthorizedQuery() {
        try (var gateway = new AgentToolGateway(executor);
             var coordinator = coordinator(AgentWorkflowSettings.Mode.ENABLED, gateway)) {
            var mixedConversation = new Conversation(conversation.session(),
                    List.of(new Message("u1", "USER", "头痛，胸痛没有", now, null)), List.of(), null);
            var affirmative = new IntentExtraction.SymptomEvent("头痛", null, null, null, null, null,
                    IntentExtraction.Subject.SELF, IntentExtraction.Temporality.CURRENT,
                    IntentExtraction.Assertion.AFFIRMED,
                    List.of(new IntentExtraction.SourceRef("u1", "头痛")));
            var negated = new IntentExtraction.SymptomEvent("胸痛", null, null, null, null, null,
                    IntentExtraction.Subject.SELF, IntentExtraction.Temporality.CURRENT,
                    IntentExtraction.Assertion.NEGATED,
                    List.of(new IntentExtraction.SourceRef("u1", "胸痛没有")));
            var proposal = new IntentExtraction(1, List.of(IntentExtraction.Intent.SYMPTOM_CONSULT),
                    List.of(affirmative, negated), List.of(), List.of());
            when(extractor.extract(any())).thenReturn(new IntentExtractor.Result(IntentExtractor.Status.ACCEPTED,
                    proposal, "test", "", 10, 5, 1));
            when(model.decide(any(), any(), anySet(), anySet(), anySet(), anyList(), anyInt())).thenAnswer(call -> {
                assertEquals(Set.of("头痛"), call.getArgument(4));
                return new AgentModel.Step(AgentModel.Status.DONE, List.of(), 1, 1, 1);
            });
            assertNull(coordinator.prepare(mixedConversation, "patient", "头痛，胸痛没有"));
            verify(model).decide(any(), any(), anySet(), anySet(), anySet(), anyList(), anyInt());
            verifyNoInteractions(executor);
        }
    }

    @Test void unrelatedConflictKeepsOnlyTheSourceCheckedUncontestedQueryAvailableToTools() {
        try (var gateway = new AgentToolGateway(executor);
             var coordinator = coordinator(AgentWorkflowSettings.Mode.ENABLED, gateway)) {
            var mixed = new Conversation(conversation.session(),
                    List.of(new Message("u1", "USER", "头痛，腹痛是否存在说不清", now, null)), List.of(), null);
            var head = new IntentExtraction.SymptomEvent("头痛", null, null, null, null, null,
                    IntentExtraction.Subject.SELF, IntentExtraction.Temporality.CURRENT,
                    IntentExtraction.Assertion.AFFIRMED, List.of(new IntentExtraction.SourceRef("u1", "头痛")));
            var abdomen = new IntentExtraction.SymptomEvent("腹痛", null, null, null, null, null,
                    IntentExtraction.Subject.SELF, IntentExtraction.Temporality.CURRENT,
                    IntentExtraction.Assertion.UNCERTAIN, List.of(new IntentExtraction.SourceRef("u1", "腹痛")));
            var proposal = new IntentExtraction(1, List.of(IntentExtraction.Intent.SYMPTOM_CONSULT),
                    List.of(head, abdomen), List.of(),
                    List.of(new IntentExtraction.Conflict(IntentExtraction.Field.ASSERTION, abdomen.sourceRefs())));
            when(extractor.extract(any())).thenReturn(new IntentExtractor.Result(IntentExtractor.Status.ACCEPTED,
                    proposal, "test", "", 10, 5, 1));
            var retrieval = new KnowledgeCatalog.Retrieval(List.of(), false, "NO_MATCH");
            when(executor.execute(eq("medical_knowledge_retrieve"), eq(Map.of("query", "头痛"))))
                    .thenReturn(new HospitalToolExecutor.Execution(retrieval,
                            new ToolTrace("medical_knowledge_retrieve", "检索", "redacted", "NO_MATCH", 1, true, "")));
            var steps = new AtomicInteger();
            when(model.decide(any(), any(), anySet(), anySet(), anySet(), anyList(), anyInt())).thenAnswer(call -> {
                assertEquals(Set.of("头痛"), call.getArgument(4));
                return steps.getAndIncrement() == 0
                        ? new AgentModel.Step(AgentModel.Status.TOOLS, List.of(new AgentModel.ToolCall(
                                "k1", "medical_knowledge_retrieve", "{\"query\":\"头痛\"}")), 1, 1, 1)
                        : new AgentModel.Step(AgentModel.Status.DONE, List.of(), 1, 1, 1);
            });
            var prepared = coordinator.prepare(mixed, "patient", mixed.messages().get(0).content());
            assertNotNull(prepared);
            assertSame(retrieval, prepared.retrieval().orElseThrow());
            verify(executor).execute("medical_knowledge_retrieve", Map.of("query", "头痛"));
            verifyNoMoreInteractions(executor);
        }
    }

    @Test void successfulReadOnlyDirectoryResultsReachPreparedPatientServiceNoticeWithoutDoctorIdentity() {
        try (var gateway = new AgentToolGateway(executor);
             var coordinator = coordinator(AgentWorkflowSettings.Mode.ENABLED, gateway)) {
            when(catalog.departments()).thenReturn(List.of(
                    new com.aihospital.catalog.domain.CatalogRecords.Department("d1", "内科", true)));
            var proposed = new IntentExtraction(1, List.of(IntentExtraction.Intent.GENERAL_HEALTH,
                    IntentExtraction.Intent.FIND_DOCTOR, IntentExtraction.Intent.BOOKING_REQUEST),
                    List.of(), List.of(), List.of());
            when(extractor.extract(any())).thenReturn(new IntentExtractor.Result(IntentExtractor.Status.ACCEPTED,
                    proposed, "test", "", 10, 5, 1));
            var retrieval = new KnowledgeCatalog.Retrieval(List.of(), false, "NO_MATCH");
            var doctor = new com.aihospital.shared.model.Models.Doctor("doctor-id", "private doctor name", "主任医师",
                    "内科", "上午", "2026-10-18", 2, 10, 30);
            when(executor.execute(anyString(), anyMap())).thenAnswer(call -> {
                String tool = call.getArgument(0);
                Object data = switch (tool) {
                    case "medical_knowledge_retrieve" -> retrieval;
                    case "department_search" -> Map.of("department", "内科", "exists", true, "enabled", true,
                            "status", "AVAILABLE", "message", "科室已配置", "activeDoctors", 1,
                            "scheduledDoctors", 1, "bookableDoctors", 1);
                    case "doctor_schedule_search" -> List.of(doctor);
                    default -> throw new IllegalArgumentException(tool);
                };
                return new HospitalToolExecutor.Execution(data, new ToolTrace(tool, tool, "redacted", "ok", 1, true, ""));
            });
            var step = new AtomicInteger();
            when(model.decide(any(), any(), anySet(), anySet(), anySet(), anyList(), anyInt())).thenAnswer(call -> {
                if (step.getAndIncrement() == 0) return new AgentModel.Step(AgentModel.Status.TOOLS, List.of(
                        new AgentModel.ToolCall("k1", "medical_knowledge_retrieve", "{\"query\":\"合成测试问题\"}"),
                        new AgentModel.ToolCall("d1", "department_search", "{\"department\":\"内科\"}"),
                        new AgentModel.ToolCall("s1", "doctor_schedule_search", "{\"department\":\"内科\"}")), 20, 10, 1);
                return new AgentModel.Step(AgentModel.Status.DONE, List.of(), 5, 2, 1);
            });

            var prepared = coordinator.prepare(conversation, "patient", "合成测试问题");
            assertNotNull(prepared);
            assertSame(retrieval, prepared.retrieval().orElseThrow());
            assertEquals(List.of(
                    new NarrationModel.ServiceState("内科", "AVAILABLE", "科室已配置"),
                    new NarrationModel.ServiceState("内科", "AVAILABLE", "本次查询返回 1 位有模拟号源的医生。")),
                    prepared.services());
            assertFalse(prepared.services().toString().contains("private doctor name"));
            assertEquals(3, executorCalls());
        }
    }

    @Test void explicitDoctorLookupCanUseDirectoryToolsWithoutInventingAMedicalQuery() {
        try (var gateway = new AgentToolGateway(executor);
             var coordinator = coordinator(AgentWorkflowSettings.Mode.ENABLED, gateway)) {
            when(catalog.departments()).thenReturn(List.of(
                    new com.aihospital.catalog.domain.CatalogRecords.Department("d1", "内科", true)));
            var proposed = new IntentExtraction(1, List.of(IntentExtraction.Intent.FIND_DOCTOR,
                    IntentExtraction.Intent.BOOKING_REQUEST), List.of(), List.of(), List.of());
            when(extractor.extract(any())).thenReturn(new IntentExtractor.Result(IntentExtractor.Status.ACCEPTED,
                    proposed, "test", "", 10, 5, 1));
            var doctor = new com.aihospital.shared.model.Models.Doctor("doctor-id", "private doctor name", "主任医师",
                    "内科", "上午", "2026-10-18", 2, 10, 30);
            when(executor.execute(anyString(), anyMap())).thenAnswer(call -> {
                String tool = call.getArgument(0);
                Object data = switch (tool) {
                    case "department_search" -> Map.of("department", "内科", "exists", true, "enabled", true,
                            "status", "AVAILABLE", "message", "科室已配置", "activeDoctors", 1,
                            "scheduledDoctors", 1, "bookableDoctors", 1);
                    case "doctor_schedule_search" -> List.of(doctor);
                    default -> throw new AssertionError("No medical query should be invented: " + tool);
                };
                return new HospitalToolExecutor.Execution(data,
                        new ToolTrace(tool, tool, "redacted", "ok", 1, true, ""));
            });
            var step = new AtomicInteger();
            when(model.decide(any(), any(), anySet(), anySet(), anySet(), anyList(), anyInt())).thenAnswer(call -> {
                Set<AgentTool> available = call.getArgument(2);
                assertFalse(available.contains(AgentTool.MEDICAL_KNOWLEDGE));
                assertEquals(Set.of(AgentTool.DEPARTMENT, AgentTool.SCHEDULE), available);
                assertTrue(((Set<?>) call.getArgument(4)).isEmpty());
                if (step.getAndIncrement() == 0) return new AgentModel.Step(AgentModel.Status.TOOLS, List.of(
                        new AgentModel.ToolCall("d1", "department_search", "{\"department\":\"内科\"}"),
                        new AgentModel.ToolCall("s1", "doctor_schedule_search", "{\"department\":\"内科\"}")), 20, 10, 1);
                return new AgentModel.Step(AgentModel.Status.DONE, List.of(), 5, 2, 1);
            });

            var prepared = coordinator.prepare(conversation, "patient", "合成测试问题");
            assertNotNull(prepared);
            assertTrue(prepared.retrieval().isEmpty());
            assertTrue(prepared.trace().isEmpty());
            assertEquals(2, prepared.services().size());
            assertFalse(prepared.services().toString().contains("private doctor name"));
            verify(executor, never()).execute(eq("medical_knowledge_retrieve"), anyMap());
        }
    }

    @Test void completedServicePlanningWithoutDirectoryResultsStillPreventsMedicalQueryFallback() {
        try (var gateway = new AgentToolGateway(executor);
             var coordinator = coordinator(AgentWorkflowSettings.Mode.ENABLED, gateway);
             var trace = com.aihospital.shared.diagnostics.TurnTraceContext.open("u1", "test-policy")) {
            when(catalog.departments()).thenReturn(List.of(
                    new com.aihospital.catalog.domain.CatalogRecords.Department("d1", "内科", true)));
            var proposed = new IntentExtraction(1, List.of(IntentExtraction.Intent.FIND_DOCTOR), List.of(), List.of(), List.of());
            when(extractor.extract(any())).thenReturn(new IntentExtractor.Result(IntentExtractor.Status.ACCEPTED,
                    proposed, "test", "", 10, 5, 1));
            when(model.decide(any(), any(), anySet(), anySet(), anySet(), anyList(), anyInt()))
                    .thenReturn(new AgentModel.Step(AgentModel.Status.DONE, List.of(), 2, 1, 1));

            var prepared = coordinator.prepare(conversation, "patient", "我想找医生");

            assertNotNull(prepared);
            assertTrue(prepared.retrieval().isEmpty());
            assertTrue(prepared.services().isEmpty());
            verifyNoInteractions(executor);
            var route = com.aihospital.shared.diagnostics.TurnTraceContext.metadata().executions().stream()
                    .filter(event -> "INTENT_ROUTE".equals(event.kind())).findFirst().orElseThrow();
            assertEquals("FIND_DOCTOR", route.name());
            assertFalse(route.status().contains("medical_knowledge_retrieve"));
            assertFalse(route.name().contains("我想找医生"));
        }
    }

    private int executorCalls() {
        return mockingDetails(executor).getInvocations().size();
    }
}
