package com.aihospital.observation;

import com.aihospital.observation.api.TechnicalCallView;
import com.aihospital.observation.api.AdminObservationController;
import com.aihospital.observation.domain.CallLogStore;
import com.aihospital.shared.model.Models.*;
import com.aihospital.shared.security.RoleGuard;
import com.aihospital.shared.security.JwtService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class TechnicalCallViewTest {
    @Test void apiDoesNotSerializeSensitiveToolPayloadOrPatientIdentity() throws Exception {
        var trace = new ToolTrace("medical_knowledge_retrieve", "private-label", "private-input", "private-outcome", 42, false, "private-error");
        var stored = new CallLog("fixture", LocalDateTime.now(), "预问诊引导", "private-patient", "model/LIVE", 0, 0, 100, false, List.of(trace));
        var calls = mock(CallLogStore.class); when(calls.calls()).thenReturn(List.of(stored));
        var controller = new AdminObservationController(null, calls, new RoleGuard(), null);
        String auth = "Bearer " + new JwtService().issue("admin", "ADMIN");
        var result = controller.calls(auth);
        String serialized = new ObjectMapper().findAndRegisterModules().writeValueAsString(result);
        assertFalse(serialized.contains("private-"));
        assertTrue(result.get(0).tools().get(0).errorPresent());
        assertEquals("medical_knowledge_retrieve", result.get(0).tools().get(0).tool());
        assertNull(result.get(0).inputTokens());
        assertEquals("private-input", stored.tools().get(0).input());
        assertThrows(org.springframework.web.server.ResponseStatusException.class, () -> controller.calls(null));
        assertThrows(org.springframework.web.server.ResponseStatusException.class,
                () -> controller.calls("Bearer " + new JwtService().issue("patient", "PATIENT")));
    }
    @Test void measuredUsageIsPreservedAndUnrecognizedFreeTextIsNotExposed() {
        var stored = new CallLog("id", LocalDateTime.now(), "private-purpose", "patient", "model", 123, 45, 10, true,
                List.of(new ToolTrace("private-tool", "label", "input", "outcome", 1, true, null)));
        var result = TechnicalCallView.from(stored);
        assertEquals(123, result.inputTokens()); assertEquals(45, result.outputTokens());
        assertEquals("其他调用", result.purpose()); assertEquals("OTHER", result.tools().get(0).tool());
    }
}
