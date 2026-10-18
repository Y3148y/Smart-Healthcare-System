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
}
