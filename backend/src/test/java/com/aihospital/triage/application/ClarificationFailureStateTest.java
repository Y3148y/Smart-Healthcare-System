package com.aihospital.triage.application;

import com.aihospital.triage.domain.*;
import com.aihospital.triage.domain.TriageRecords.Session;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class ClarificationFailureStateTest {
    @Test void failedGuidanceKeepsPatientMessageAndExitsProcessingWithoutInventingAnswer() {
        var store = mock(TriageStore.class);
        var engine = mock(TriageEngine.class);
        var calls = new com.aihospital.observation.infrastructure.demo.InMemoryCallLogStore();
        var service = new TriageConversationService(store, engine, null, calls);
        var now = LocalDateTime.now();
        when(store.sessions("patient")).thenReturn(List.of(new Session("session", "新会话", "", "等待描述", now, now)));
        when(store.messages("session")).thenReturn(List.of());
        when(store.assessments("session")).thenReturn(List.of());
        when(engine.needsClarification("", "合成问题")).thenReturn(true);
        var failure = new IllegalStateException("synthetic dependency failure");
        when(engine.clarificationPrompt("", List.of())).thenThrow(failure);
        assertSame(failure, assertThrows(IllegalStateException.class, () -> service.send("session", "patient", "合成问题")));
        verify(store).appendMessage("session", "USER", "合成问题");
        verify(store).updateSession("session", "合成问题", "合成问题", "处理中");
        verify(store).updateSession("session", "合成问题", "合成问题", "待重试");
        verify(store, never()).appendAssistantMessage(anyString(), anyString(), any());
        verify(store, never()).saveAssessmentAndAnswer(anyString(), anyInt(), any());
        assertNull(com.aihospital.shared.diagnostics.TurnTraceContext.currentId());
        assertEquals(1, calls.calls().size());
        assertFalse(calls.calls().get(0).success());
        assertNotNull(calls.calls().get(0).traceId());
    }
}
