package com.aihospital.shared.diagnostics;

import com.aihospital.shared.model.Models.*;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class InvocationContextTest {
    @Test void originsAreNestedAndRestoredOnFailureWithoutLeaking(){
        assertEquals("UNKNOWN",InvocationContext.current());
        try(var workflow=InvocationContext.open(InvocationContext.Origin.WORKFLOW)){
            assertEquals("WORKFLOW",InvocationContext.current());
            assertThrows(IllegalStateException.class,()->{
                try(var agent=InvocationContext.open(InvocationContext.Origin.AGENT)){
                    assertEquals("AGENT",new CallLog("synthetic",LocalDateTime.now(),"test","synthetic","test",0,0,1,true,List.of()).initiator());
                    throw new IllegalStateException("Synthetic failure");
                }
            });
            assertEquals("WORKFLOW",InvocationContext.current());
        }
        assertEquals("UNKNOWN",InvocationContext.current());
    }
    @Test void oldSerializedOriginStaysUnknownEvenInsideAnActiveInvocation(){
        try(var invocation=InvocationContext.open(InvocationContext.Origin.AGENT)){
            assertEquals("UNKNOWN",new CallLog("legacy",LocalDateTime.now(),"test","synthetic","test",0,0,1,true,List.of(),null,null,null).initiator());
            assertEquals("UNKNOWN",new TurnTraceContext.Execution("TOOL","synthetic","SUCCESS",1,null,null,null,null,null).initiator());
            assertEquals("AGENT",new TurnTraceContext.Execution("TOOL","synthetic","SUCCESS",1,null,null,null,null).initiator());
        }
    }
    @Test void callersCannotSmuggleArbitraryTextAsAnOrigin(){
        assertThrows(IllegalArgumentException.class,()->InvocationContext.checked("patient-provided-text"));
    }
    @Test void contextDoesNotImplicitlyPropagateToAnotherThread()throws Exception{
        var value=new java.util.concurrent.atomic.AtomicReference<String>();
        try(var invocation=InvocationContext.open(InvocationContext.Origin.AGENT)){
            var worker=new Thread(()->value.set(InvocationContext.current()));worker.start();worker.join(5000);
            assertFalse(worker.isAlive());assertEquals("UNKNOWN",value.get());
        }
    }
}
