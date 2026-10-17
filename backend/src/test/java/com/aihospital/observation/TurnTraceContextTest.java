package com.aihospital.observation;

import com.aihospital.shared.diagnostics.TurnTraceContext;
import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

class TurnTraceContextTest {
    @Test void budgetEstimatesRoundTripSeparatelyFromUsageAndOldRowsRemainUnknown() throws Exception {
        var json=new com.fasterxml.jackson.databind.ObjectMapper();
        var budget=new TurnTraceContext.ContextBudget(1000,2000,2,1,"UTF8_BYTES_WITH_FRAMING");
        var execution=new TurnTraceContext.Execution("CONTEXT","NARRATION","BOUNDED_ESTIMATE",0,
                null,null,null,1,"WORKFLOW",budget);
        assertEquals(budget,json.readValue(json.writeValueAsString(execution),TurnTraceContext.Execution.class).contextBudget());
        var old=json.readValue("{\"kind\":\"TOOL\",\"name\":\"legacy\",\"elapsedMs\":0}",TurnTraceContext.Execution.class);
        assertNull(old.contextBudget());assertEquals("UNKNOWN",old.initiator());
        assertThrows(IllegalArgumentException.class,()->new TurnTraceContext.ContextBudget(2001,2000,0,0,"UTF8_BYTES_WITH_FRAMING"));
    }
    @Test void executionSummaryFollowsExplicitWorkerScopeAndPersistsWithoutCrossTurnLeak() throws Exception {
        var json = new com.fasterxml.jackson.databind.ObjectMapper();
        try (var scope = TurnTraceContext.open("message", "policy")) {
            var snapshot = TurnTraceContext.metadata();
            var pool = Executors.newSingleThreadExecutor();
            try {
                pool.submit(() -> {
                    try (var worker = TurnTraceContext.restore(snapshot)) {
                        TurnTraceContext.record(new TurnTraceContext.Execution("RETRIEVAL", "LOCAL_BM25_UNRERANKED",
                                "UNAVAILABLE / DISABLED_BY_CONFIGURATION", 10, true, false, false, 2));
                    }
                }).get(5, TimeUnit.SECONDS);
            } finally { pool.shutdownNow(); }
            var restored = json.readValue(json.writeValueAsString(TurnTraceContext.metadata()), TurnTraceContext.Metadata.class);
            assertEquals(1, restored.executions().size());
            assertTrue(restored.executions().get(0).rrfUsed());
            assertFalse(restored.executions().get(0).rerankUsed());
        }
        try (var scope = TurnTraceContext.open("other", "policy")) {
            assertTrue(TurnTraceContext.metadata().executions().isEmpty());
        }
        var old = json.readValue("{\"traceId\":\"old\",\"elapsedMs\":1}", TurnTraceContext.Metadata.class);
        assertNull(old.executions());
    }
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
