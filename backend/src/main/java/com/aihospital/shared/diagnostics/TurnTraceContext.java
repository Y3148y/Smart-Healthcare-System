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
    private java.util.List<Execution> executions = new java.util.concurrent.CopyOnWriteArrayList<>();
    /** Technical summaries only; no tool arguments, patient text or provider error bodies. */
    public record ContextBudget(int estimatedInputTokens,int inputTokenBudget,int historyMessagesRemoved,
                                int referencesRemoved,String estimator,Integer turnInputBudget,Integer turnInputReserved){
        public ContextBudget(int estimatedInputTokens,int inputTokenBudget,int historyMessagesRemoved,int referencesRemoved,String estimator){
            this(estimatedInputTokens,inputTokenBudget,historyMessagesRemoved,referencesRemoved,estimator,null,null);
        }
        public ContextBudget{
            if(estimatedInputTokens<0||inputTokenBudget<512||estimatedInputTokens>inputTokenBudget
                    ||historyMessagesRemoved<0||referencesRemoved<0||!"UTF8_BYTES_WITH_FRAMING".equals(estimator))
                throw new IllegalArgumentException("Invalid context budget metadata");
            if((turnInputBudget==null)!=(turnInputReserved==null)||turnInputBudget!=null
                    &&(turnInputBudget<512||turnInputBudget>1048576||turnInputReserved<estimatedInputTokens||turnInputReserved>turnInputBudget))
                throw new IllegalArgumentException("Invalid turn budget metadata");
        }
    }
    public record Execution(String kind, String name, String status, long elapsedMs,
                            Boolean rrfUsed, Boolean rerankEnabled, Boolean rerankUsed,
                            Integer selectedCount,String initiator,ContextBudget contextBudget) {
        public Execution {initiator=InvocationContext.checked(initiator);}
        public Execution(String kind,String name,String status,long elapsedMs,Boolean rrfUsed,Boolean rerankEnabled,
                         Boolean rerankUsed,Integer selectedCount,String initiator){
            this(kind,name,status,elapsedMs,rrfUsed,rerankEnabled,rerankUsed,selectedCount,initiator,null);
        }
        public Execution(String kind,String name,String status,long elapsedMs,Boolean rrfUsed,Boolean rerankEnabled,
                         Boolean rerankUsed,Integer selectedCount){
            this(kind,name,status,elapsedMs,rrfUsed,rerankEnabled,rerankUsed,selectedCount,InvocationContext.current());
        }
    }
    public record Metadata(String traceId, String userMessageId, String route, String safetyPolicyVersion,
                           long elapsedMs, java.util.List<Execution> executions) {}
    private TurnTraceContext(String userMessageId, String policyVersion) {
        id = UUID.randomUUID().toString(); started = System.nanoTime();
        previous = CURRENT.get(); this.userMessageId = userMessageId; this.policyVersion = policyVersion;
        CURRENT.set(this);
    }
    private TurnTraceContext(Metadata snapshot) {
        previous = CURRENT.get(); id = snapshot.traceId(); userMessageId = snapshot.userMessageId();
        policyVersion = snapshot.safetyPolicyVersion(); route = snapshot.route();
        if(snapshot.executions()!=null)executions=snapshot.executions() instanceof java.util.concurrent.CopyOnWriteArrayList<?>
                ?snapshot.executions():new java.util.concurrent.CopyOnWriteArrayList<>(snapshot.executions());
        started = System.nanoTime() - java.util.concurrent.TimeUnit.MILLISECONDS.toNanos(snapshot.elapsedMs());
        CURRENT.set(this);
    }
    /** Explicitly propagate a detached trace snapshot to bounded local worker execution. */
    public static TurnTraceContext restore(Metadata snapshot) {
        return snapshot == null ? null : new TurnTraceContext(snapshot);
    }
    public static TurnTraceContext open(String userMessageId, String policyVersion) { return new TurnTraceContext(userMessageId, policyVersion); }
    public static String currentId() { return CURRENT.get() == null ? null : CURRENT.get().id; }
    public static void record(Execution execution) {
        var context = CURRENT.get();
        if(context!=null)synchronized(context.executions){if(context.executions.size()<64)context.executions.add(execution);}
    }
    /** Atomic live-turn reservation shared with explicitly restored workers; not billing or a durable quota. */
    public enum Reservation { ACCEPTED, OVER_BUDGET, TRACKING_UNAVAILABLE }
    public static Reservation reserveContext(Execution execution,int maximum){
        if(!"CONTEXT".equals(execution.kind())||execution.contextBudget()==null||maximum<512||maximum>1048576)
            throw new IllegalArgumentException("Invalid turn reservation");
        var context=CURRENT.get();
        if(context==null)return Reservation.ACCEPTED; // Standalone adapters retain per-call bounds, without a turn-wide claim.
        synchronized(context.executions){
            long total=execution.contextBudget().estimatedInputTokens();
            int effective=maximum;
            for(var existing:context.executions)if("CONTEXT".equals(existing.kind())&&existing.contextBudget()!=null){
                total+=existing.contextBudget().estimatedInputTokens();
                if(existing.contextBudget().turnInputBudget()!=null)effective=Math.min(effective,existing.contextBudget().turnInputBudget());
            }
            if(total>effective)return Reservation.OVER_BUDGET;
            if(context.executions.size()>=64)return Reservation.TRACKING_UNAVAILABLE;
            var b=execution.contextBudget();
            var reserved=new ContextBudget(b.estimatedInputTokens(),b.inputTokenBudget(),b.historyMessagesRemoved(),
                    b.referencesRemoved(),b.estimator(),effective,(int)total);
            context.executions.add(new Execution(execution.kind(),execution.name(),execution.status(),execution.elapsedMs(),
                    execution.rrfUsed(),execution.rerankEnabled(),execution.rerankUsed(),execution.selectedCount(),execution.initiator(),reserved));
            return Reservation.ACCEPTED;
        }
    }
    public static String currentOrNewId() { return currentId() == null ? UUID.randomUUID().toString() : currentId(); }
    public void route(String route) {
        if (!java.util.Set.of("GUIDANCE", "ASSESSMENT", "SAFETY", "POLICY_REFUSAL").contains(route)) throw new IllegalArgumentException("Unknown workflow branch");
        this.route = route;
    }
    public static Metadata metadata() {
        var context = CURRENT.get();
        return context == null ? null : new Metadata(context.id, context.userMessageId, context.route,
                context.policyVersion, Math.max(0, (System.nanoTime() - context.started) / 1_000_000), context.executions);
    }
    @Override public void close() {
        if (closed) return;
        if (CURRENT.get() != this) throw new IllegalStateException("Trace scope closed out of order");
        if (previous == null) CURRENT.remove(); else CURRENT.set(previous);
        closed = true;
    }
}
