package com.aihospital.observation;

import com.aihospital.observation.domain.CallLogStore;
import com.aihospital.shared.diagnostics.InvocationContext;
import com.aihospital.shared.model.Models.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class InvocationOriginPersistenceTest {
    @Autowired CallLogStore calls;
    @Test void originRemainsAttachedToItsCallAfterTheScopeEnds(){
        for(var origin:InvocationContext.Origin.values()){
            String id=UUID.randomUUID().toString(),trace=UUID.randomUUID().toString();
            try(var scope=InvocationContext.open(origin)){
                calls.record(new CallLog(id,LocalDateTime.now(),"意图抽取","synthetic","synthetic",0,0,1,true,List.of(),trace,TokenUsage.local()));
            }
            assertEquals("UNKNOWN",InvocationContext.current());
            assertEquals(origin.name(),calls.calls(trace).get(0).initiator());
        }
    }
}
