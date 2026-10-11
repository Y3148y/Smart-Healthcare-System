package com.aihospital.tools.domain;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;

/** Server-owned, single-turn scope. Never deserialize this class from a model or HTTP request. */
public final class AgentToolRun {
    public record Scope(String patient, String sessionId, String traceId, long stateVersion,
                        String corpusVersion, Set<AgentTool> allowedTools, Set<String> allowedDepartments) {
        public Scope {
            if (patient == null || patient.isBlank() || sessionId == null || sessionId.isBlank()
                    || traceId == null || traceId.isBlank() || corpusVersion == null || corpusVersion.isBlank()
                    || stateVersion < 0) throw new IllegalArgumentException("Invalid server tool scope");
            allowedTools = Set.copyOf(allowedTools);
            allowedDepartments = Set.copyOf(allowedDepartments);
        }
    }
    private final Scope scope;
    private final int maxCalls;
    private final long totalNanos, singleToolNanos, started;
    private final LongSupplier clock;
    private final Map<String, String> results = new HashMap<>();
    private final Map<String, String> callSignatures = new HashMap<>();
    private int calls;

    public AgentToolRun(Scope scope, int maxCalls, java.time.Duration total, java.time.Duration singleTool) {
        this(scope, maxCalls, total, singleTool, System::nanoTime);
    }
    public AgentToolRun(Scope scope, int maxCalls, java.time.Duration total, java.time.Duration singleTool, LongSupplier clock) {
        if (maxCalls < 1 || maxCalls > 8 || total.isZero() || total.isNegative() || total.compareTo(java.time.Duration.ofSeconds(120)) > 0
                || singleTool.isZero() || singleTool.isNegative() || singleTool.compareTo(total) > 0)
            throw new IllegalArgumentException("Invalid tool execution budget");
        this.scope = java.util.Objects.requireNonNull(scope); this.maxCalls = maxCalls;
        this.totalNanos = total.toNanos(); this.singleToolNanos = singleTool.toNanos();
        this.clock = java.util.Objects.requireNonNull(clock); this.started = clock.getAsLong();
    }
    public Scope scope() { return scope; }
    public synchronized long reserveCall() {
        long remaining = totalNanos - Math.max(0, clock.getAsLong() - started);
        if (calls >= maxCalls || remaining <= 0) throw new IllegalStateException("TOOL_BUDGET_EXHAUSTED");
        calls++;
        return Math.min(remaining, singleToolNanos);
    }
    public synchronized boolean expired() { return clock.getAsLong() - started >= totalNanos; }
    public synchronized int calls() { return calls; }
    public synchronized String cached(String key) { return results.get(key); }
    public synchronized void remember(String key, String value) { results.put(key, value); }
    public synchronized boolean bindCall(String callId, String signature) {
        String previous = callSignatures.putIfAbsent(callId, signature);
        return previous == null || previous.equals(signature);
    }
}
