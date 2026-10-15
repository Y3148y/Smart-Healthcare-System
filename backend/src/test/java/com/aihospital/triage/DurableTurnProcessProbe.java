package com.aihospital.triage;

import com.aihospital.HospitalApplication;
import com.aihospital.triage.domain.TriageStore;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import java.time.LocalDateTime;
import java.util.UUID;

/** Child JVM fixture for crash testing; never enabled by application startup. */
public final class DurableTurnProcessProbe {
    public static void main(String[] args) throws Exception {
        try(var app=new SpringApplicationBuilder(HospitalApplication.class).web(WebApplicationType.NONE).run()){
            TriageStore store=app.getBean(TriageStore.class);
            if("begin".equals(args[0])){
                String session=UUID.randomUUID().toString();
                store.createSession(session,"process-restart-fixture",LocalDateTime.now());
                var claim=store.beginTurn(session,args[1],"Synthetic interrupted question",180);
                if(!"CLAIMED".equals(claim.outcome()))throw new IllegalStateException("Synthetic claim failed");
                System.out.println("TURN_READY "+session);System.out.flush();
                Thread.sleep(120000); // parent forcibly stops this JVM after the committed claim
            }else{
                String session=args[1],key=args[2];
                store.reconcileTurns(session);
                if(!"INTERRUPTED".equals(store.turn(session,key).status()))throw new IllegalStateException("Unreconciled turn");
                if(!"INTERRUPTED".equals(store.beginTurn(session,key,"Synthetic interrupted question",180).outcome()))
                    throw new IllegalStateException("Interrupted request was replayed");
                if(store.messages(session).size()!=1)throw new IllegalStateException("Unexpected replayed message");
                var next=store.beginTurn(session,UUID.randomUUID().toString(),"Synthetic new question",180).turn();
                store.completeTurn(next,()->{
                    store.appendAssistantMessage(session,"Synthetic recovered answer",null);
                    store.updateSession(session,"Synthetic","Synthetic","待补充信息");
                });
                if(store.messages(session).size()!=3 || !"COMPLETED".equals(store.latestTurn(session).status()))
                    throw new IllegalStateException("Recovery result missing");
                System.out.println("TURN_RECOVERED");System.out.flush();
            }
        }
    }
}
