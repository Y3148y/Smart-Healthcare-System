package com.aihospital.tools.application;

import com.aihospital.shared.diagnostics.TurnTraceContext;
import com.aihospital.tools.domain.AgentTool;
import com.aihospital.tools.domain.AgentToolRun;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;
import java.util.Map;
import java.util.concurrent.*;

/** Bounded local read-only execution. Not yet connected to model-selected patient actions. */
@Service
public final class AgentToolGateway implements AutoCloseable {
    public enum Status { SUCCESS, BLOCKED, FAILED, TIMEOUT, BUDGET_EXHAUSTED, RESULT_TOO_LARGE, BUSY }
    public record Result(String callId, Status status, String content, boolean cached) {}
    private final HospitalToolExecutor executor;
    private final ObjectMapper json = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(5).maxStringLength(2200).build()).build())
            .enable(com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final ExecutorService workers = new ThreadPoolExecutor(2, 4, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(8), runnable -> {
                var thread = new Thread(runnable, "agent-read-tool"); thread.setDaemon(true); return thread;
            }, new ThreadPoolExecutor.AbortPolicy());

    public AgentToolGateway(HospitalToolExecutor executor) { this.executor = executor; }

    public Result execute(AgentToolRun run, String callId, String code, String arguments) {
        if (callId == null || !callId.matches("[A-Za-z0-9_-]{1,128}")) return result("", Status.BLOCKED);
        if (run == null || arguments == null || arguments.length() > 2400) return result(callId, Status.BLOCKED);
        var captured = TurnTraceContext.metadata();
        if (captured != null && !captured.traceId().equals(run.scope().traceId()))
            return result(callId, Status.BLOCKED);
        final AgentTool tool;
        final String value;
        try {
            tool = AgentTool.fromCode(code);
            if (!run.scope().allowedTools().contains(tool)) return result(callId, Status.BLOCKED);
            var parameters = json.readTree(arguments);
            if (!parameters.isObject() || parameters.size() != 1 || !parameters.path(tool.parameter()).isTextual())
                return result(callId, Status.BLOCKED);
            value = parameters.get(tool.parameter()).textValue().strip();
            if (value.isBlank() || value.codePointCount(0, value.length()) > tool.maxLength())
                return result(callId, Status.BLOCKED);
            if (tool != AgentTool.MEDICAL_KNOWLEDGE && !run.scope().allowedDepartments().contains(value))
                return result(callId, Status.BLOCKED);
        } catch (Exception invalid) { return result(callId, Status.BLOCKED); }
        synchronized (run) {
            if (run.expired()) return result(callId, Status.BUDGET_EXHAUSTED);
            String key = code + "\u0000" + value;
            if (!run.bindCall(callId, key)) return result(callId, Status.BLOCKED);
            String previous = run.cached(key);
            if (previous != null) return new Result(callId, Status.SUCCESS, previous, true);
            final long timeout;
            try { timeout = run.reserveCall(); }
            catch (IllegalStateException exhausted) { return result(callId, Status.BUDGET_EXHAUSTED); }
            Future<HospitalToolExecutor.Execution> pending;
            try {
                pending = workers.submit(() -> {
                    try (var ignored = TurnTraceContext.restore(captured)) {
                        return executor.execute(code, Map.of(tool.parameter(), value));
                    }
                });
            } catch (RejectedExecutionException busy) { return result(callId, Status.BUSY); }
            try {
                var execution = pending.get(timeout, TimeUnit.NANOSECONDS);
                if (execution == null || execution.trace() == null || !execution.trace().success())
                    return result(callId, Status.FAILED);
                String content = json.writeValueAsString(execution.data());
                if (content.length() > 12000) return result(callId, Status.RESULT_TOO_LARGE);
                if (run.expired()) return result(callId, Status.BUDGET_EXHAUSTED);
                run.remember(key, content);
                return new Result(callId, Status.SUCCESS, content, false);
            } catch (TimeoutException timeoutFailure) { return result(callId, Status.TIMEOUT); }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt(); return result(callId, Status.FAILED);
            } catch (Exception failure) { return result(callId, Status.FAILED); }
            finally { pending.cancel(true); }
        }
    }

    private Result result(String callId, Status status) { return new Result(callId, status, status.name(), false); }
    @Override @PreDestroy public void close() { workers.shutdownNow(); }
}
