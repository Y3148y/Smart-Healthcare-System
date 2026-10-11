package com.aihospital.triage.application;

import com.aihospital.shared.diagnostics.TurnTraceContext;
import com.aihospital.tools.application.AgentToolGateway;
import com.aihospital.tools.domain.*;
import com.aihospital.triage.domain.IntentExtraction;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;

/** Local bounded orchestrator. Caller supplies a model port; patient integration is separate. */
public final class AgentRunner implements AutoCloseable {
    public enum Status { COMPLETED, MODEL_FAILED, MODEL_INVALID, MODEL_TIMEOUT, TOOL_FAILED,
        MISSING_REQUIRED_TOOL, BUDGET_EXHAUSTED, STALE_SCOPE, LOOP_DETECTED, CONTEXT_LIMIT, BUSY, TOOL_BLOCKED }
    public record RunResult(Status status, List<AgentModel.Exchange> transcript, List<AgentModel.Step> decisions) {
        public RunResult { transcript = List.copyOf(transcript); decisions = List.copyOf(decisions); }
    }
    private final AgentModel model;
    private final AgentToolGateway gateway;
    private final int maxSteps, timeoutSeconds;
    private final ExecutorService workers = new ThreadPoolExecutor(2, 2, 30, TimeUnit.SECONDS,
            new ArrayBlockingQueue<>(4), task -> {
                var thread = new Thread(task, "agent-decision"); thread.setDaemon(true); return thread;
            }, new ThreadPoolExecutor.AbortPolicy());

    public AgentRunner(AgentModel model, AgentToolGateway gateway, int maxSteps, int timeoutSeconds) {
        if (maxSteps < 1 || maxSteps > 3 || timeoutSeconds < 1 || timeoutSeconds > 35)
            throw new IllegalArgumentException("Invalid agent decision budget");
        this.model = Objects.requireNonNull(model); this.gateway = Objects.requireNonNull(gateway);
        this.maxSteps = maxSteps; this.timeoutSeconds = timeoutSeconds;
    }

