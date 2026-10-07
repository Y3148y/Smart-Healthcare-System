package com.aihospital.triage.infrastructure.llm;

import com.aihospital.shared.model.Models.Evidence;
import com.aihospital.triage.domain.NarrationModel;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;

class ThinkingModeTransportTest {
    private final ObjectMapper json = new ObjectMapper();
    @Test void bothArmsUseSameContractAndKeepReasoningOutOfHistory() throws Exception {
        var request = new AtomicReference<String>(); var status = new AtomicInteger(200);
        var text = new AtomicReference<>("资料提供一般入口参考。");
        var finish = new AtomicReference<>("stop");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v1/chat/completions", exchange -> {
            String sent = new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8);
            boolean reviewing = sent.contains("<review_data>");
            if (!reviewing) request.set(sent);
            String draft = json.writeValueAsString(Map.of("paragraphs",List.of(Map.of("text",text.get(),"kind","GENERAL_INFORMATION","referenceIds",List.of("E1"))),"questions",List.of(),"uncovered",List.of()));
            if (reviewing) draft = AnswerSupportReviewerTest.PASS;
            byte[] bytes = json.writeValueAsBytes(Map.of("choices",List.of(Map.of("finish_reason",finish.get(),"message",Map.of("content",draft,"reasoning_content","synthetic-private-reasoning"))),"usage",Map.of("prompt_tokens",12,"completion_tokens",20)));
            if (status.get()!=200) bytes="synthetic-secret-provider-error".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status.get(),bytes.length); exchange.getResponseBody().write(bytes); exchange.close();
        }); server.start();
        try {
            for (boolean thinking : List.of(false,true)) {
                var model=model(server,Boolean.toString(thinking));
                ReflectionTestUtils.setField(model,"structuredJson",true);
                ReflectionTestUtils.setField(model,"thinkingBudget",thinking ? "1024" : "");
                var history=List.of(new NarrationModel.Turn("USER","咳嗽"),new NarrationModel.Turn("ASSISTANT","持续多久？"),new NarrationModel.Turn("USER","两天"));
                var answer=model.answerWithEvidence("咳嗽两天","","",List.of(new Evidence("资料","source","资料提供一般入口参考。",.8)),"可观察",history,null,false,"MATCHED");
                assertEquals("LIVE",answer.status());
                assertEquals("PASSED",answer.diagnostics().supportReview().status());
                assertEquals(false,answer.diagnostics().supportReview().thinkingEnabled());
                var sent=json.readTree(request.get());
                assertEquals(thinking,sent.path("enable_thinking").booleanValue());
                assertEquals("json_object",sent.path("response_format").path("type").asText());
                assertFalse(sent.path("preserve_thinking").booleanValue());
                assertFalse(sent.path("stream").booleanValue());
                assertEquals(4096,sent.path("max_tokens").intValue());
                if (thinking) {
                    assertEquals(1024, sent.path("thinking_budget").intValue());
                    assertEquals(1024, answer.diagnostics().generation().thinkingBudget());
                } else {
                    assertFalse(sent.has("thinking_budget"));
                    assertNull(answer.diagnostics().generation().thinkingBudget());
                }
                assertTrue(request.get().contains("持续多久")); assertTrue(request.get().contains("<evidence>"));
                assertEquals(thinking,answer.diagnostics().generation().thinkingEnabled());
                assertEquals("CONFIGURED_HTTP",answer.diagnostics().generation().transport());
                assertEquals(true,answer.diagnostics().generation().jsonObjectEnabled());
                assertEquals(12,answer.diagnostics().generation().inputTokens());
                assertFalse(json.writeValueAsString(answer).contains("synthetic-private-reasoning"));
            }
            var model=model(server,"false");
            text.set("你患有某疾病。");
            assertEquals("VALIDATION_BLOCKED",turn(model).status());
            text.set("一般信息。");finish.set("length");
            assertEquals("OUTPUT_TRUNCATED",turn(model).diagnostics().failure().code());
            status.set(429);
            var failed=turn(model);
            assertEquals("MODEL_RATE_LIMITED",failed.diagnostics().failure().code());
            assertTrue(failed.diagnostics().adoptedReferenceIds().isEmpty());
            assertFalse(json.writeValueAsString(failed).contains("synthetic-secret-provider-error"));
        } finally { server.stop(0); }
    }
    @Test void emptyKeepsExistingAdapterAndInvalidFlagIsRejected() {
        var model=new OptionalNarrationModel();
        ReflectionTestUtils.setField(model,"mode","demo");
        assertDoesNotThrow(model::validateCredential);
        ReflectionTestUtils.setField(model,"enableThinking","maybe");
        assertThrows(IllegalArgumentException.class,model::validateCredential);
    }
    @Test void budgetRequiresExplicitThinkingAndValidRange() {
        var model = new OptionalNarrationModel();
        ReflectionTestUtils.setField(model, "mode", "demo");
        assertDoesNotThrow(model::validateCredential);
        ReflectionTestUtils.setField(model, "thinkingBudget", "1024");
        assertThrows(IllegalArgumentException.class, model::validateCredential);
        ReflectionTestUtils.setField(model, "enableThinking", "false");
        assertThrows(IllegalArgumentException.class, model::validateCredential);
        ReflectionTestUtils.setField(model, "enableThinking", "true");
        assertDoesNotThrow(model::validateCredential);
        for (String invalid : List.of("0", "-1", "32769", "3.5", "not-a-number")) {
            ReflectionTestUtils.setField(model, "thinkingBudget", invalid);
            assertThrows(IllegalArgumentException.class, model::validateCredential);
        }
    }
    @Test void bodyReadHasADeadlineNotJustHeaders() throws Exception {
        var release=new java.util.concurrent.CountDownLatch(1);
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v1/chat/completions",exchange->{
            try { exchange.getRequestBody().readAllBytes();exchange.sendResponseHeaders(200,0);exchange.getResponseBody().flush();release.await(5,java.util.concurrent.TimeUnit.SECONDS); }
            catch(InterruptedException ex){Thread.currentThread().interrupt();}finally{exchange.close();}
        });server.start();
        try {
            assertThrows(java.util.concurrent.TimeoutException.class,()->new ConfiguredChatTransport().generate(
                    "http://127.0.0.1:"+server.getAddress().getPort()+"/v1","synthetic-key","synthetic",false,4096,1,List.of(new dev.langchain4j.data.message.UserMessage("test"))));
        }finally{release.countDown();server.stop(0);}
    }
    @Test void oversizeProviderBodyIsBoundedAndRedirectIsNotFollowed() throws Exception {
        var status=new AtomicInteger(200); var downstream=new AtomicInteger();
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v1/chat/completions",exchange->{
            exchange.getRequestBody().readAllBytes();
            byte[] bytes=new byte[300*1024];
            if(status.get()==302){exchange.getResponseHeaders().set("Location","/leak");bytes=new byte[0];}
            try{exchange.sendResponseHeaders(status.get(),bytes.length);exchange.getResponseBody().write(bytes);}finally{exchange.close();}
        });server.createContext("/leak",exchange->{downstream.incrementAndGet();exchange.close();});server.start();
        try {
            var client=new ConfiguredChatTransport();String base="http://127.0.0.1:"+server.getAddress().getPort()+"/v1";
            assertThrows(java.util.concurrent.ExecutionException.class,()->client.generate(base,"synthetic-key","synthetic",false,4096,3,List.of(new dev.langchain4j.data.message.UserMessage("test"))));
            status.set(302);
            var ex=assertThrows(dev.ai4j.openai4j.OpenAiHttpException.class,()->client.generate(base,"synthetic-key","synthetic",false,4096,3,List.of(new dev.langchain4j.data.message.UserMessage("test"))));
            assertEquals(302,ex.code());assertEquals(0,downstream.get());
        }finally{server.stop(0);}
    }
    private OptionalNarrationModel model(HttpServer server,String thinking) {
        var model=new OptionalNarrationModel();
        ReflectionTestUtils.setField(model,"mode","openai-compatible");ReflectionTestUtils.setField(model,"apiKey","synthetic-key");
        ReflectionTestUtils.setField(model,"model","synthetic");ReflectionTestUtils.setField(model,"baseUrl","http://127.0.0.1:"+server.getAddress().getPort()+"/v1");
        ReflectionTestUtils.setField(model,"timeoutSeconds",3);ReflectionTestUtils.setField(model,"enableThinking",thinking);return model;
    }
    private NarrationModel.Answer turn(OptionalNarrationModel model) {
        return model.answerWithEvidence("问题","","",List.of(new Evidence("资料","source","一般资料。",.8)),"可观察",List.of(),null,false,"MATCHED");
    }
}
