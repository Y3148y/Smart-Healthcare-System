package com.aihospital.triage;
import com.aihospital.triage.domain.*;
import java.time.LocalDateTime;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
/** Isolated application tests exercise orchestration; database fencing is tested separately. */
public final class TurnStoreStub {
    public static void configure(TriageStore store) {
        var content = new java.util.concurrent.atomic.AtomicReference<String>();
        when(store.beginTurn(anyString(),anyString(),anyString(),anyInt())).thenAnswer(i -> {
            String session=i.getArgument(0),key=i.getArgument(1),text=i.getArgument(2);
            content.set(text);store.appendMessage(session,"USER",text);
            store.updateSession(session,text.substring(0,Math.min(24,text.length())),text.substring(0,Math.min(120,text.length())),"处理中");
            var now=LocalDateTime.now();
            return new TriageTurn.Claim(new TriageTurn("turn",session,key,"RUNNING","user",null,now,now.plusMinutes(3),null,null),"CLAIMED");
        });
        doAnswer(i->{((Runnable)i.getArgument(1)).run();return null;}).when(store).completeTurn(any(),any());
        doAnswer(i->{TriageTurn t=i.getArgument(0);String text=content.get();
            store.updateSession(t.sessionId(),text.substring(0,Math.min(24,text.length())),text.substring(0,Math.min(120,text.length())),"待重试");return null;})
                .when(store).failTurn(any(),anyString());
    }
}
