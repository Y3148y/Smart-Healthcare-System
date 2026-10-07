package com.aihospital.shared.diagnostics;

import java.util.UUID;

/** Scoped synchronous workflow correlation. Not inherited by other threads; no patient text. */
public final class TurnTraceContext implements AutoCloseable {
    private static final ThreadLocal<TurnTraceContext> CURRENT = new ThreadLocal<>();
    private final TurnTraceContext previous;
    private final String id = UUID.randomUUID().toString();
    private final String userMessageId;
    private final String policyVersion;
    private final long started = System.nanoTime();
    private String route = "UNDECIDED";
    private boolean closed;
    public record Metadata(String traceId, String userMessageId, String route, String safetyPolicyVersion, long elapsedMs) {}
    private TurnTraceContext(String userMessageId, String policyVersion) {
        previous = CURRENT.get(); this.userMessageId = userMessageId; this.policyVersion = policyVersion;
        CURRENT.set(this);
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
