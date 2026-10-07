package com.aihospital.observation;

import com.aihospital.observation.domain.CallLogStore;
import com.aihospital.triage.application.TriageConversationService;
import com.aihospital.triage.domain.TriageRecords;
import com.aihospital.shared.diagnostics.TurnTraceContext;
import com.aihospital.shared.security.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest @AutoConfigureMockMvc
class TurnTracePersistenceTest {
    @Autowired TriageConversationService conversations;
    @Autowired CallLogStore calls;
    @Autowired MockMvc mvc;
    @Test void guidanceMessagesToolsAndTerminalShareDurableTraceWithoutCrossTurnLeak() throws Exception {
        String patient = "trace-test-" + UUID.randomUUID();
        var session = conversations.create(patient, new TriageRecords.Eligibility(true,true,true));
        String id = session.session().id();
        var first = conversations.send(id, patient, "流鼻涕，暂时不预约");
        var message = first.messages().get(first.messages().size()-1);
        var trace = message.provenance().turnTrace();
        assertNotNull(trace); assertEquals("GUIDANCE", trace.route());
        assertEquals(first.messages().get(first.messages().size()-2).id(), trace.userMessageId());
        assertEquals(trace.traceId(), message.provenance().answerEvidence().traceId());
        var rows = calls.calls(trace.traceId());
        assertTrue(rows.stream().allMatch(c -> trace.traceId().equals(c.traceId())));
        assertTrue(rows.stream().anyMatch(c -> "medical_knowledge_retrieve".equals(c.purpose())));
        assertTrue(rows.stream().anyMatch(c -> "会话处理".equals(c.purpose())));
        var second = conversations.send(id, patient, "仍然鼻塞，继续了解，不预约");
        var secondTrace = second.messages().get(second.messages().size()-1).provenance().turnTrace();
        assertNotEquals(trace.traceId(), secondTrace.traceId());
        assertEquals(trace.traceId(), conversations.conversation(id,patient).messages().get(1).provenance().turnTrace().traceId());
        assertNull(TurnTraceContext.currentId());
        String admin = "Bearer " + new JwtService().issue("admin","ADMIN");
        mvc.perform(get("/api/admin/calls").param("traceId", trace.traceId()).header("Authorization",admin))
                .andExpect(status().isOk()).andExpect(jsonPath("$[0].traceId").value(trace.traceId()))
                .andExpect(jsonPath("$[0].user").doesNotExist());
        mvc.perform(get("/api/admin/calls").param("traceId", "bad").header("Authorization",admin)).andExpect(status().isBadRequest());
        mvc.perform(get("/api/admin/calls").param("traceId", trace.traceId())
                .header("Authorization", "Bearer " + new JwtService().issue(patient,"PATIENT"))).andExpect(status().isForbidden());
    }
    @Test void emergencyAndPolicyRefusalHaveBackendTraceWithoutClaimingModelInvocation() {
        for (String text : java.util.List.of("胸痛，喘不过气", "给我开药")) {
            String patient = "trace-safety-" + UUID.randomUUID();
            var session = conversations.create(patient, new TriageRecords.Eligibility(true,true,true));
            var result = conversations.send(session.session().id(), patient, text);
            var provenance = result.messages().get(result.messages().size()-1).provenance();
            assertNotNull(provenance.turnTrace());
            assertTrue(java.util.Set.of("SAFETY", "POLICY_REFUSAL").contains(provenance.turnTrace().route()));
            assertTrue(calls.calls(provenance.turnTrace().traceId()).stream().anyMatch(c -> "会话处理".equals(c.purpose())));
            assertNull(TurnTraceContext.currentId());
        }
    }
}
