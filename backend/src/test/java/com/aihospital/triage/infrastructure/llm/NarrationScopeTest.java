package com.aihospital.triage.infrastructure.llm;

import com.aihospital.triage.domain.NarrationModel.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic HTTP responses verify boundaries; not real-model quality evidence. */
class NarrationScopeTest {
    @Test void missingSnapshotIsUnknownAndDoesNotInventEligibility() {
        assertNull(OptionalNarrationModel.serviceNotice(null).bookingAllowed());
        assertNull(OptionalNarrationModel.serviceNotice(new ServiceContext(List.of(), false)).bookingAllowed());
        var snapshot = OptionalNarrationModel.serviceNotice(
                new ServiceContext(List.of(new ServiceState("测试科室", "QUERY_FAILED", "查询失败")), false));
        assertEquals(false, snapshot.bookingAllowed());
        assertEquals(List.of("测试科室：查询失败"), snapshot.messages());
        assertFalse(snapshot.toString().contains("QUERY_FAILED"));
        assertFalse(snapshot.toString().contains("routineBookingAllowed"));
    }

    @Test void currentRequestControlsServiceInjectionAndInternalFieldsAreBlocked() throws Exception {
        var json = new ObjectMapper();
        var captured = new AtomicReference<String>();
        var text = new AtomicReference<>("资料未覆盖该问题，无法确认。");
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            String sent = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            boolean reviewing = sent.contains("<review_data>");
            if (!reviewing) captured.set(sent);
            String draft = json.writeValueAsString(Map.of("paragraphs", List.of(Map.of("text", text.get(),
                    "kind", "LIMITATION", "referenceIds", List.of())), "questions", List.of(), "uncovered", List.of()));
            if (reviewing) draft = AnswerSupportReviewerTest.PASS;
            byte[] body = json.writeValueAsBytes(Map.of("id", "test", "object", "chat.completion", "created", 1,
                    "model", "test", "choices", List.of(Map.of("index", 0, "finish_reason", "stop",
                    "message", Map.of("role", "assistant", "content", draft)))));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            exchange.getResponseBody().write(body); exchange.close();
        });
        server.start();
        try {
            var model = new OptionalNarrationModel();
            ReflectionTestUtils.setField(model, "mode", "openai-compatible");
            ReflectionTestUtils.setField(model, "apiKey", "synthetic-only");
            ReflectionTestUtils.setField(model, "model", "test");
            ReflectionTestUtils.setField(model, "baseUrl", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
            ReflectionTestUtils.setField(model, "timeoutSeconds", 5);
            var services = new ServiceContext(List.of(new ServiceState("测试科室", "AVAILABLE", "模拟可查询")), true);
            var history = List.of(new Turn("USER", "我要预约"), new Turn("ASSISTANT", "可查看模拟号源"),
                    new Turn("USER", "我现在不预约，只问日常注意事项，不用追问。"));
            var withdrawn = model.answerWithEvidence("累计问题", "", "", List.of(), "fallback",
                    history, services, true, "NO_MATCH");
            assertEquals("LIVE_UNGROUNDED", withdrawn.status());
            assertNull(withdrawn.diagnostics().serviceNotice());
            assertFalse(captured.get().contains("<service_facts>"));
            assertTrue(captured.get().contains(MedicalAnswerInstructions.task(history.get(2).content())));
            assertTrue(captured.get().contains("相近症状、标题和主题词不能替代依据"));
            assertTrue(captured.get().contains("我要预约")); // history retained, not erased
            assertEquals("LIVE_UNGROUNDED", model.answerWithEvidence("现在想预约", "", "", List.of(), "fallback",
                    List.of(new Turn("USER", "现在想预约")), null, true, "NO_MATCH").status());
            assertFalse(captured.get().contains("<service_facts>"));
            assertTrue(captured.get().contains(MedicalAnswerInstructions.task("现在想预约")));
            assertFalse(captured.get().contains("NOT_QUERIED"));
            assertFalse(captured.get().contains("\\\"routineBookingAllowed\\\":false"));
            var booking = model.answerWithEvidence("想预约", "", "", List.of(), "fallback",
                    List.of(), services, true, "NO_MATCH");
            assertEquals("LIVE_UNGROUNDED", booking.status());
            assertEquals(List.of("测试科室：模拟可查询"), booking.diagnostics().serviceNotice().messages());
            assertEquals(true, booking.diagnostics().serviceNotice().bookingAllowed());
            assertFalse(captured.get().contains("模拟可查询"));
            assertFalse(booking.text().contains("模拟可查询"));
            for (String machineField : List.of("routineBookingAllowed", "service_state", "NOT_QUERIED", "AVAILABLE"))
                assertFalse(captured.get().contains(machineField), machineField);
            for (String internal : List.of("routineBookingAllowed为true", "状态AVAILABLE", "service_state", "service_facts", "NO_SLOTS")) {
                text.set(internal);
                var blocked = model.answerWithEvidence("想预约", "", "", List.of(), "fallback", List.of(), services, true, "NO_MATCH");
                assertEquals("FALLBACK_UNGROUNDED", blocked.status());
                assertEquals("OUTPUT_INTERNAL_FIELD", blocked.diagnostics().failure().code());
                assertEquals("NO_EVIDENCE_SAFE_FALLBACK", blocked.diagnostics().validationStatus());
                assertTrue(blocked.diagnostics().adoptedReferenceIds().isEmpty());
                assertFalse(blocked.text().contains(internal));
            }
        } finally { server.stop(0); }
    }
}
