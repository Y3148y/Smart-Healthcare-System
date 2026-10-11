package com.aihospital.triage;

import com.aihospital.triage.application.*;
import com.aihospital.triage.domain.IntentExtraction;
import com.aihospital.tools.application.*;
import com.aihospital.tools.domain.*;
import com.aihospital.shared.model.Models.ToolTrace;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class AgentRunnerTest {
    private final IntentInput input = new IntentInput(List.of(new IntentInput.SourceMessage("u1", "USER", "测试问题")));
    private final IntentExtraction intent = new IntentExtraction(1, List.of(IntentExtraction.Intent.GENERAL_HEALTH), List.of(), List.of(), List.of());
    private AgentToolRun scope(Duration duration) {
        return new AgentToolRun(new AgentToolRun.Scope("patient", "session", "trace", 1, "corpus",
                Set.of(AgentTool.MEDICAL_KNOWLEDGE), Set.of()), 4, duration, duration);
    }
    private AgentModel.Step tools(String id) {
        return new AgentModel.Step(AgentModel.Status.TOOLS,
                List.of(new AgentModel.ToolCall(id, "medical_knowledge_retrieve", "{\"query\":\"测试问题\"}")), 20, 10, 1);
    }
    private AgentModel.Step done() { return new AgentModel.Step(AgentModel.Status.DONE, List.of(), 30, 5, 1); }
    private HospitalToolExecutor executor() {
        var executor = mock(HospitalToolExecutor.class);
        when(executor.execute(anyString(), anyMap())).thenReturn(new HospitalToolExecutor.Execution(Map.of("evidence", "bounded-result"),
                new ToolTrace("medical_knowledge_retrieve", "检索", "redacted", "完成", 1, true, "")));
        return executor;
    }
    @Test void modelSelectsToolAndConsumesItsResultBeforeCompletion() {
        var count = new AtomicInteger(); var executor = executor();
        AgentModel model = (in, facts, allowed, departments, transcript, seconds) -> {
            if (count.getAndIncrement()==0) { assertTrue(transcript.isEmpty()); return tools("c1"); }
            assertEquals("c1", transcript.get(0).calls().get(0).id());
            assertTrue(transcript.get(0).results().get(0).contains("bounded-result")); return done();
        };
        try (var gateway = new AgentToolGateway(executor); var runner = new AgentRunner(model, gateway, 3, 2)) {
            var result = runner.run(input, intent, scope(Duration.ofSeconds(3)), Set.of(AgentTool.MEDICAL_KNOWLEDGE), ()->true);
            assertEquals(AgentRunner.Status.COMPLETED, result.status()); assertEquals(2, result.decisions().size());
            verify(executor, times(1)).execute(anyString(), anyMap());
        }
    }
    @Test void cannotDeclareEvidenceWithoutRequiredToolOrExecuteAWrite() {
        var executor = executor();
        for (AgentModel.Step response : List.of(done(), new AgentModel.Step(AgentModel.Status.TOOLS,
                List.of(new AgentModel.ToolCall("w1", "create_booking", "{}")), null, null, 0))) {
            try (var gateway = new AgentToolGateway(executor); var runner = new AgentRunner((a,b,c,d,e,f)->response, gateway, 3, 2)) {
                assertNotEquals(AgentRunner.Status.COMPLETED, runner.run(input, intent, scope(Duration.ofSeconds(3)),
                        Set.of(AgentTool.MEDICAL_KNOWLEDGE), ()->true).status());
            }
        }
        verifyNoInteractions(executor);
    }
    @Test void repeatedReadIsCachedAndTerminatesLoop() {
        var count = new AtomicInteger(); var executor = executor();
        try (var gateway = new AgentToolGateway(executor);
             var runner = new AgentRunner((a,b,c,d,e,f)->tools("c"+count.incrementAndGet()), gateway, 3, 2)) {
            var result = runner.run(input, intent, scope(Duration.ofSeconds(3)), Set.of(AgentTool.MEDICAL_KNOWLEDGE), ()->true);
            assertEquals(AgentRunner.Status.LOOP_DETECTED, result.status()); assertEquals(2, count.get());
            verify(executor,times(1)).execute(anyString(), anyMap());
        }
    }
    @Test void staleScopeIsRejectedBeforeAndAfterModel() {
        var allowed = new AtomicBoolean(true); var count = new AtomicInteger(); var executor = executor();
        try (var gateway = new AgentToolGateway(executor); var runner = new AgentRunner((a,b,c,d,e,f)->{
            count.incrementAndGet(); allowed.set(false); return tools("c1"); }, gateway, 3, 2)) {
            assertEquals(AgentRunner.Status.STALE_SCOPE, runner.run(input,intent,scope(Duration.ofSeconds(3)),
                    Set.of(), ()->false).status()); assertEquals(0,count.get());
            assertEquals(AgentRunner.Status.STALE_SCOPE, runner.run(input,intent,scope(Duration.ofSeconds(3)),
                    Set.of(), allowed::get).status()); assertEquals(1,count.get()); verifyNoInteractions(executor);
        }
    }
    @Test void hangingModelCannotHoldTurnBeyondDeadline() throws Exception {
        var interrupted = new CountDownLatch(1); var executor = executor();
        try (var gateway = new AgentToolGateway(executor); var runner = new AgentRunner((a,b,c,d,e,f)->{
            try { new CountDownLatch(1).await(); } catch (InterruptedException stop) { interrupted.countDown(); Thread.currentThread().interrupt(); }
            return done(); }, gateway, 3, 1)) {
            assertEquals(AgentRunner.Status.MODEL_TIMEOUT, runner.run(input,intent,scope(Duration.ofMillis(100)),
                    Set.of(), ()->true).status()); assertTrue(interrupted.await(2,TimeUnit.SECONDS)); verifyNoInteractions(executor);
        }
    }
    @Test void rejectsDuplicateIdsAndOversizedBatchWithoutExecutingTools() {
        var executor = executor(); var call = tools("c1").calls().get(0);
        for (var batch : List.of(List.of(call,call), Collections.nCopies(5,call))) {
            try (var gateway=new AgentToolGateway(executor);var runner=new AgentRunner((a,b,c,d,e,f)->
                    new AgentModel.Step(AgentModel.Status.TOOLS,batch,null,null,0),gateway,3,2)) {
                assertEquals(AgentRunner.Status.MODEL_INVALID, runner.run(input,intent,scope(Duration.ofSeconds(3)),Set.of(),()->true).status());
            }
        }
        verifyNoInteractions(executor);
    }
}