    public RunResult run(IntentInput input, IntentExtraction intent, AgentToolRun scope,
            Set<AgentTool> required, BooleanSupplier scopeStillValid) {
        Objects.requireNonNull(input); Objects.requireNonNull(intent); Objects.requireNonNull(scopeStillValid);
        required = Set.copyOf(required);
        var exchanges = new ArrayList<AgentModel.Exchange>(); var decisions = new ArrayList<AgentModel.Step>();
        var completed = new HashSet<AgentTool>(); var callIds = new HashSet<String>(); int contextCharacters = 0;
        if (!scope.scope().allowedTools().containsAll(required)) return result(Status.MISSING_REQUIRED_TOOL, exchanges, decisions);
        for (int step = 0; step < maxSteps; step++) {
            if (!valid(scopeStillValid)) return result(Status.STALE_SCOPE, exchanges, decisions);
            long remaining = Math.min(scope.remainingNanos(), TimeUnit.SECONDS.toNanos(timeoutSeconds));
            if (remaining <= 0) return result(Status.BUDGET_EXHAUSTED, exchanges, decisions);
            var captured = TurnTraceContext.metadata(); Future<AgentModel.Step> pending;
            if(captured!=null && !captured.traceId().equals(scope.scope().traceId())) return result(Status.STALE_SCOPE,exchanges,decisions);
            try {
                var history = List.copyOf(exchanges);
                pending = workers.submit(() -> {
                    try (var ignored = TurnTraceContext.restore(captured)) {
                        var available = new HashSet<>(scope.scope().allowedTools());
                        if (completed.contains(AgentTool.MEDICAL_KNOWLEDGE)) available.remove(AgentTool.MEDICAL_KNOWLEDGE);
                        return model.decide(input, intent, Set.copyOf(available), scope.scope().allowedDepartments(),
                                scope.scope().allowedQueries(),
                                history, (int)Math.max(1, TimeUnit.NANOSECONDS.toSeconds(remaining)));
                    }
                });
            } catch (RejectedExecutionException busy) { return result(Status.BUSY, exchanges, decisions); }
            final AgentModel.Step proposal;
            try { proposal = pending.get(remaining, TimeUnit.NANOSECONDS); }
            catch (TimeoutException timeout) { return result(Status.MODEL_TIMEOUT, exchanges, decisions); }
            catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt(); return result(Status.MODEL_FAILED, exchanges, decisions);
            } catch (Exception failure) { return result(Status.MODEL_FAILED, exchanges, decisions); }
            finally { pending.cancel(true); }
            if (!valid(scopeStillValid)) return result(Status.STALE_SCOPE, exchanges, decisions);
            if (scope.expired()) return result(Status.BUDGET_EXHAUSTED, exchanges, decisions);
            if (proposal == null || proposal.status() == null) return result(Status.MODEL_INVALID, exchanges, decisions);
            decisions.add(proposal);
            if (proposal.status() == AgentModel.Status.DONE) {
                if (!proposal.calls().isEmpty()) return result(Status.MODEL_INVALID, exchanges, decisions);
                return result(completed.containsAll(required) ? Status.COMPLETED : Status.MISSING_REQUIRED_TOOL, exchanges, decisions);
            }
            if (proposal.status() != AgentModel.Status.TOOLS) return result(Status.MODEL_FAILED, exchanges, decisions);
            if (proposal.calls().isEmpty() || proposal.calls().size() > 4)
                return result(Status.MODEL_INVALID, exchanges, decisions);
            // Validate the entire batch's identity and allowlist before executing its first read.
            try {
                for (var call : proposal.calls()) {
                    if (call == null || call.id() == null || !call.id().matches("[A-Za-z0-9_-]{1,128}")
                            || !callIds.add(call.id()) || call.arguments() == null || call.arguments().length() > 2400
                            || !scope.scope().allowedTools().contains(AgentTool.fromCode(call.name())))
                        return result(Status.MODEL_INVALID, exchanges, decisions);
                    if (completed.contains(AgentTool.MEDICAL_KNOWLEDGE)
                            && AgentTool.MEDICAL_KNOWLEDGE == AgentTool.fromCode(call.name()))
                        return result(Status.LOOP_DETECTED, exchanges, decisions);
                }
            } catch (IllegalArgumentException invalid) { return result(Status.MODEL_INVALID, exchanges, decisions); }
            if (callIds.size() > 4) return result(Status.BUDGET_EXHAUSTED, exchanges, decisions);
            var results = new ArrayList<String>(); boolean allCached = true;
            for (var call : proposal.calls()) {
                if (!valid(scopeStillValid)) return result(Status.STALE_SCOPE, exchanges, decisions);
                var executed = gateway.execute(scope, call.id(), call.name(), call.arguments());
                if (executed.status() != AgentToolGateway.Status.SUCCESS)
                    return result(executed.status() == AgentToolGateway.Status.BUDGET_EXHAUSTED ? Status.BUDGET_EXHAUSTED
                            : executed.status() == AgentToolGateway.Status.BLOCKED ? Status.TOOL_BLOCKED
                            : Status.TOOL_FAILED, exchanges, decisions);
                if (!valid(scopeStillValid)) return result(Status.STALE_SCOPE, exchanges, decisions);
                allCached &= executed.cached(); completed.add(AgentTool.fromCode(call.name()));
                contextCharacters += executed.content().length() + call.arguments().length();
                if (contextCharacters > 20000) return result(Status.CONTEXT_LIMIT, exchanges, decisions);
                results.add(executed.content());
            }
            exchanges.add(new AgentModel.Exchange(proposal.calls(), results));
            if (allCached) return result(Status.LOOP_DETECTED, exchanges, decisions);
        }
        return result(Status.BUDGET_EXHAUSTED, exchanges, decisions);
    }

    private boolean valid(BooleanSupplier check) { try { return check.getAsBoolean(); } catch (RuntimeException failure) { return false; } }
    private RunResult result(Status status, List<AgentModel.Exchange> transcript, List<AgentModel.Step> decisions) {
        return new RunResult(status, transcript, decisions);
    }
    @Override public void close() { workers.shutdownNow(); }
}
