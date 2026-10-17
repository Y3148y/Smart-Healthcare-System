package com.aihospital.observation;

import com.aihospital.observation.api.TechnicalCallView;
import com.aihospital.observation.domain.CallLogStore;
import com.aihospital.observation.infrastructure.mybatis.CallLogMapper;
import com.aihospital.shared.model.Models.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class TokenUsagePersistenceTest {
    @Autowired CallLogStore calls;
    @Autowired CallLogMapper mapper;
    @Autowired JdbcTemplate jdbc;
    private String id(){return UUID.randomUUID().toString();}
    @Test void nullableAndZeroUsageAndTheirOriginSurviveDatabaseRoundTrip(){
        String trace=id();
        for(var usage:List.of(TokenUsage.reported(0,null),TokenUsage.reported(null,11),TokenUsage.reported(null,null),TokenUsage.local())){
            String id=id();calls.record(new CallLog(id,LocalDateTime.now(),"意图抽取","synthetic","synthetic",0,0,1,true,List.of(),trace,usage));
            var loaded=calls.calls(trace).stream().filter(c->c.id().equals(id)).findFirst().orElseThrow();
            assertEquals(usage,loaded.usage());
            assertEquals(usage.inputTokens(),TechnicalCallView.from(loaded).inputTokens());
        }
    }
    @Test void oldWriterWithoutUsageMetadataIsNotReclassifiedAsMeasured(){
        String id=id(),trace=id();mapper.insert(id,LocalDateTime.now(),"意图抽取","synthetic","synthetic",0,0,1,false,"[]");mapper.insertTrace(id,trace);
        var view=TechnicalCallView.from(calls.calls(trace).get(0));
        assertNull(view.inputTokens());assertNull(view.outputTokens());assertEquals("LEGACY_RECORDED",view.tokenUsageSource());
    }
    @Test void invalidTraceWriteRollsBackBaseLogAndUsageTogether(){
        String id=id(),trace=id();
        // An overlong trace association is rejected; no partial log or usage child may remain.
        assertThrows(RuntimeException.class,()->calls.record(new CallLog(id,LocalDateTime.now(),"意图抽取","synthetic","synthetic",0,0,1,true,
                List.of(new ToolTrace("medical_knowledge_retrieve","synthetic","synthetic","synthetic",1,true,"")),"x".repeat(1000),TokenUsage.reported(0,0))));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM agent_call_log WHERE id=?",Integer.class,id));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM agent_call_usage WHERE call_id=?",Integer.class,id));
        assertTrue(calls.calls(trace).isEmpty());
    }
    @Test void malformedStoredMetadataDoesNotMasqueradeAsKnownZero(){
        String id=id(),trace=id();calls.record(new CallLog(id,LocalDateTime.now(),"意图抽取","synthetic","synthetic",0,0,1,true,List.of(),trace,TokenUsage.reported(null,null)));
        jdbc.update("UPDATE agent_call_usage SET usage_json=? WHERE call_id=?","{\"source\":\"NOT_REPORTED\",\"inputTokens\":42}",id);
        try{assertThrows(IllegalStateException.class,()->calls.calls(trace));}
        finally{jdbc.update("DELETE FROM agent_call_usage WHERE call_id=?",id);}
    }
}
