package com.aihospital.observation;

import com.aihospital.shared.diagnostics.TurnTraceContext;
import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class TurnTraceContextTest {
    @Test void historicalJsonDoesNotAcquireCurrentTurnAndInvalidTraceIsNotExposed() throws Exception {
        try (var scope = TurnTraceContext.open("new-message", "policy")) {
            var old = new com.fasterxml.jackson.databind.ObjectMapper().readValue(
                    "{\"modelStatus\":\"DEMO\",\"knowledgeHits\":0,\"localToolCalls\":0,\"toolFailures\":0}",
                    com.aihospital.triage.domain.TriageRecords.ResponseProvenance.class);
            assertNull(old.turnTrace());
            var call = new com.aihospital.shared.model.Models.CallLog("id", java.time.LocalDateTime.now(),
                    "会话处理", "", "GUIDANCE", 0, 0, 1, true, java.util.List.of(), "sensitive-text");
            assertNull(com.aihospital.observation.api.TechnicalCallView.from(call).traceId());
        }
        assertNull(TurnTraceContext.currentId());
    }
    @Test void nestedScopeRestoresOuterAndCleanupRunsOnException() {
        assertNull(TurnTraceContext.currentId());
        try (var outer = TurnTraceContext.open("message", "policy")) {
            String id = TurnTraceContext.currentId();
            outer.route("GUIDANCE");
            assertThrows(IllegalStateException.class, () -> {
                try (var inner = TurnTraceContext.open("second", "policy")) {
                    assertNotEquals(id, TurnTraceContext.currentId());
                    throw new IllegalStateException("synthetic");
                }
            });
            assertEquals(id, TurnTraceContext.currentId());
            assertEquals("message", TurnTraceContext.metadata().userMessageId());
            assertEquals("GUIDANCE", TurnTraceContext.metadata().route());
        }
        assertNull(TurnTraceContext.currentId()); assertNull(TurnTraceContext.metadata());
    }
    @Test void reusedWorkerAndOtherThreadsNeverInheritPreviousPatientTurn() throws Exception {
        var pool = Executors.newSingleThreadExecutor();
        try (var parent = TurnTraceContext.open("parent", "policy")) {
            String first = pool.submit(() -> { assertNull(TurnTraceContext.currentId());
                try (var child = TurnTraceContext.open("child", "policy")) { return TurnTraceContext.currentId(); }
            }).get(5, TimeUnit.SECONDS);
            assertNotEquals(first, TurnTraceContext.currentId());
            assertNull(pool.submit(TurnTraceContext::currentId).get(5, TimeUnit.SECONDS));
        } finally { pool.shutdownNow(); }
    }
    @Test void invalidRouteIsRejectedAndOutOfOrderCloseDoesNotLoseCurrentScope() {
        try (var outer = TurnTraceContext.open("a", "p")) {
            try (var inner = TurnTraceContext.open("b", "p")) {
                assertThrows(IllegalStateException.class, outer::close);
                assertThrows(IllegalArgumentException.class, () -> inner.route("patient text"));
                assertEquals("b", TurnTraceContext.metadata().userMessageId());
            }
        }
        assertNull(TurnTraceContext.currentId());
    }
}
