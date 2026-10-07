package com.aihospital.triage;

import com.aihospital.catalog.domain.DepartmentAvailability;
import com.aihospital.catalog.domain.DepartmentAvailability.Status;
import com.aihospital.catalog.domain.DoctorDirectory;
import com.aihospital.knowledge.domain.KnowledgeCatalog;
import com.aihospital.observation.infrastructure.demo.InMemoryCallLogStore;
import com.aihospital.shared.model.Models.Evidence;
import com.aihospital.triage.domain.NarrationModel;
import com.aihospital.triage.domain.TriageSafetyPolicy;
import com.aihospital.triage.infrastructure.demo.RuleBasedTriageEngine;
import com.aihospital.triage.infrastructure.llm.StructuredDecisionModel;
import com.aihospital.tools.application.HospitalToolExecutor;
import com.aihospital.tools.infrastructure.demo.InMemoryToolRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Synthetic dependencies test wiring only, not medical or real-provider answer quality. */
class NarrationServiceFlowTest {
    static class Capture implements NarrationModel {
        ServiceContext context;
        int calls;
        public Answer explain(String text, String department, String candidates, String evidence,
                              String fallback, List<Turn> history) { return new Answer(fallback, "DEMO", ""); }
        public Answer explainWithServices(String text, String department, String candidates, String evidence,
                                          String fallback, List<Turn> history, ServiceContext services) {
            context = services; calls++;
            return explain(text, department, candidates, evidence, fallback, history);
        }
    }
    private RuleBasedTriageEngine engine(DoctorDirectory directory, Capture narration, boolean grounded) {
        var safety = new TriageSafetyPolicy();
        var knowledge = mock(KnowledgeCatalog.class);
        when(knowledge.retrieve(anyString(), anyInt(), anyDouble())).thenReturn(new KnowledgeCatalog.Retrieval(
                grounded ? List.of(new Evidence("synthetic", "synthetic", "测试依据", 1)) : List.of(), grounded, "test"));
        var logs = new InMemoryCallLogStore();
        return new RuleBasedTriageEngine(safety, directory, knowledge, narration, logs,
                new HospitalToolExecutor(new InMemoryToolRegistry(), directory, knowledge, safety, logs),
                new StructuredDecisionModel("demo", "", "", "", 5));
    }
    @ParameterizedTest @EnumSource(Status.class)
    void forwardsAllDirectoryStatesWithoutChangingMedicalRisk(Status status) {
        var directory = mock(DoctorDirectory.class);
        var state = new DepartmentAvailability("呼吸内科", status, 0, 0, 0);
        when(directory.departmentAvailability(anyString())).thenReturn(state);
        when(directory.doctors(anyString())).thenReturn(List.of());
        var narration = new Capture();
        var result = engine(directory, narration, true).triage("test", "咳嗽两天，我要挂号", "test", List.of());
        assertEquals("普通", result.riskLevel());
        assertTrue(narration.context.routineBookingAllowed()); // eligibility != a slot or reservation
        assertNull(result.doctor());
        assertEquals(status.name(), narration.context.services().get(0).status());
        assertEquals(state.message(), narration.context.services().get(0).message());
    }
    @Test void queryFailureIsNotInventedAsMissingDepartment() {
        var directory = mock(DoctorDirectory.class);
        when(directory.departmentAvailability(anyString())).thenThrow(new IllegalStateException("synthetic failure"));
        when(directory.doctors(anyString())).thenReturn(List.of());
        var narration = new Capture();
        engine(directory, narration, true).triage("test", "咳嗽两天，挂号", "test", List.of());
        assertEquals("QUERY_FAILED", narration.context.services().get(0).status());
        assertFalse(narration.context.services().get(0).message().contains("未配置"));
    }
    @Test void urgentDoesNotBecomeBookableEvenWhenDirectoryIsAvailable() {
        var directory = mock(DoctorDirectory.class);
        when(directory.departmentAvailability(anyString())).thenReturn(new DepartmentAvailability("骨科", Status.AVAILABLE, 1, 1, 1));
        when(directory.doctors(anyString())).thenReturn(List.of());
        var narration = new Capture();
        var result = engine(directory, narration, true).triage("test", "我手摔断了", "test", List.of());
        assertEquals("尽快就医", result.riskLevel());
        assertFalse(narration.context.routineBookingAllowed());
        assertNull(result.doctor());
    }
    @Test void emergencyNeverWaitsForModelOrDirectory() {
        var directory = mock(DoctorDirectory.class);
        var narration = new Capture();
        var result = engine(directory, narration, true).triage("test", "胸痛，喘不过气", "test", List.of());
        assertEquals("SAFETY_RULE", result.modelStatus());
        assertEquals(0, narration.calls);
        verifyNoInteractions(directory);
    }
    @Test void missingEvidenceDoesNotEnterGroundedServiceNarration() {
        var directory = mock(DoctorDirectory.class);
        when(directory.departmentAvailability(anyString())).thenReturn(new DepartmentAvailability("呼吸内科", Status.AVAILABLE, 1, 1, 1));
        when(directory.doctors(anyString())).thenReturn(List.of());
        var narration = new Capture();
        var result = engine(directory, narration, false).triage("test", "咳嗽两天，挂号", "test", List.of());
        assertFalse(result.grounded());
        assertEquals(0, narration.calls);
        assertNull(result.doctor());
    }
}
