package com.aihospital.triage.infrastructure.llm;

import com.aihospital.triage.application.*;
import com.aihospital.triage.domain.IntentExtraction;
import com.aihospital.tools.domain.AgentTool;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class AgentFunctionCallingTransportTest {
    @Test void functionSchemaAndResultIdsAreTransmittedWithoutReasoning() throws Exception {
        var json = new ObjectMapper(); var requests = new ArrayList<String>(); var count = new AtomicInteger();
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v1/chat/completions", exchange -> {
            requests.add(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));
            boolean first = count.incrementAndGet()==1;
            Map<String,Object> message = first ? Map.of("tool_calls",List.of(Map.of("id","c1","type","function",
                    "function",Map.of("name","medical_knowledge_retrieve","arguments","{\"query\":\"合成问题\"}"))),
                    "reasoning_content","private-reasoning") : Map.of("content","DONE");
            byte[] bytes=json.writeValueAsBytes(Map.of("choices",List.of(Map.of("finish_reason",first?"tool_calls":"stop","message",message)),
                    "usage",Map.of("prompt_tokens",50,"completion_tokens",10)));
            exchange.sendResponseHeaders(200,bytes.length);exchange.getResponseBody().write(bytes);exchange.close();
        });server.start();
        try {
            var model = new LangChainAgentModel("openai-compatible","synthetic-key","http://127.0.0.1:"+server.getAddress().getPort()+"/v1","model",false);
            var input = new IntentInput(List.of(new IntentInput.SourceMessage("u1","USER","合成问题")));
            var intent = new IntentExtraction(1,List.of(IntentExtraction.Intent.GENERAL_HEALTH),List.of(),List.of(),List.of());
            var selected=model.decide(input,intent,Set.of(AgentTool.MEDICAL_KNOWLEDGE),Set.of(),Set.of("合成问题"),List.of(),2);
            assertEquals(AgentModel.Status.TOOLS,selected.status());assertEquals("c1",selected.calls().get(0).id());
            assertEquals(50,selected.inputTokens());
            var done=model.decide(input,intent,Set.of(),Set.of(),Set.of("合成问题"),
                    List.of(new AgentModel.Exchange(selected.calls(),List.of("synthetic-result"))),2);
            assertEquals(AgentModel.Status.DONE,done.status());
            var first=json.readTree(requests.get(0));assertEquals("function",first.path("tools").get(0).path("type").asText());
            assertFalse(first.path("enable_thinking").asBoolean());
            assertEquals("合成问题", first.path("tools").get(0).path("function").path("parameters")
                    .path("properties").path("query").path("enum").get(0).asText());
            assertFalse(first.path("tools").get(0).path("function").path("parameters").path("additionalProperties").asBoolean());
            var turns=json.readTree(requests.get(1)).path("messages");
            assertFalse(json.readTree(requests.get(1)).has("tools"));
            assertEquals("c1",turns.get(2).path("tool_calls").get(0).path("id").asText());
            assertEquals("tool",turns.get(3).path("role").asText());assertEquals("c1",turns.get(3).path("tool_call_id").asText());
            assertEquals("synthetic-result",turns.get(3).path("content").asText());
            assertFalse(requests.get(1).contains("private-reasoning"));
            var oversize=model.decide(input,intent,Set.of(AgentTool.MEDICAL_KNOWLEDGE),Set.of(),Set.of("合成问题"),
                    List.of(new AgentModel.Exchange(selected.calls(),List.of("x".repeat(31000)))),2);
            assertEquals(AgentModel.Status.UNAVAILABLE,oversize.status());assertEquals(2,count.get());
        } finally {server.stop(0);}
    }
}
