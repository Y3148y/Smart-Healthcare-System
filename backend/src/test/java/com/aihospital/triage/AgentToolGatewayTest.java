package com.aihospital.triage;

import com.aihospital.shared.diagnostics.TurnTraceContext;
import com.aihospital.shared.model.Models.ToolTrace;
import com.aihospital.tools.application.AgentToolGateway;
import com.aihospital.tools.application.HospitalToolExecutor;
import com.aihospital.tools.domain.AgentTool;
import com.aihospital.tools.domain.AgentToolRun;
import org.junit.jupiter.api.Test;
import java.time.Duration;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class AgentToolGatewayTest {
    @Test void modelSelectedToolsAreTaggedOnWorkerButDoNotOverwriteWorkflowOrigin(){
        var executor=mock(HospitalToolExecutor.class);
        when(executor.execute(anyString(),anyMap())).thenAnswer(call->{
            assertEquals("AGENT",com.aihospital.shared.diagnostics.InvocationContext.current());
            return success(Map.of("status","SYNTHETIC"));
        });
        try(var workflow=com.aihospital.shared.diagnostics.InvocationContext.open(com.aihospital.shared.diagnostics.InvocationContext.Origin.WORKFLOW);
            var gateway=new AgentToolGateway(executor)){
            assertEquals(AgentToolGateway.Status.SUCCESS,gateway.execute(run(4),"synthetic","department_search","{\"department\":\"内科\"}").status());
            assertEquals("WORKFLOW",com.aihospital.shared.diagnostics.InvocationContext.current());
        }
        assertEquals("UNKNOWN",com.aihospital.shared.diagnostics.InvocationContext.current());
    }
    @Test void medicalQueryMustComeFromServerFrozenPatientSources() {
        var executor=mock(HospitalToolExecutor.class);
        when(executor.execute(anyString(),anyMap())).thenReturn(success(Map.of("status","NO_MATCH")));
        var run=new AgentToolRun(new AgentToolRun.Scope("p","s","t",1,"v",
                Set.of(AgentTool.MEDICAL_KNOWLEDGE),Set.of(),Set.of("头痛")),
                4,Duration.ofSeconds(3),Duration.ofSeconds(1));
        try(var gateway=new AgentToolGateway(executor)) {
            assertEquals(AgentToolGateway.Status.BLOCKED,gateway.execute(run,"c1","medical_knowledge_retrieve",
                    "{\"query\":\"四肢骨折\"}").status());
            verifyNoInteractions(executor);
            assertEquals(AgentToolGateway.Status.SUCCESS,gateway.execute(run,"c2","medical_knowledge_retrieve",
                    "{\"query\":\" 头痛 \"}").status());
            assertEquals(1,run.snapshots().size());
            assertEquals("头痛",run.snapshots().get(0).arguments().get("query"));
        }
    }
    @Test void emptyMedicalQueryAllowlistDeniesEveryModelQuery() {
        var executor = mock(HospitalToolExecutor.class);
        var empty = new AgentToolRun(new AgentToolRun.Scope("p", "s", "t", 1, "v",
                Set.of(AgentTool.MEDICAL_KNOWLEDGE), Set.of(), Set.of()),
                1, Duration.ofSeconds(3), Duration.ofSeconds(1));
        try (var gateway = new AgentToolGateway(executor)) {
            assertEquals(AgentToolGateway.Status.BLOCKED, gateway.execute(empty, "c1",
                    "medical_knowledge_retrieve", "{\"query\":\"未经授权的查询\"}").status());
            verifyNoInteractions(executor);
        }
    }
    private AgentToolRun run(int maximum) {
        return new AgentToolRun(scope(1), maximum, Duration.ofSeconds(3), Duration.ofSeconds(1));
    }
    private AgentToolRun.Scope scope(long version) {
        return new AgentToolRun.Scope("patient", "session", "trace", version, "corpus-v1",
                Set.of(AgentTool.values()), Set.of("内科"), Set.of("测试问题"));
    }
    private HospitalToolExecutor.Execution success(Object value) {
        return new HospitalToolExecutor.Execution(value, new ToolTrace("department_search", "科室查询", "redacted",
                "完成", 1, true, ""));
    }
    @Test void blocksWriteSafetyUnknownToolsAndIdentityInjectionBeforeExecution() {
        var executor = mock(HospitalToolExecutor.class);
        try (var gateway = new AgentToolGateway(executor)) {
            for (String tool : new String[]{"appointment_create", "knowledge_approve", "symptom_tag_search", "unknown"})
                assertEquals(AgentToolGateway.Status.BLOCKED, gateway.execute(run(4), "c1", tool, "{}").status());
            for (String args : new String[]{"{\"department\":\"内科\",\"patientId\":\"other\"}",
                    "{\"department\":\"内科\",\"department\":\"内科\"}", "{\"department\":5}",
                    "{\"department\":\"未授权科室\"}", "{\"department\":\"内科\"} {}"})
                assertEquals(AgentToolGateway.Status.BLOCKED, gateway.execute(run(4), "c1", "department_search", args).status());
            assertEquals(AgentToolGateway.Status.BLOCKED, gateway.execute(run(4), "invalid\ncall", "department_search", "{}").status());
            verifyNoInteractions(executor);
        }
    }
    @Test void respectsPerTurnAllowedToolsAndDoesNotExposeUnderlyingErrors() {
        var executor = mock(HospitalToolExecutor.class);
        when(executor.execute(anyString(), anyMap())).thenThrow(new IllegalStateException("secret-patient-data"));
        var restricted = new AgentToolRun(new AgentToolRun.Scope("p", "s", "t", 0, "v", Set.of(), Set.of()),
                1, Duration.ofSeconds(2), Duration.ofSeconds(1));
        try (var gateway = new AgentToolGateway(executor)) {
            assertEquals(AgentToolGateway.Status.BLOCKED,
                    gateway.execute(restricted, "c1", "medical_knowledge_retrieve", "{\"query\":\"头痛\"}").status());
            var failed = gateway.execute(run(2), "c2", "department_search", "{\"department\":\"内科\"}");
            assertEquals(AgentToolGateway.Status.FAILED, failed.status());
            assertFalse(failed.content().contains("secret"));
        }
    }
    @Test void deduplicatesOnlyInsideOneRunAndReturnsEachNewCallId() {
        var executor = mock(HospitalToolExecutor.class);
        when(executor.execute(anyString(), anyMap())).thenReturn(success(Map.of("status", "AVAILABLE")));
        try (var gateway = new AgentToolGateway(executor)) {
            var run = run(1);
            assertFalse(gateway.execute(run, "c1", "department_search", "{\"department\":\"内科\"}").cached());
            var cached = gateway.execute(run, "c2", "department_search", "{\"department\":\" 内科 \"}");
            assertTrue(cached.cached()); assertEquals("c2", cached.callId()); assertEquals(1, run.calls());
            assertEquals(AgentToolGateway.Status.BUDGET_EXHAUSTED,
                    gateway.execute(run, "c3", "doctor_schedule_search", "{\"department\":\"内科\"}").status());
            assertFalse(gateway.execute(run(1), "c4", "department_search", "{\"department\":\"内科\"}").cached());
            verify(executor, times(2)).execute("department_search", Map.of("department", "内科"));
        }
    }
    @Test void expiredRunDoesNotReuseCachedResultsOrInvokeAnotherTool() {
        var clock = new AtomicLong();
        var budget = new AgentToolRun(scope(1), 2, Duration.ofSeconds(2), Duration.ofSeconds(1), clock::get);
        var executor = mock(HospitalToolExecutor.class);
        when(executor.execute(anyString(), anyMap())).thenReturn(success(Map.of()));
        try (var gateway = new AgentToolGateway(executor)) {
            assertEquals(AgentToolGateway.Status.SUCCESS,
                    gateway.execute(budget, "c1", "department_search", "{\"department\":\"内科\"}").status());
            clock.set(TimeUnit.SECONDS.toNanos(3));
            assertEquals(AgentToolGateway.Status.BUDGET_EXHAUSTED,
                    gateway.execute(budget, "c2", "department_search", "{\"department\":\"内科\"}").status());
            verify(executor, times(1)).execute(anyString(), anyMap());
        }
    }
    @Test void timeoutCancelsReadOnlyTaskAndDoesNotCacheLateResult() throws Exception {
        var interrupted = new CountDownLatch(1);
        var executor = mock(HospitalToolExecutor.class);
        when(executor.execute(anyString(), anyMap())).thenAnswer(call -> {
            try { new CountDownLatch(1).await(); }
            catch (InterruptedException cancellation) { interrupted.countDown(); Thread.currentThread().interrupt(); }
            return success(Map.of("late", true));
        });
        var run = new AgentToolRun(scope(1), 1, Duration.ofSeconds(2), Duration.ofMillis(100));
        try (var gateway = new AgentToolGateway(executor)) {
            assertEquals(AgentToolGateway.Status.TIMEOUT,
                    gateway.execute(run, "c1", "department_search", "{\"department\":\"内科\"}").status());
            assertTrue(interrupted.await(2, TimeUnit.SECONDS));
            assertEquals(AgentToolGateway.Status.BUDGET_EXHAUSTED,
                    gateway.execute(run, "c2", "department_search", "{\"department\":\"内科\"}").status());
        }
    }
    @Test void propagatesTraceSnapshotAndRestoresWorkerContext() {
        var executor = mock(HospitalToolExecutor.class);
        var noLeak = new AtomicBoolean(false);
        try (var gateway = new AgentToolGateway(executor)) {
            try (var trace = TurnTraceContext.open("message", "policy")) {
                trace.route("GUIDANCE"); String expected = TurnTraceContext.currentId();
                when(executor.execute(anyString(), anyMap())).thenAnswer(call -> {
                    assertEquals(expected, TurnTraceContext.currentId());
                    assertEquals("message", TurnTraceContext.metadata().userMessageId());
                    assertEquals("GUIDANCE", TurnTraceContext.metadata().route());
                    return success(Map.of());
                });
                var tracedRun = new AgentToolRun(new AgentToolRun.Scope("patient", "session", expected, 1, "v1",
                        Set.of(AgentTool.DEPARTMENT), Set.of("内科")), 1, Duration.ofSeconds(3), Duration.ofSeconds(1));
                assertEquals(AgentToolGateway.Status.SUCCESS,
                        gateway.execute(tracedRun, "c1", "department_search", "{\"department\":\"内科\"}").status());
                assertEquals(expected, TurnTraceContext.currentId());
            }
            doAnswer(call -> {
                noLeak.set(TurnTraceContext.currentId() == null); return success(Map.of());
            }).when(executor).execute(anyString(), anyMap());
            gateway.execute(run(1), "c2", "department_search", "{\"department\":\"内科\"}");
            assertTrue(noLeak.get()); assertNull(TurnTraceContext.currentId());
        }
    }
    @Test void oversizedResultIsNotTruncatedIntoMisleadingMedicalEvidence() {
        var executor = mock(HospitalToolExecutor.class);
        when(executor.execute(anyString(), anyMap())).thenReturn(success("长".repeat(12001)));
        try (var gateway = new AgentToolGateway(executor)) {
            var result = gateway.execute(run(1), "c1", "department_search", "{\"department\":\"内科\"}");
            assertEquals(AgentToolGateway.Status.RESULT_TOO_LARGE, result.status());
            assertEquals("RESULT_TOO_LARGE", result.content());
        }
    }
    @Test void cannotReuseCallIdForAnotherToolOrUseAnotherTurnTrace() {
        var executor = mock(HospitalToolExecutor.class);
        when(executor.execute(anyString(), anyMap())).thenReturn(success(Map.of()));
        try (var gateway = new AgentToolGateway(executor)) {
            var budget = run(3);
            assertEquals(AgentToolGateway.Status.SUCCESS,
                    gateway.execute(budget, "c1", "department_search", "{\"department\":\"内科\"}").status());
            assertEquals(AgentToolGateway.Status.BLOCKED,
                    gateway.execute(budget, "c1", "doctor_schedule_search", "{\"department\":\"内科\"}").status());
            try (var ignored = TurnTraceContext.open("another-message", "v1")) {
                assertEquals(AgentToolGateway.Status.BLOCKED,
                        gateway.execute(budget, "c2", "department_search", "{\"department\":\"内科\"}").status());
            }
            verify(executor, times(1)).execute(anyString(), anyMap());
        }
    }
}
