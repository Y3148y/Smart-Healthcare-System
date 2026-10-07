package com.aihospital.triage.infrastructure.llm;

import com.aihospital.triage.domain.NarrationModel.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import static org.junit.jupiter.api.Assertions.*;

/** Fake HTTP provider checks the actual SDK request, not a claim of live model validation. */
class NarrationServicePromptTest {
    @Test void sdkReceivesCurrentServiceSnapshotSeparateFromMedicalEvidence() throws Exception {
        var requests = new CopyOnWriteArrayList<String>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            requests.add(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            String answer = requests.size() == 2 ? "你患有某疾病。" : "请查看页面的模拟号源状态。";
            byte[] body = ("{\"id\":\"test\",\"object\":\"chat.completion\",\"created\":1,\"model\":\"test\","
                    + "\"choices\":[{\"index\":0,\"message\":{\"role\":\"assistant\",\"content\":\"" + answer
                    + "\"},\"finish_reason\":\"stop\"}]}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body); exchange.close();
        });
        server.start();
        try {
            var model = new OptionalNarrationModel();
            ReflectionTestUtils.setField(model, "mode", "openai-compatible");
            ReflectionTestUtils.setField(model, "apiKey", "synthetic-test-credential");
            ReflectionTestUtils.setField(model, "model", "test");
            ReflectionTestUtils.setField(model, "baseUrl", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
            var history = List.of(new Turn("USER", "首轮"), new Turn("ASSISTANT", "持续多久？"), new Turn("USER", "两天了"));
            var services = new ServiceContext(List.of(new ServiceState("呼吸内科", "NO_SLOTS", "当前无剩余号源")), false);
            assertEquals("LIVE", model.explainWithServices("两天了", "呼吸内科", "呼吸内科", "咳嗽相关依据", "fallback", history, services).status());
            var messages = new ObjectMapper().readTree(requests.get(0)).get("messages");
            assertTrue(messages.get(0).get("content").asText().contains(OptionalNarrationModel.SERVICE_BOUNDARY));
            assertTrue(messages.get(1).get("content").asText().contains("<evidence>咳嗽相关依据</evidence>"));
            String snapshot = messages.get(2).get("content").asText();
            assertTrue(snapshot.contains("NO_SLOTS"));
            assertTrue(snapshot.contains("\"routineBookingAllowed\":false"));
            assertFalse(messages.get(1).get("content").asText().contains("NO_SLOTS"));
            assertEquals("持续多久？", messages.get(4).get("content").asText());
            assertEquals("两天了", messages.get(5).get("content").asText());
            assertEquals("EVIDENCE_BLOCKED", model.explainWithServices("问题", "科室", "方向", "", "fallback", history, services).status());
            assertEquals(1, requests.size()); // service facts cannot substitute for evidence
            assertEquals("VALIDATION_BLOCKED", model.explainWithServices("问题", "科室", "方向", "依据", "fallback", history,
                    new ServiceContext(List.of(new ServiceState("科室", "QUERY_FAILED", "查询失败")), false)).status());
            assertEquals("LIVE", model.guide("两天了", "方向", "依据", "fallback", history).status());
            var guidance = new ObjectMapper().readTree(requests.get(2)).get("messages").get(0).get("content").asText();
            assertTrue(guidance.contains(OptionalNarrationModel.GUIDANCE_STYLE));
            assertFalse(guidance.contains("再只问一个"));
        } finally { server.stop(0); }
    }
    @Test void demoStillReturnsFallbackWithoutExternalCall() {
        var model = new OptionalNarrationModel();
        ReflectionTestUtils.setField(model, "mode", "demo");
        var answer = model.explainWithServices("问题", "方向", "方向", "依据", "原兜底", List.of(),
                new ServiceContext(List.of(new ServiceState("方向", "NO_SLOTS", "无号源")), false));
        assertEquals("DEMO", answer.status());
        assertEquals("原兜底", answer.text());
    }
}
