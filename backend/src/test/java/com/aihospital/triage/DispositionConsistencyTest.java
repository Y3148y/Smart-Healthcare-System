package com.aihospital.triage;

import com.aihospital.booking.application.BookingApplicationService;
import com.aihospital.booking.application.SimulationBookingService;
import com.aihospital.catalog.application.DoctorCatalogService;
import com.aihospital.shared.model.Models.*;
import com.aihospital.triage.application.TriageConversationService;
import com.aihospital.triage.domain.TriageEngine;
import com.aihospital.triage.domain.Disposition;
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
    @Test void successfulReceiptReplayDoesNotRebookOrReassessLaterSessionRisk() {
        var store = mock(com.aihospital.booking.domain.BookingStore.class);
        var catalog = mock(DoctorCatalogService.class);
        var slots = mock(com.aihospital.catalog.domain.SlotStore.class);
        var conversations = mock(TriageConversationService.class);
        var doctor = new Doctor("d1", "测试医生", "医师", "测试科", "上午", "2026-10-03", 0, 1, 0);
        var prior = new Appointment("a1", "a1", "patient", doctor, "模拟预约", "s1", LocalDateTime.now());
        when(store.appointmentByKey("patient", "key")).thenReturn(java.util.Optional.of(prior));
        var service = new BookingApplicationService(new SimulationBookingService(store, catalog, slots, conversations));
        assertSame(prior, service.book("d1", "s1", "patient", "key"));
        verifyNoInteractions(conversations, catalog, slots);
        verify(store, never()).saveAppointment(any(), anyString());
        assertEquals(409, assertThrows(ResponseStatusException.class,
                () -> service.book("other", "s1", "patient", "key")).getStatusCode().value());
    }
    @Test void jsonAndProgressCallsShareTheSameSessionGuardAndReleaseItAfterCompletion() throws Exception {
        var store = mock(TriageStore.class);
        TurnStoreStub.configure(store);
        var engine = mock(TriageEngine.class);
        var catalog = mock(DoctorCatalogService.class);
        when(store.sessions("patient")).thenReturn(List.of(new Session("s1", "新会话", "", "待补充信息",
                LocalDateTime.now(), LocalDateTime.now())));
        when(store.messages("s1")).thenReturn(List.of());
        when(store.assessments("s1")).thenReturn(List.of());
        when(engine.needsClarification(anyString(), anyString())).thenReturn(true);
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        when(engine.clarificationPrompt(anyString(), anyList())).thenAnswer(invocation -> {
            entered.countDown();
            assertTrue(release.await(5, java.util.concurrent.TimeUnit.SECONDS));
            return new TriageEngine.Guidance("Synthetic response", "DEMO", 0, 0, 0);
        });
        var service = new TriageConversationService(store, engine, catalog,
                new com.aihospital.observation.infrastructure.demo.InMemoryCallLogStore());
        var worker = java.util.concurrent.Executors.newSingleThreadExecutor();
        try {
            var first = worker.submit(() -> service.send("s1", "patient", "first"));
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
            var conflict = assertThrows(ResponseStatusException.class,
                    () -> service.send("s1", "patient", "duplicate", stage -> {}));
            assertEquals(409, conflict.getStatusCode().value());
            verify(store, times(1)).appendMessage(eq("s1"), eq("USER"), anyString());
            release.countDown();
            first.get(5, java.util.concurrent.TimeUnit.SECONDS);
            assertDoesNotThrow(() -> service.send("s1", "patient", "after completion"));
            verify(store, times(2)).appendMessage(eq("s1"), eq("USER"), anyString());
            doThrow(new IllegalStateException("Synthetic failure")).when(engine).clarificationPrompt(anyString(), anyList());
            assertThrows(IllegalStateException.class, () -> service.send("s1", "patient", "failed turn"));
            doReturn(new TriageEngine.Guidance("Synthetic recovery", "DEMO", 0, 0, 0))
                    .when(engine).clarificationPrompt(anyString(), anyList());
            assertDoesNotThrow(() -> service.send("s1", "patient", "after failure"));
            verify(store, times(4)).appendMessage(eq("s1"), eq("USER"), anyString());
        } finally { release.countDown(); worker.shutdownNow(); }
    }

    @Test void onlyKnownRoutineDispositionsCanAuthorizeBooking() {
        for (String risk : java.util.Arrays.asList(null, "", "unknown", "待重试", Disposition.PENDING,
                Disposition.URGENT, Disposition.EMERGENCY)) {
            assertFalse(Disposition.isBookable(risk));
            assertNotEquals("已完成分诊", Disposition.sessionStatus(risk));
        }
        for (String risk : List.of(Disposition.ROUTINE, Disposition.MULTI)) {
            assertTrue(Disposition.isBookable(risk));
            assertEquals("已完成分诊", Disposition.sessionStatus(risk));
        }
    }

    @Test void unfinishedSessionCannotConsumeSlotsUsingAHistoricRoutineResult() {
        var store = mock(com.aihospital.booking.domain.BookingStore.class);
        var catalog = mock(DoctorCatalogService.class);
        var slots = mock(com.aihospital.catalog.domain.SlotStore.class);
        var conversations = mock(TriageConversationService.class);
        var doctor = new Doctor("d1", "测试医生", "医师", "测试科", "上午", "2026-10-03", 1, 1, 0);
        when(conversations.latestResult("s1", "patient")).thenReturn(new TriageResult("s1", Disposition.ROUTINE,
                72, "测试科", doctor, "", "", List.of(), List.of(), List.of(), "DEMO", "",
                LocalDateTime.now(), null, true, ""));
        var service = new SimulationBookingService(store, catalog, slots, conversations);
        for (String state : List.of("处理中", "待重试", "新会话", "待补充信息", "紧急提示", "建议尽快就医", "unknown")) {
            when(conversations.requireOwner("s1", "patient")).thenReturn(new Session("s1", "", "", state,
                    LocalDateTime.now(), LocalDateTime.now()));
            var error = assertThrows(ResponseStatusException.class,
                    () -> service.book("d1", "s1", "patient", "new-key"));
            assertEquals(409, error.getStatusCode().value());
        }
        verifyNoInteractions(slots, catalog);
        verify(store, never()).saveAppointment(any(), anyString());
        when(conversations.requireOwner("s1", "patient")).thenReturn(new Session("s1", "", "", "已完成分诊",
                LocalDateTime.now(), LocalDateTime.now()));
        when(catalog.find("d1")).thenReturn(doctor);
        when(slots.decrementAvailableSlot("d1", doctor.date())).thenReturn(1);
        when(slots.remaining("d1", doctor.date())).thenReturn(0);
        assertEquals("s1", service.book("d1", "s1", "patient", "new-key").triageSessionId());
        verify(slots, times(1)).decrementAvailableSlot("d1", doctor.date());
    }

    @Test
    void pendingAssessmentKeepsVersionButHasNoBookableDoctorAndPendingSession() {
        TriageStore store = mock(TriageStore.class);
        TurnStoreStub.configure(store);
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
        var store = mock(com.aihospital.booking.domain.BookingStore.class);
        var catalog = mock(com.aihospital.catalog.application.DoctorCatalogService.class);
        var slots = mock(com.aihospital.catalog.domain.SlotStore.class);
        TriageConversationService conversations = mock(TriageConversationService.class);
        var booking = new SimulationBookingService(store, catalog, slots, conversations);
        when(conversations.requireOwner("s1", "patient")).thenReturn(new com.aihospital.triage.domain.TriageRecords.Session(
                "s1", "test", "test", "已完成分诊", LocalDateTime.now(), LocalDateTime.now()));
        Doctor doctor = new Doctor("d1", "测试医生", "医师", "全科医学科", "上午", "2026-10-03", 1, 1, 0);
        when(conversations.latestResult("s1", "patient")).thenReturn(new TriageResult("s1", "待补充信息", 55,
                "全科医学科", doctor, "", "", List.of(), List.of(), List.of(), "DEMO", "", LocalDateTime.now(),
                null, true, ""));

        assertThrows(ResponseStatusException.class,
                () -> new BookingApplicationService(booking).book("d1", "s1", "patient", "key"));
        verifyNoInteractions(catalog, slots);
        verify(store, never()).saveAppointment(any(), anyString());
    }
}
