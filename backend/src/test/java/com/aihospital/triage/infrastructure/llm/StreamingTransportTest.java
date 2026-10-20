package com.aihospital.triage.infrastructure.llm;

import com.sun.net.httpserver.HttpServer;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.output.FinishReason;
import org.junit.jupiter.api.Test;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class StreamingTransportTest {
    @Test void streamPreservesUnspecifiedThinkingAndOnlySendsExplicitOverrides() throws Exception {
        var requests=new java.util.concurrent.CopyOnWriteArrayList<com.fasterxml.jackson.databind.JsonNode>();
        var server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v1/chat/completions",exchange->{
            requests.add(new com.fasterxml.jackson.databind.ObjectMapper().readTree(exchange.getRequestBody()));
            byte[] bytes=(DELTA+END).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type","text/event-stream");exchange.sendResponseHeaders(200,bytes.length);
            try(var out=exchange.getResponseBody()){out.write(bytes);}
        });
        server.start();
        try {
            for(Boolean setting:new Boolean[]{null,true,false})new ConfiguredChatTransport().generateStreaming(
                    "http://127.0.0.1:"+server.getAddress().getPort()+"/v1","synthetic-only","fixture",setting,256,5,List.of(UserMessage.from("fixture")),false,null);
            assertFalse(requests.get(0).has("enable_thinking"));
            assertTrue(requests.get(1).path("enable_thinking").booleanValue());
            assertTrue(requests.get(2).has("enable_thinking"));assertFalse(requests.get(2).path("enable_thinking").booleanValue());
        } finally {server.stop(0);}
    }
    private static final String DELTA = "data: {\"choices\":[{\"index\":0,\"delta\":{\"content\":\"中文😀\",\"reasoning_content\":\"private-reasoning\"},\"finish_reason\":null}]}\r\n\r\n";
    private static final String END = "data: {\"choices\":[{\"index\":0,\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n"
            + "data: {\"choices\":[],\"usage\":{\"prompt_tokens\":7,\"completion_tokens\":9}}\n\ndata: [DONE]\n\n";

    @Test void realChunkedHttpPreservesUtf8UsageAndDiscardsReasoning() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v1/chat/completions", exchange -> {
            var request = new com.fasterxml.jackson.databind.ObjectMapper().readTree(exchange.getRequestBody());
            assertTrue(request.path("stream").asBoolean());
            assertTrue(request.path("stream_options").path("include_usage").asBoolean());
            exchange.getResponseHeaders().set("Content-Type","text/event-stream");
            exchange.sendResponseHeaders(200,0);
            try(var out=exchange.getResponseBody()) {
                for(byte b:(DELTA+END).getBytes(StandardCharsets.UTF_8)) { out.write(b); out.flush(); }
            }
        });
        server.start();
        try {
            var response = new ConfiguredChatTransport().generateStreaming("http://127.0.0.1:"+server.getAddress().getPort()+"/v1",
                    "synthetic-only", "fixture", false, 256, 5, List.of(UserMessage.from("fixture")), true, null);
            assertEquals("中文😀",response.content().text());
            assertEquals(FinishReason.STOP,response.finishReason());
            assertEquals(7,response.tokenUsage().inputTokenCount());
            assertEquals(9,response.tokenUsage().outputTokenCount());
            assertFalse(response.content().text().contains("private-reasoning"));
        } finally { server.stop(0); }
    }

    @Test void incompleteInvalidAndUnexpectedToolStreamsAreNotAccepted() {
        for(String stream:List.of(DELTA, DELTA+END.replace("data: [DONE]",": no terminal"),
                DELTA+END+DELTA, DELTA.replace("\"index\":0","\"index\":0.1")+END,
                DELTA.replace("\"index\":0","\"index\":4294967296")+END,
                DELTA.replace("\"index\":0","\"index\":-4294967296")+END,
                DELTA.replace("\"content\":\"中文😀\"","\"content\":{}")+END,
                DELTA.replace("\"content\":\"中文😀\"","\"tool_calls\":[]")+END,
                "data: {\"error\":\"private-provider-detail\"}\n\n"+END)) {
            assertThrows(java.io.IOException.class,()->ConfiguredChatTransport.decodeStream(stream.getBytes(StandardCharsets.UTF_8)));
        }
        assertThrows(java.io.IOException.class,()->ConfiguredChatTransport.decodeStream(new byte[]{(byte)0xC3}));
    }
}
