package com.aihospital.triage;

import com.aihospital.booking.application.BookingApplicationService;
import com.aihospital.booking.application.SimulationBookingService;
import com.aihospital.catalog.application.DoctorCatalogService;
import com.aihospital.shared.model.Models.*;
import com.aihospital.triage.application.TriageConversationService;
import com.aihospital.triage.domain.TriageEngine;
import com.aihospital.triage.domain.TriageRecords.Session;
import com.aihospital.triage.domain.TriageStore;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class DispositionConsistencyTest {
    @Test
    void pendingAssessmentKeepsVersionButHasNoBookableDoctorAndPendingSession() {
        TriageStore store = mock(TriageStore.class);
        TriageEngine engine = mock(TriageEngine.class);
        DoctorCatalogService catalog = mock(DoctorCatalogService.class);
        Doctor doctor = new Doctor("d1", "测试医生", "医师", "全科医学科", "上午", "2026-10-03", 1, 1, 0);
        TriageResult pending = new TriageResult("s1", "待补充信息", 55, "全科医学科", doctor,
                "请补充信息", "仅供挂号参考", List.of(),
                List.of(new ToolTrace("doctor_schedule_search", "号源", "全科医学科", "成功", 1, true, "")),
                List.of(new DepartmentCandidate("全科医学科", "信息不足", doctor)),
                "DEMO", "", LocalDateTime.now(), null, true, "");
        when(store.sessions("patient")).thenReturn(List.of(new Session("s1", "新会话", "", "待补充信息",
                LocalDateTime.now(), LocalDateTime.now())));
        when(store.messages("s1")).thenReturn(List.of());
        when(store.assessments("s1")).thenReturn(List.of());
        when(engine.triage(eq("s1"), anyString(), eq("patient"), anyList())).thenReturn(pending);

        new TriageConversationService(store, engine, catalog, new com.aihospital.observation.infrastructure.demo.InMemoryCallLogStore()).send("s1", "patient", "我要挂号");

        var saved = org.mockito.ArgumentCaptor.forClass(TriageResult.class);
        verify(store).saveAssessmentAndAnswer(eq("s1"), eq(1), saved.capture());
        assertNull(saved.getValue().doctor());
        assertNull(saved.getValue().candidates().get(0).doctor());
        verify(store, atLeastOnce()).updateSession(eq("s1"), anyString(), anyString(), eq("待补充信息"));
        verifyNoInteractions(catalog);
    }

    @Test
    void bookingRejectsPendingRiskEvenIfHistoricResultStillHasDoctor() {
        SimulationBookingService booking = mock(SimulationBookingService.class);
        TriageConversationService conversations = mock(TriageConversationService.class);
        Doctor doctor = new Doctor("d1", "测试医生", "医师", "全科医学科", "上午", "2026-10-03", 1, 1, 0);
        when(conversations.latestResult("s1", "patient")).thenReturn(new TriageResult("s1", "待补充信息", 55,
                "全科医学科", doctor, "", "", List.of(), List.of(), List.of(), "DEMO", "", LocalDateTime.now(),
                null, true, ""));

        assertThrows(ResponseStatusException.class,
                () -> new BookingApplicationService(booking, conversations).book("d1", "s1", "patient", "key"));
        verifyNoInteractions(booking);
    }
}
