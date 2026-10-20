package com.aihospital.triage;

import com.aihospital.knowledge.domain.KnowledgeCatalog.Retrieval;
import com.aihospital.observation.domain.CallLogStore;
import com.aihospital.shared.model.Models.ToolTrace;
import com.aihospital.tools.application.HospitalToolExecutor;
import com.aihospital.triage.application.GuidanceAnswerService;
import com.aihospital.triage.domain.NarrationModel;
import com.aihospital.triage.domain.TriageEngine;
import com.aihospital.triage.domain.TriageProgress;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class GuidanceAnswerServiceTest {
    @Test void humanAndMissingInformationRoutesNeverFallThroughToRagOrNarration() {
        for(var purpose:List.of(TriageEngine.QueryPurpose.HUMAN_HELP,TriageEngine.QueryPurpose.NEEDS_INFORMATION)) {
            var tools=mock(HospitalToolExecutor.class);var narration=mock(NarrationModel.class);var logs=mock(CallLogStore.class);
            var questions=purpose==TriageEngine.QueryPurpose.NEEDS_INFORMATION?List.of("请说明本次情况的时间归属？"):List.<String>of();
            var prepared=new TriageEngine.PreparedEvidence(java.util.Optional.empty(),java.util.Optional.empty(),List.of(),purpose,questions);
            var result=new GuidanceAnswerService(tools,narration,logs).answer("synthetic","nonempty query","",List.of(),
                    stage->fail("Local route must not start retrieval or generation"),prepared,
                    evidence->{fail("Local route must not create a medical fallback");return "";});
            assertEquals(purpose.name(),result.modelStatus());assertEquals(0,result.knowledgeHits());
            assertEquals("NOT_APPLICABLE",result.answerEvidence().retrievalStatus());
            assertEquals("NOT_RUN",result.answerEvidence().generation().phase());verifyNoInteractions(tools,narration);
            if(purpose==TriageEngine.QueryPurpose.NEEDS_INFORMATION)assertEquals(questions.get(0),result.text());
            else {assertTrue(result.text().contains("提交不代表已受理"));assertFalse(result.text().contains("已为您提交"));}
            verify(logs).record(argThat(log->log.tools().isEmpty()&&log.usage().source().equals("NOT_APPLICABLE")));
        }
    }
    @Test void preparedRetrievalIsReusedWithoutExecutingAnotherToolOrBypassingNarrationChecks() {
        var tools = mock(HospitalToolExecutor.class);
        var narration = mock(NarrationModel.class);
        var logs = mock(CallLogStore.class);
        var retrieval = new Retrieval(List.of(), false, "NO_MATCH");
        var trace = new ToolTrace("medical_knowledge_retrieve", "检索", "redacted", "NO_MATCH", 1, true, "");
        when(narration.answerWithEvidence(anyString(), anyString(), anyString(), anyList(), anyString(), anyList(),
                any(), eq(true), eq("NO_MATCH"))).thenReturn(new NarrationModel.Answer("checked", "LIVE", "synthetic"));
        var stages = new ArrayList<TriageProgress>();
        var serviceState = new NarrationModel.ServiceState("内科", "NO_SLOTS", "本次没有返回可预约的模拟号源。");
        var result = new GuidanceAnswerService(tools, narration, logs).answer("question", "query", "", List.of(),
                stages::add, new TriageEngine.PreparedEvidence(retrieval, trace, List.of(serviceState)), evidence -> "fallback");
        assertEquals("checked", result.text());
        assertEquals(List.of(TriageProgress.KNOWLEDGE_RETRIEVAL, TriageProgress.ANSWER_GENERATION), stages);
        assertEquals(0, result.knowledgeHits());
        verify(narration).answerWithEvidence(anyString(), anyString(), anyString(), anyList(), anyString(), anyList(),
                eq(new NarrationModel.ServiceContext(List.of(serviceState), false)), eq(true), eq("NO_MATCH"));
        verifyNoInteractions(tools);
        verify(logs, atLeastOnce()).record(any());
    }

    @Test void failedDependencyIsNotReportedAsMissingMedicalDocuments() {
        var tools = mock(HospitalToolExecutor.class);
        var narration = mock(NarrationModel.class);
        var logs = mock(CallLogStore.class);
        when(tools.execute("medical_knowledge_retrieve", Map.of("query", "query"))).thenReturn(
                new HospitalToolExecutor.Execution(new Retrieval(List.of(), false, "SEARCH_UNAVAILABLE"),
                        new ToolTrace("medical_knowledge_retrieve", "检索", "redacted", "failed", 1, false, "unavailable")));
        when(narration.answerWithEvidence(anyString(), anyString(), anyString(), anyList(), anyString(), anyList(),
                any(), eq(true), eq("DEPENDENCY_UNAVAILABLE"))).thenReturn(new NarrationModel.Answer("fallback", "FALLBACK", ""));
        var result = new GuidanceAnswerService(tools, narration, logs).answer("question", "query", "", List.of(),
                stage -> {}, null, evidence -> "fallback");
        assertEquals(1, result.toolFailures());
        assertEquals("FALLBACK", result.modelStatus());
    }

    @Test void serviceOnlyAgentSnapshotKeepsDirectoryNoticeAndRunsMedicalRetrievalNormally() {
        var tools = mock(HospitalToolExecutor.class);
        var narration = mock(NarrationModel.class);
        var logs = mock(CallLogStore.class);
        var retrieval = new Retrieval(List.of(), false, "NO_MATCH");
        when(tools.execute("medical_knowledge_retrieve", Map.of("query", "query"))).thenReturn(
                new HospitalToolExecutor.Execution(retrieval,
                        new ToolTrace("medical_knowledge_retrieve", "检索", "redacted", "NO_MATCH", 1, true, "")));
        var serviceState = new NarrationModel.ServiceState("内科", "AVAILABLE", "本次查询返回 1 位有模拟号源的医生。");
        when(narration.answerWithEvidence(anyString(), anyString(), anyString(), anyList(), anyString(), anyList(),
                eq(new NarrationModel.ServiceContext(List.of(serviceState), false)), eq(true), eq("NO_MATCH")))
                .thenReturn(new NarrationModel.Answer("checked", "LIVE", "synthetic"));

        var prepared = new TriageEngine.PreparedEvidence(java.util.Optional.empty(), java.util.Optional.empty(),
                List.of(serviceState));
        var result = new GuidanceAnswerService(tools, narration, logs).answer("question", "query", "", List.of(),
                stage -> {}, prepared, evidence -> "fallback");

        assertEquals("checked", result.text());
        verify(tools).execute("medical_knowledge_retrieve", Map.of("query", "query"));
        verify(narration).answerWithEvidence(anyString(), anyString(), anyString(), anyList(), anyString(), anyList(),
                eq(new NarrationModel.ServiceContext(List.of(serviceState), false)), eq(true), eq("NO_MATCH"));
    }

    @Test void pureServiceRequestSkipsMedicalRetrievalAndFinalAnswerModel() {
        var tools = mock(HospitalToolExecutor.class);
        var narration = mock(NarrationModel.class);
        var logs = mock(CallLogStore.class);
        var serviceState = new NarrationModel.ServiceState("内科", "AVAILABLE", "本次查询返回 1 位有模拟号源的医生。");
        var prepared = new TriageEngine.PreparedEvidence(java.util.Optional.empty(), java.util.Optional.empty(),
                List.of(serviceState));
        var stages = new ArrayList<TriageProgress>();

        var result = new GuidanceAnswerService(tools, narration, logs).answer("我想找医生", "", "", List.of(),
                stages::add, prepared, evidence -> fail("Service-only response must not build a medical fallback"));

        assertEquals("SERVICE_ONLY", result.modelStatus());
        assertTrue(result.text().contains("查询结果已列在下方"));
        assertFalse(result.text().contains("补充主要不适"));
        assertEquals(0, result.knowledgeHits());
        assertEquals(0, result.localToolCalls());
        assertEquals("NOT_APPLICABLE", result.answerEvidence().retrievalStatus());
        assertEquals("NOT_RUN", result.answerEvidence().generation().phase());
        assertEquals(List.of("内科：本次查询返回 1 位有模拟号源的医生。"),
                result.answerEvidence().serviceNotice().messages());
        assertEquals(List.of(TriageProgress.SERVICE_RESPONSE), stages);
        verifyNoInteractions(tools, narration);
        verify(logs).record(argThat(log -> "SERVICE_ONLY".equals(log.model()) && log.success()
                && log.tools().isEmpty() && log.usage().source().equals("NOT_APPLICABLE")));
    }

    @Test void serviceRequestWithoutDirectorySnapshotDoesNotClaimLookupSucceededOrKnowledgeWasMissing() {
        var tools = mock(HospitalToolExecutor.class);
        var narration = mock(NarrationModel.class);
        var logs = mock(CallLogStore.class);
        var result = new GuidanceAnswerService(tools, narration, logs).answer("我要找医生", "", "", List.of(),
                stage -> {}, null, evidence -> fail("No medical retrieval should run for a service-only request"));
        assertTrue(result.text().contains("尚未取得科室或号源查询结果"));
        assertFalse(result.text().contains("没有医学资料"));
        assertFalse(result.text().contains("查询结果已列在下方"));
        assertEquals("NOT_APPLICABLE", result.answerEvidence().retrievalStatus());
        assertEquals("NOT_RUN", result.answerEvidence().generation().phase());
        verifyNoInteractions(tools, narration);
    }

    @Test void servicePurposeCannotDiscardAnActualMedicalRetrieval() {
        var retrieval = new Retrieval(List.of(), false, "NO_MATCH");
        var trace = new ToolTrace("medical_knowledge_retrieve", "检索", "redacted", "NO_MATCH", 1, true, "");
        assertThrows(IllegalArgumentException.class, () -> new TriageEngine.PreparedEvidence(
                java.util.Optional.of(retrieval), java.util.Optional.of(trace), List.of(), TriageEngine.QueryPurpose.SERVICE_ONLY));
    }

    @Test void aMedicalQueryStillUsesRetrievalWhenAgentDidNotPrepareEvidence() {
        var tools = mock(HospitalToolExecutor.class);
        var narration = mock(NarrationModel.class);
        var logs = mock(CallLogStore.class);
        var retrieval = new com.aihospital.knowledge.domain.KnowledgeCatalog.Retrieval(List.of(), false, "NO_MATCH");
        var trace = new com.aihospital.shared.model.Models.ToolTrace("medical_knowledge_retrieve", "检索", "redacted",
                "NO_MATCH", 4, true, "");
        when(tools.execute(eq("medical_knowledge_retrieve"), anyMap()))
                .thenReturn(new HospitalToolExecutor.Execution(retrieval, trace));
        var answer = new NarrationModel.Answer("请补充症状持续时间。", "LIVE", "",
                new com.aihospital.triage.domain.AnswerEvidence.Diagnostics("trace", "NO_MATCH", "VALIDATED",
                        List.of(), List.of(), List.of(), "UNKNOWN"));
        when(narration.answerWithEvidence(anyString(), anyString(), anyString(), anyList(), anyString(), anyList(),
                any(), anyBoolean(), anyString())).thenReturn(answer);
        var stages = new ArrayList<TriageProgress>();

        var result = new GuidanceAnswerService(tools, narration, logs).answer("胸痛", "胸痛", "", List.of(),
                stages::add, new TriageEngine.PreparedEvidence(java.util.Optional.empty(), java.util.Optional.empty(), List.of()),
                evidence -> "暂无资料");

        assertEquals("LIVE", result.modelStatus());
        assertEquals(1, result.localToolCalls());
        assertEquals(List.of(TriageProgress.KNOWLEDGE_RETRIEVAL, TriageProgress.ANSWER_GENERATION), stages);
        verify(tools).execute(eq("medical_knowledge_retrieve"), anyMap());
        verify(narration).answerWithEvidence(anyString(), anyString(), anyString(), anyList(), anyString(), anyList(),
                any(), anyBoolean(), anyString());
    }
}
