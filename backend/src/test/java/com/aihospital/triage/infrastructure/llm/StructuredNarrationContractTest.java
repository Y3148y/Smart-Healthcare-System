package com.aihospital.triage.infrastructure.llm;

import com.aihospital.shared.model.Models.Evidence;
import com.aihospital.triage.domain.NarrationModel;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class StructuredNarrationContractTest {
    @Test void stalledLocalProviderRecordsSdkTimeoutPathWithoutMedicalFallback() throws Exception {
        var release = new java.util.concurrent.CountDownLatch(1);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            try {
                exchange.getRequestBody().readAllBytes();
                release.await(6, java.util.concurrent.TimeUnit.SECONDS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            } finally { exchange.close(); }
        });
        server.start();
        try {
            var model = new OptionalNarrationModel();
            ReflectionTestUtils.setField(model, "mode", "openai-compatible");
            ReflectionTestUtils.setField(model, "apiKey", "synthetic-test-key");
            ReflectionTestUtils.setField(model, "model", "synthetic");
            ReflectionTestUtils.setField(model, "baseUrl", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
            ReflectionTestUtils.setField(model, "timeoutSeconds", 3);
            var failed = model.answerWithEvidence("合成问题", "", "", List.of(),
                    "可观察，请问持续多久了？", List.of(), null, false, "NO_MATCH");
            assertEquals("MODEL_CALL", failed.diagnostics().generation().phase());
            assertTrue(List.of("MODEL_TIMEOUT", "MODEL_IO_INTERRUPTED").contains(failed.diagnostics().failure().code()));
            assertTrue(failed.diagnostics().failure().locations().contains("HTTP_TRANSPORT")
                    || failed.diagnostics().failure().locations().contains("HTTP_CLIENT_TIMEOUT_EXIT"));
            assertNull(failed.diagnostics().failure().httpStatus());
            assertTrue(failed.diagnostics().adoptedReferenceIds().isEmpty());
            assertFalse(failed.text().matches("(?s).*(观察|持续多久).*"));
            assertFalse(json.writeValueAsString(failed).contains("synthetic-test-key"));
        } finally { release.countDown(); server.stop(0); }
    }
    private final ObjectMapper json = new ObjectMapper();
    private String draft(String text, String ids) {
        return "{\"paragraphs\":[{\"text\":\"" + text + "\",\"kind\":\"GENERAL_INFORMATION\",\"referenceIds\":"
                + ids + "}],\"questions\":[],\"uncovered\":[\"个人严重程度无法确认\"]}";
    }
    @Test void realAdapterUsesMessageListAndPersistsOnlyValidatedReferences() throws Exception {
        var response = new AtomicReference<>(draft("资料提供一般就医方向，无法确认个人严重程度。", "[\"E1\"]"));
        var request = new AtomicReference<String>();
        var status = new AtomicInteger(200);
        var finish = new AtomicReference<>("stop");
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/chat/completions", exchange -> {
            String sent = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            boolean reviewing = sent.contains("<review_data>");
            if (!reviewing) request.set(sent);
            byte[] bytes = json.writeValueAsBytes(Map.of("id", "synthetic", "object", "chat.completion", "created", 1,
                    "model", "synthetic", "usage", Map.of("prompt_tokens",11,"completion_tokens",13,"total_tokens",24),
                    "choices", List.of(Map.of("index", 0, "finish_reason", finish.get(),
                            "message", Map.of("role", "assistant", "content", reviewing
                                    ? AnswerSupportReviewerTest.PASS : response.get())))));
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            if (status.get() != 200) bytes = "{\"error\":{\"message\":\"synthetic-secret-response\",\"type\":\"rate_limit_error\"}}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status.get(), bytes.length);
            exchange.getResponseBody().write(bytes); exchange.close();
        });
        server.start();
        try {
            var model = new OptionalNarrationModel();
            ReflectionTestUtils.setField(model, "mode", "openai-compatible");
            ReflectionTestUtils.setField(model, "apiKey", "synthetic-test-key");
            ReflectionTestUtils.setField(model, "model", "synthetic");
            ReflectionTestUtils.setField(model, "baseUrl", "http://127.0.0.1:" + server.getAddress().getPort() + "/v1");
            ReflectionTestUtils.setField(model, "timeoutSeconds", 10);
            var history = List.of(new NarrationModel.Turn("USER", "咳嗽"),
                    new NarrationModel.Turn("ASSISTANT", "持续多久了？"), new NarrationModel.Turn("USER", "两天"));
            var evidence = List.of(new Evidence("方向资料", "source", "稳定的呼吸道症状可咨询呼吸内科。", .9));
            var services = new NarrationModel.ServiceContext(List.of(), false);
            var accepted = model.answerWithEvidence("咳嗽。两天", "呼吸内科", "", evidence, "兜底", history, services, true, "MATCHED");
            assertEquals("LIVE", accepted.status());
            assertEquals(List.of("E1"), accepted.diagnostics().adoptedReferenceIds());
            assertEquals("MODEL_REVIEW_PASSED", accepted.diagnostics().semanticSupport());
            assertEquals("STOP", accepted.diagnostics().generation().finishReason());
            assertEquals(11, accepted.diagnostics().generation().inputTokens());
            assertEquals(13, accepted.diagnostics().generation().outputTokens());
            assertTrue(request.get().contains("持续多久了"));
            assertTrue(request.get().contains("contentHash"));
            assertTrue(request.get().contains("不可信数据"));
            response.set(draft("引用不存在", "[\"E9\"]"));
            var blocked = model.answerWithEvidence("问题", "", "", evidence, "兜底", history, services, true, "MATCHED");
            assertEquals("VALIDATION_BLOCKED", blocked.status());
            assertTrue(blocked.text().contains("未通过校验"));
            assertFalse(blocked.text().contains("持续多久"));
            assertEquals("OUTPUT_REFERENCE_INVALID", blocked.diagnostics().failure().code());
            assertNotNull(blocked.diagnostics().elapsedMs());
            assertTrue(blocked.diagnostics().adoptedReferenceIds().isEmpty());
            assertNotEquals(accepted.diagnostics().traceId(), blocked.diagnostics().traceId());
            response.set(draft("你患有某疾病。", "[\"E1\"]"));
            var unsafe = model.answerWithEvidence("问题", "", "", evidence, "兜底", history, services, false, "MATCHED");
            assertEquals("CONTENT_BLOCKED", unsafe.diagnostics().validationStatus());
            assertEquals("OUTPUT_UNSAFE_PATTERN", unsafe.diagnostics().failure().code());
            response.set(draft("建议服用止痛药。", "[\"E1\"]"));
            assertEquals("VALIDATION_BLOCKED", model.answerWithEvidence("问题", "", "", evidence, "兜底", history,
                    services, true, "MATCHED").status());
            response.set(draft("一般信息", "[\"E1\"]"));
            var noEvidence = model.answerWithEvidence("问题", "", "", List.of(), "兜底", history,
                    services, true, "NO_MATCH");
            assertEquals("FALLBACK_UNGROUNDED", noEvidence.status());
            assertEquals("OUTPUT_REFERENCE_COUNT_INVALID", noEvidence.diagnostics().failure().code());
            assertEquals("NO_EVIDENCE_SAFE_FALLBACK", noEvidence.diagnostics().validationStatus());
            assertFalse(noEvidence.text().contains("一般信息"));
            status.set(429);
            var failed = model.answerWithEvidence("咳嗽。两天", "", "", evidence,
                    "可观察，请问持续多久了？", history, services, true, "MATCHED");
            assertEquals("FALLBACK", failed.status());
            assertEquals("MODEL_RATE_LIMITED", failed.diagnostics().failure().code());
            assertEquals(429, failed.diagnostics().failure().httpStatus());
            assertTrue(failed.diagnostics().adoptedReferenceIds().isEmpty());
            assertFalse(failed.text().matches("(?s).*(持续多久|观察|没有危险信号).*"));
            assertFalse(json.writeValueAsString(failed).contains("synthetic-secret-response"));
            assertEquals("MODEL_CALL",failed.diagnostics().generation().phase());
            assertNull(failed.diagnostics().generation().finishReason());
            status.set(200); finish.set("length");
            response.set(draft("一般资料", "[\"E1\"]"));
            var truncated=model.answerWithEvidence("问题","","",evidence,"兜底",history,services,true,"MATCHED");
            assertEquals("OUTPUT_TRUNCATED",truncated.diagnostics().failure().code());
            assertEquals("LENGTH",truncated.diagnostics().generation().finishReason());
            assertTrue(truncated.diagnostics().adoptedReferenceIds().isEmpty());
            finish.set("content_filter");
            assertEquals("OUTPUT_PROVIDER_FILTERED",model.answerWithEvidence("问题","","",evidence,"兜底",history,services,true,"MATCHED").diagnostics().failure().code());
        } finally { server.stop(0); }
    }
    @Test void demoDoesNotCallProviderAndRecordsNotRun() {
        var model = new OptionalNarrationModel();
        ReflectionTestUtils.setField(model, "mode", "demo");
        var answer = model.answerWithEvidence("问题", "", "", List.of(), "原兜底", List.of(), null, true, "NO_MATCH");
        assertEquals("原兜底", answer.text());
        assertEquals("DEMO_UNGROUNDED", answer.status());
        assertEquals("NOT_RUN", answer.diagnostics().validationStatus());
    }
}
