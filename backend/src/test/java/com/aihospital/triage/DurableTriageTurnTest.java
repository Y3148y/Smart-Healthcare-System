package com.aihospital.triage;

import com.aihospital.triage.domain.*;
import com.aihospital.triage.infrastructure.mybatis.TriageMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import java.time.LocalDateTime;
import java.util.UUID;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class DurableTriageTurnTest {
    @Autowired TriageStore store;
    @Autowired TriageMapper mapper;
    @Autowired JdbcTemplate jdbc;
    private String session(){String id=UUID.randomUUID().toString();store.createSession(id,"durable-fixture",LocalDateTime.now());return id;}
    private void answer(TriageTurn t){store.completeTurn(t,()->{
        store.appendAssistantMessage(t.sessionId(),"Synthetic answer",null);
        store.updateSession(t.sessionId(),"Synthetic","Synthetic","待补充信息");
    });}
    @Test void identicalRequestsReplayButChangedContentAndConcurrentTurnsAreRejected(){
        String s=session(),key=UUID.randomUUID().toString();
        var first=store.beginTurn(s,key,"Synthetic question",180);
        assertEquals("CLAIMED",first.outcome());
        assertEquals("BUSY",store.beginTurn(s,key,"Synthetic question",180).outcome());
        assertEquals("CONFLICT",store.beginTurn(s,key,"Changed question",180).outcome());
        assertEquals("BUSY",store.beginTurn(s,UUID.randomUUID().toString(),"Other question",180).outcome());
        assertEquals(1,store.messages(s).size());
        answer(first.turn());
        assertEquals("REPLAY",store.beginTurn(s,key,"Synthetic question",180).outcome());
        assertEquals(2,store.messages(s).size());
        assertEquals("COMPLETED",store.turn(s,key).status());
        assertEquals(store.messages(s).get(1).id(),store.turn(s,key).assistantMessageId());
    }
    @Test void expiredWorkCannotWriteOrEraseTheNextTurn(){
        String s=session(),key=UUID.randomUUID().toString();
        var old=store.beginTurn(s,key,"Abandoned question",180).turn();
        jdbc.update("UPDATE triage_turn SET expires_at=started_at WHERE id=?",old.id());
        store.reconcileTurns(s);
        assertEquals("INTERRUPTED",store.turn(s,key).status());
        assertEquals("待重试",mapper.sessionStatus(s));
        assertEquals("INTERRUPTED",store.beginTurn(s,key,"Abandoned question",180).outcome());
        var next=store.beginTurn(s,UUID.randomUUID().toString(),"New question",180).turn();
        var writes=new java.util.concurrent.atomic.AtomicInteger();
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->store.completeTurn(old,writes::incrementAndGet));
        assertEquals(0,writes.get());
        store.failTurn(old,"TURN_PROCESSING_FAILED");
        assertEquals("处理中",mapper.sessionStatus(s));
        assertEquals(next.id(),store.latestTurn(s).id());
        answer(next);
    }
    @Test void partialFinalAnswerRollsBackWithCompletionState(){
        String s=session(),key=UUID.randomUUID().toString();
        var t=store.beginTurn(s,key,"Rollback question",180).turn();
        assertThrows(IllegalStateException.class,()->store.completeTurn(t,()->{
            store.appendAssistantMessage(s,"Must rollback",null);
            store.updateSession(s,"bad","bad","已完成分诊");
            throw new IllegalStateException("Synthetic persistence failure");
        }));
        assertEquals(1,store.messages(s).size());
        assertEquals("RUNNING",store.turn(s,key).status());
        assertEquals("处理中",mapper.sessionStatus(s));
        store.failTurn(t,"TURN_PROCESSING_FAILED");
        assertEquals("FAILED",store.turn(s,key).status());
        assertEquals("待重试",mapper.sessionStatus(s));
    }
    @Test void legacyProcessingRecordsBecomeRetryableWithoutInventingAnAnswerOrTurn(){
        String s=session();store.appendMessage(s,"USER","Legacy question");
        store.updateSession(s,"Legacy","Legacy question","处理中");
        store.reconcileTurns(s);
        assertEquals("待重试",mapper.sessionStatus(s));
        assertEquals(1,store.messages(s).size());assertNull(store.latestTurn(s));
    }
    @Test void independentDatabaseTransactionsOnlyClaimOneActiveTurn() throws Exception {
        String s=session();var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
        try{
            Callable<TriageTurn.Claim> task=()->{start.await();return store.beginTurn(s,UUID.randomUUID().toString(),"Concurrent question",180);};
            var a=pool.submit(task);var b=pool.submit(task);start.countDown();
            var claims=java.util.List.of(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS));
            assertEquals(1,claims.stream().filter(c->"CLAIMED".equals(c.outcome())).count());
            assertEquals(1,claims.stream().filter(c->"BUSY".equals(c.outcome())).count());
            assertEquals(1,store.messages(s).size());
            answer(claims.stream().filter(c->"CLAIMED".equals(c.outcome())).findFirst().orElseThrow().turn());
        }finally{pool.shutdownNow();}
    }
}
