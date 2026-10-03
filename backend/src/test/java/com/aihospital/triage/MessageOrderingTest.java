package com.aihospital.triage;

import com.aihospital.triage.infrastructure.mybatis.MybatisTriageStore;
import com.aihospital.triage.infrastructure.mybatis.TriageMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.ArrayList;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class MessageOrderingTest {
    @Autowired MybatisTriageStore store;
    @Autowired TriageMapper mapper;

    private String session() {
        String id=UUID.randomUUID().toString();
        store.createSession(id,"ordering-test",LocalDateTime.now());
        return id;
    }

    @Test void equalTimestampsUsePersistentOrderNotRoleOrUuid() {
        String s=session();
        var time=LocalDateTime.of(2026,10,3,12,0);
        String[] ids={"z-","a-","y-","b-"};
        String[] roles={"USER","ASSISTANT","USER","ASSISTANT"};
        for(int i=0;i<4;i++) {
            ids[i]+=s;
            mapper.insertMessage(ids[i],s,roles[i],"turn-"+i,time);
            mapper.insertMessageOrder(ids[i],s,i+1);
        }
        assertEquals(java.util.List.of(ids),store.messages(s).stream().map(m->m.id()).toList());
        assertEquals(java.util.List.of("turn-0","turn-1","turn-2","turn-3"),
                store.messages(s).stream().map(m->m.content()).toList());
    }

    @Test void legacyMessagesRemainBeforeNewTurnsAndSimpleTiePutsUserFirst() {
        String s=session();
        var time=LocalDateTime.of(2026,10,3,12,0);
        mapper.insertMessage("a-"+s,s,"ASSISTANT","legacy reply",time);
        mapper.insertMessage("z-"+s,s,"USER","legacy question",time);
        store.appendMessage(s,"USER","new question");
        store.appendAssistantMessage(s,"new reply",null);
        assertEquals(java.util.List.of("legacy question","legacy reply","new question","new reply"),
                store.messages(s).stream().map(m->m.content()).toList());
    }

    @Test void concurrentWritersAllocateUniqueSequences() throws Exception {
        String s=session();
        var executor=Executors.newFixedThreadPool(4);
        try {
            var futures=new ArrayList<Future<String>>();
            for(int i=0;i<12;i++) {
                final int n=i;
                futures.add(executor.submit(()->store.appendMessage(s,"USER","parallel-"+n)));
            }
            for(var future:futures) assertNotNull(future.get(15,TimeUnit.SECONDS));
            assertEquals(12,store.messages(s).size());
            assertEquals(13,mapper.nextMessageSequence(s));
            assertEquals(12,store.messages(s).stream().map(m->m.id()).distinct().count());
        } finally { executor.shutdownNow(); }
    }
}
