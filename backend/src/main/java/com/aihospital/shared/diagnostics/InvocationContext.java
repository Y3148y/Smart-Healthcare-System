package com.aihospital.shared.diagnostics;

/** Server-owned invocation origin, not a role or permission. Never accepted from model/user input. */
public final class InvocationContext implements AutoCloseable {
    public enum Origin { WORKFLOW, AGENT, ADMIN, MCP, UNKNOWN }
    private static final ThreadLocal<InvocationContext> CURRENT=new ThreadLocal<>();
    private final InvocationContext previous;
    private final Origin origin;
    private boolean closed;
    private InvocationContext(Origin origin){
        this.origin=java.util.Objects.requireNonNull(origin);previous=CURRENT.get();CURRENT.set(this);
    }
    public static InvocationContext open(Origin origin){return new InvocationContext(origin);}
    public static String current(){var scope=CURRENT.get();return scope==null?Origin.UNKNOWN.name():scope.origin.name();}
    public static String checked(String origin){
        if(origin==null)return Origin.UNKNOWN.name();
        try{return Origin.valueOf(origin).name();}
        catch(IllegalArgumentException invalid){throw new IllegalArgumentException("Invalid invocation origin");}
    }
    @Override public void close(){
        if(closed)return;
        if(CURRENT.get()!=this)throw new IllegalStateException("Invocation scope closed out of order");
        if(previous==null)CURRENT.remove();else CURRENT.set(previous);
        closed=true;
    }
}
