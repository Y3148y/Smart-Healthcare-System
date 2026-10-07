package com.aihospital.triage.infrastructure.llm;

import com.aihospital.triage.domain.NarrationModel.Turn;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import static org.junit.jupiter.api.Assertions.*;

class NarrationClientReuseTest {
    @Test void reusedClientStillSendsEachTurnsOwnContextAndValidatesOutput() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var requests = new CopyOnWriteArrayList<String>();
        server.createContext("/v1/chat/completions", exchange -> {
            requests.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String answer = requests.size() == 2 ? "你患有某疾病。" : "请问持续多久了？";
            byte[] response = ("{\"id\":\"test\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"synthetic\","
                    + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"" + answer
                    + "\"},\"finish_reason\":\"stop\"}]}").getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response); exchange.close();
        });
        server.start();
        try {
            var model = new OptionalNarrationModel();
            ReflectionTestUtils.setField(model, "mode", "openai-compatible");
            ReflectionTestUtils.setField(model, "apiKey", "synthetic-test-credential");
            ReflectionTestUtils.setField(model, "model", "synthetic");
            ReflectionTestUtils.setField(model, "baseUrl", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
            ReflectionTestUtils.setField(model, "timeoutSeconds", 10);
            var client = model.client(true);
            assertSame(client, model.client(true));
            var first = model.guide("首轮问题", "方向", "依据", "兜底", List.of(new Turn("USER", "首轮问题")));
            assertEquals("LIVE", first.status());
            var second = model.guide("第二轮问题", "方向", "依据", "兜底", List.of(
                    new Turn("USER", "首轮问题"), new Turn("ASSISTANT", first.text()), new Turn("USER", "第二轮问题")));
            assertEquals("VALIDATION_BLOCKED", second.status());
            assertEquals("兜底", second.text());
            assertSame(client, model.client(true));
            assertEquals(2, requests.size());
            assertTrue(requests.get(1).contains("第二轮问题"));
            assertTrue(requests.get(1).contains("请问持续多久了"));
            assertFalse(requests.get(0).contains("第二轮问题"));
            assertTrue(requests.get(1).contains("<evidence>依据</evidence>"));
        } finally { server.stop(0); }
    }
}
