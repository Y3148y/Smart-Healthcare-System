package com.aihospital.triage.api;

import com.aihospital.shared.security.RoleGuard;
import com.aihospital.shared.model.Models.SendMessage;
import com.aihospital.triage.application.TriageConversationService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;
import org.springframework.web.server.ResponseStatusException;
import jakarta.annotation.PreDestroy;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** Authenticated POST SSE: real progress followed by one validated persisted conversation. */
@RestController @RequestMapping("/api/triage/sessions")
public class TriageStreamController {
    private final TriageConversationService service;private final RoleGuard guard;
    private final ThreadPoolExecutor workers=new ThreadPoolExecutor(4,4,0,TimeUnit.MILLISECONDS,
        new SynchronousQueue<>(),r->{var t=new Thread(r,"triage-progress");t.setDaemon(true);return t;},new ThreadPoolExecutor.AbortPolicy());
    private final java.util.Set<String> inFlight=ConcurrentHashMap.newKeySet();
    public TriageStreamController(TriageConversationService service,RoleGuard guard){this.service=service;this.guard=guard;}
    @PreDestroy public void stop(){workers.shutdown();}
    @PostMapping(value="/{id}/turns/stream",produces=MediaType.TEXT_EVENT_STREAM_VALUE)
    public SseEmitter send(@PathVariable String id,@RequestBody SendMessage request,
            @RequestHeader(value="Authorization",required=false)String auth){
        String patient=guard.require(auth,"PATIENT").subject();
        service.requireOwner(id,patient);
        String text=request==null?null:request.content();
        if(text==null||text.isBlank()||text.trim().length()>2000)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"症状描述应为1至2000字");
        if(!inFlight.add(id))throw new ResponseStatusException(HttpStatus.CONFLICT,"该会话正在处理，请等待或刷新会话核对");
        var emitter=new SseEmitter(120000L);var disconnected=new AtomicBoolean(false);
        emitter.onCompletion(()->disconnected.set(true));emitter.onTimeout(()->{disconnected.set(true);emitter.complete();});emitter.onError(e->disconnected.set(true));
        try{workers.execute(()->{
            try{
                event(emitter,disconnected,"status",Map.of("stage","ACCEPTED"));
                var result=service.send(id,patient,text,stage->event(emitter,disconnected,"status",Map.of("stage",stage.name())));
                event(emitter,disconnected,"result",result);emitter.complete();
            }catch(Exception ex){
                String message=ex instanceof ResponseStatusException r&&r.getReason()!=null?r.getReason():"本次处理未完成，请刷新会话核对保存状态";
                event(emitter,disconnected,"failure",Map.of("message",message));emitter.complete();
            }finally{inFlight.remove(id);}
        });}catch(RejectedExecutionException ex){inFlight.remove(id);throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,"问诊服务繁忙，请稍后重试");}
        return emitter;
    }
    @ExceptionHandler(ResponseStatusException.class) public org.springframework.http.ResponseEntity<Map<String,String>> rejected(ResponseStatusException ex){return org.springframework.http.ResponseEntity.status(ex.getStatusCode()).contentType(MediaType.APPLICATION_JSON).body(Map.of("message",ex.getReason()==null?"请求被拒绝":ex.getReason()));}
    private static void event(SseEmitter emitter,AtomicBoolean disconnected,String name,Object value){
        if(disconnected.get())return;
        try{emitter.send(SseEmitter.event().name(name).data(value,MediaType.APPLICATION_JSON));}
        catch(Exception ex){disconnected.set(true);}
    }
}
