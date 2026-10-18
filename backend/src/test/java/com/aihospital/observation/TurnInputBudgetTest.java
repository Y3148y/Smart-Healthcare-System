package com.aihospital.observation;

import com.aihospital.shared.diagnostics.TurnTraceContext;
import org.junit.jupiter.api.Test;
import java.util.concurrent.*;
import static com.aihospital.shared.diagnostics.TurnTraceContext.Reservation.*;
import static org.junit.jupiter.api.Assertions.*;

class TurnInputBudgetTest {
    private TurnTraceContext.Execution estimate(int size){
        return new TurnTraceContext.Execution("CONTEXT","SYNTHETIC","BOUNDED_ESTIMATE",0,null,null,null,null,"WORKFLOW",
                new TurnTraceContext.ContextBudget(size,2000,0,0,"UTF8_BYTES_WITH_FRAMING"));
    }
    @Test void reservationsAccumulateWithoutBeingWidenedByLaterCallers() throws Exception {
        try(var scope=TurnTraceContext.open("u1","policy")){
            assertEquals(ACCEPTED,TurnTraceContext.reserveContext(estimate(400),600));
            assertEquals(OVER_BUDGET,TurnTraceContext.reserveContext(estimate(400),1000));
            assertEquals(1,TurnTraceContext.metadata().executions().size());
            assertEquals(400,TurnTraceContext.metadata().executions().get(0).contextBudget().turnInputReserved());
            var json=new com.fasterxml.jackson.databind.ObjectMapper();
            var restored=json.readValue(json.writeValueAsString(TurnTraceContext.metadata()),TurnTraceContext.Metadata.class);
            assertEquals(600,restored.executions().get(0).contextBudget().turnInputBudget());
            assertEquals(400,restored.executions().get(0).contextBudget().turnInputReserved());
        }
        try(var scope=TurnTraceContext.open("u2","policy")){
            assertEquals(ACCEPTED,TurnTraceContext.reserveContext(estimate(400),600));
        }
    }
    @Test void restoredWorkersShareAtomicReservations() throws Exception {
        try(var scope=TurnTraceContext.open("u","policy")){
            var snapshot=TurnTraceContext.metadata();var start=new CountDownLatch(1);
            var workers=Executors.newFixedThreadPool(2);
            try{
                Callable<TurnTraceContext.Reservation> reserve=()->{try(var child=TurnTraceContext.restore(snapshot)){
                    start.await();return TurnTraceContext.reserveContext(estimate(400),600);}};
                var first=workers.submit(reserve);var second=workers.submit(reserve);start.countDown();
                var results=java.util.List.of(first.get(5,TimeUnit.SECONDS),second.get(5,TimeUnit.SECONDS));
                assertEquals(1,results.stream().filter(value->value==ACCEPTED).count());
                assertEquals(1,results.stream().filter(value->value==OVER_BUDGET).count());
                assertEquals(1,TurnTraceContext.metadata().executions().size());
            }finally{workers.shutdownNow();}
        }
    }
    @Test void fullTrackingDoesNotPretendThatInputQuotaWasExceeded(){
        try(var scope=TurnTraceContext.open("u","policy")){
            for(int i=0;i<64;i++)TurnTraceContext.record(new TurnTraceContext.Execution("TOOL","synthetic","DONE",0,null,null,null,null));
            assertEquals(TRACKING_UNAVAILABLE,TurnTraceContext.reserveContext(estimate(400),1000));
        }
    }
    @Test void immutableHistoricalMetadataCanBeRestoredWithoutMutatingItsOriginalList(){
        var original=java.util.List.of(estimate(300));
        var snapshot=new TurnTraceContext.Metadata("id","u","GUIDANCE","policy",1,original);
        try(var scope=TurnTraceContext.restore(snapshot)){
            assertEquals(ACCEPTED,TurnTraceContext.reserveContext(estimate(300),1000));
            assertEquals(2,TurnTraceContext.metadata().executions().size());assertEquals(1,original.size());
        }
    }
    @Test void invalidReservationLimitsAreRejected(){
        assertThrows(IllegalArgumentException.class,()->TurnTraceContext.reserveContext(estimate(400),511));
    }
}
