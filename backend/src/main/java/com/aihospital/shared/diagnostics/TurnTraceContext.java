package com.aihospital.shared.diagnostics;

import java.util.UUID;

/** Scoped synchronous workflow correlation. Not inherited by other threads; no patient text. */
public final class TurnTraceContext implements AutoCloseable {
    private static final ThreadLocal<TurnTraceContext> CURRENT = new ThreadLocal<>();
    private final TurnTraceContext previous;
    private final String id;
    private final String userMessageId;
    private final String policyVersion;
    private final long started;
    private String route = "UNDECIDED";
    private boolean closed;
    public record Metadata(String traceId, String userMessageId, String route, String safetyPolicyVersion, long elapsedMs) {}
    private TurnTraceContext(String userMessageId, String policyVersion) {
        id = UUID.randomUUID().toString(); started = System.nanoTime();
        previous = CURRENT.get(); this.userMessageId = userMessageId; this.policyVersion = policyVersion;
        CURRENT.set(this);
    }
    private TurnTraceContext(Metadata snapshot) {
        previous = CURRENT.get(); id = snapshot.traceId(); userMessageId = snapshot.userMessageId();
        policyVersion = snapshot.safetyPolicyVersion(); route = snapshot.route();
        started = System.nanoTime() - java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(snapshot.elapsedMs());
        CURRENT.set(this);
    }
    /** Explicitly propagate a detached trace snapshot to bounded local worker execution. */
    public static TurnTraceContext restore(Metadata snapshot) {
        return snapshot == null ? null : new TurnTraceContext(snapshot);
    }
    public static TurnTraceContext open(String userMessageId, String policyVersion) { return new TurnTraceContext(userMessageId, policyVersion); }
    public static String currentId() { return CURRENT.get() == null ? null : CURRENT.get().id; }
    public static String currentOrNewId() { return currentId() == null ? UUID.randomUUID().toString() : currentId(); }
    public void route(String route) {
        if (!java.util.Set.of("GUIDANCE", "ASSESSMENT", "SAFETY", "POLICY_REFUSAL").contains(route)) throw new IllegalArgumentException("Unknown workflow branch");
        this.route = route;
    }
    public static Metadata metadata() {
        var context = CURRENT.get();
        return context == null ? null : new Metadata(context.id, context.userMessageId, context.route,
                context.policyVersion, Math.max(0, (System.nanoTime() - context.started) / 1_000_000));
    }
    @Override public void close() {
        if (closed) return;
        if (CURRENT.get() != this) throw new IllegalStateException("Trace scope closed out of order");
        if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
        closed = true;
    }
}
