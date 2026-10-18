package com.aihospital.triage.infrastructure.llm;

import com.aihospital.shared.model.Models.DepartmentCandidate;
import com.aihospital.triage.domain.NarrationModel.Turn;
import com.aihospital.shared.diagnostics.TurnTraceContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.*;
import static org.junit.jupiter.api.Assertions.*;

class StructuredDecisionBoundaryTest {
    private final List<DepartmentCandidate> candidates=List.of(new DepartmentCandidate("全科医学科","CANDIDATE_OUTSIDE_SECRET",null));
    private record Provider(HttpServer server,AtomicReference<String> request,AtomicInteger calls) implements AutoCloseable {
        public void close(){server.stop(0);}
        String base(){return "http://127.0.0.1:"+server.getAddress().getPort()+"/v1";}
    }
    private Provider provider(int status,String finish) throws Exception {
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        var request=new AtomicReference<String>();var calls=new AtomicInteger();
        server.createContext("/v1/chat/completions",exchange->{
            calls.incrementAndGet();request.set(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            String decision="{\"department\":\"全科医学科\",\"basis\":\"仅合成测试输入\",\"confidence\":60}";
            var body=status==200?Map.of("id","fixture","object","chat.completion","model","fixture",
                    "choices",List.of(Map.of("index",0,"finish_reason",finish,"message",Map.of("role","assistant","content",decision))),
                    "usage",Map.of("prompt_tokens",17,"completion_tokens",7,"total_tokens",24))
                    :Map.of("error",Map.of("message","PRIVATE_PROVIDER_ERROR","type","fixture"));
            byte[] bytes=new ObjectMapper().writeValueAsBytes(body);
            exchange.getResponseHeaders().set("Content-Type","application/json; charset=utf-8");
            exchange.sendResponseHeaders(status,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });server.start();return new Provider(server,request,calls);
    }
    @Test void onlyCompleteRecentWindowAndCandidateNamesReachTheProvider() throws Exception {
        try(var provider=provider(200,"stop")){
            var model=new StructuredDecisionModel("openai-compatible","synthetic-key",provider.base(),"fixture",3);
            var history=new ArrayList<Turn>();
            for(int i=0;i<8;i++){history.add(new Turn("USER",i==0?"HISTORY_OUTSIDE_SECRET":"synthetic "+i));history.add(new Turn("ASSISTANT","question "+i));}
            String current="x".repeat(450)+" CURRENT_END_MARKER";history.add(new Turn("USER",current));
            var result=model.propose("ACCUMULATED_OUTSIDE_SECRET",candidates,history);
            assertEquals(StructuredDecisionModel.Proposal.ACCEPTED,result.status());
            assertEquals(17,result.inputTokens());assertEquals(7,result.outputTokens());assertEquals(true,result.providerRequestAttempted());
            var text=provider.request().get();assertTrue(text.contains("CURRENT_END_MARKER"));assertTrue(text.contains("question 7"));
            for(String marker:List.of("HISTORY_OUTSIDE_SECRET","ACCUMULATED_OUTSIDE_SECRET","CANDIDATE_OUTSIDE_SECRET"))assertFalse(text.contains(marker));
            var messages=new ObjectMapper().readTree(text).get("messages");int users=0,characters=0;
            assertFalse(messages.get(0).path("content").asText().contains("全科医学科"));
            assertTrue(messages.get(2).path("content").asText().contains("<routing_options>"));
            for(int i=3;i<messages.size();i++){var message=messages.get(i);if("user".equals(message.path("role").asText()))users++;characters+=message.path("content").asText().length();}
            assertTrue(users<=5);assertTrue(characters<=4000);
        }
    }
    @Test void truncatedOutputCannotBeAcceptedEvenWithACompleteJsonObject() throws Exception {
        try(var provider=provider(200,"length")){
            var result=new StructuredDecisionModel("openai-compatible","synthetic-key",provider.base(),"fixture",3).propose("synthetic",candidates,List.of());
            assertEquals(StructuredDecisionModel.Proposal.REJECTED,result.status());assertEquals("DECISION_RESPONSE_INCOMPLETE",result.failureCode());
            assertEquals(17,result.inputTokens());assertTrue(result.decision().isEmpty());
        }
    }
    @Test void cumulativeBudgetAndMissingEndpointCannotFallThroughToAnExternalDefault() throws Exception {
        try(var provider=provider(200,"stop");var trace=TurnTraceContext.open("u","policy")){
            ContextAssembler.reserveMessages(List.of(dev.langchain4j.data.message.UserMessage.from("reserved")),"SYNTHETIC",65536,700);
            var result=new StructuredDecisionModel("openai-compatible","synthetic-key",provider.base(),"fixture",3).propose("synthetic",candidates,List.of());
            assertEquals("DECISION_CONTEXT_TURN_INPUT_BUDGET_EXCEEDED",result.failureCode());assertEquals(false,result.providerRequestAttempted());
            assertEquals(0,provider.calls().get());
            var missing=new StructuredDecisionModel("openai-compatible","synthetic-key","","fixture",3).propose("synthetic",candidates,List.of());
            assertEquals(StructuredDecisionModel.Proposal.SKIPPED,missing.status());assertEquals(false,missing.providerRequestAttempted());
        }
    }
    @Test void providerErrorBodyIsNeverWrittenByTheDecisionLogger() throws Exception {
        var logger=(ch.qos.logback.classic.Logger)org.slf4j.LoggerFactory.getLogger(StructuredDecisionModel.class);
        var appender=new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();appender.start();logger.addAppender(appender);
        try(var provider=provider(401,"stop")){
            var result=new StructuredDecisionModel("openai-compatible","synthetic-key",provider.base(),"fixture",3).propose("synthetic",candidates,List.of());
            assertEquals("DECISION_MODEL_AUTH_FAILED",result.failureCode());assertEquals(true,result.providerRequestAttempted());
            assertEquals(1,provider.calls().get());
            assertTrue(appender.list.stream().anyMatch(event->event.getFormattedMessage().contains("MODEL_AUTH_FAILED")));
            assertFalse(appender.list.stream().anyMatch(event->event.getFormattedMessage().contains("PRIVATE_PROVIDER_ERROR")));
        }finally{logger.detachAppender(appender);appender.stop();}
    }
}
