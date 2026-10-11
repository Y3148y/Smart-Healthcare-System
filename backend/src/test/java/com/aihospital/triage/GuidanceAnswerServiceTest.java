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
        var result = new GuidanceAnswerService(tools, narration, logs).answer("question", "query", "", List.of(),
                stages::add, new TriageEngine.PreparedEvidence(retrieval, trace), evidence -> "fallback");
        assertEquals("checked", result.text());
        assertEquals(List.of(TriageProgress.KNOWLEDGE_RETRIEVAL, TriageProgress.ANSWER_GENERATION), stages);
        assertEquals(0, result.knowledgeHits());
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
}
