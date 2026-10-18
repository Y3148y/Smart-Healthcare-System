package com.aihospital.triage.infrastructure.llm;

import com.aihospital.triage.application.*;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.output.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import com.sun.net.httpserver.HttpServer;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class LangChainIntentExtractorTest {
    @Test void exhaustedTurnBudgetCannotIssueAnExtractionRequest(){
        try(var scope=com.aihospital.shared.diagnostics.TurnTraceContext.open("u","policy")){
            ContextAssembler.reserveMessages(List.of(UserMessage.from("reserved")),"SYNTHETIC",65536,700);
            var requests=new AtomicInteger();
            var result=extractor(AgentWorkflowSettings.Mode.ENABLED,"openai-compatible",messages->{requests.incrementAndGet();return null;}).extract(input);
            assertEquals("INTENT_CONTEXT_TURN_INPUT_BUDGET_EXCEEDED",result.failureCode());assertEquals(0,requests.get());
        }
    }
    @Test void contextBudgetBlocksBeforeProviderAndDoesNotTruncateSources(){
        var calls=new AtomicInteger();
        var extractor=extractor(AgentWorkflowSettings.Mode.SHADOW,"openai-compatible",messages->{calls.incrementAndGet();return null;});
        org.springframework.test.util.ReflectionTestUtils.setField(extractor,"inputTokenBudget",512);
        var result=extractor.extract(input);
        assertEquals(IntentExtractor.Status.UNAVAILABLE,result.status());
        assertEquals("INTENT_CONTEXT_INPUT_BUDGET_EXCEEDED",result.failureCode());
        assertNull(result.inputTokens());assertNull(result.extraction());assertEquals(0,calls.get());
    }
    @Test void contextEstimateIsSeparateFromActualProviderUsage(){
        try(var trace=com.aihospital.shared.diagnostics.TurnTraceContext.open("synthetic","policy")){
            var result=extractor(AgentWorkflowSettings.Mode.SHADOW,"openai-compatible",messages->
                    Response.from(AiMessage.from(VALID),new TokenUsage(10,5),FinishReason.STOP)).extract(input);
            var event=com.aihospital.shared.diagnostics.TurnTraceContext.metadata().executions().get(0);
            assertEquals("INTENT_EXTRACTION",event.name());assertNotNull(event.contextBudget());
            assertTrue(event.contextBudget().estimatedInputTokens()>10);assertEquals(10,result.inputTokens());
        }
    }
    private final IntentInput input = new IntentInput(List.of(
            new IntentInput.SourceMessage("a1", "ASSISTANT", "持续多久？"),
            new IntentInput.SourceMessage("u2", "USER", "两天")));
    private static final String VALID = "{\"schemaVersion\":1,\"intents\":[\"SYMPTOM_CONSULT\"],\"events\":[],\"missingInformation\":[\"SYMPTOM\"],\"conflicts\":[]}";
    private LangChainIntentExtractor extractor(AgentWorkflowSettings.Mode mode, String aiMode,
            LangChainIntentExtractor.Sender sender) {
        return new LangChainIntentExtractor(new AgentWorkflowSettings(mode, 8, 2048), new IntentValidator(),
                aiMode, "synthetic-key", "https://example.invalid/v1", "synthetic-model", sender);
    }
    @Test void offAndDemoNeverCallProvider() {
        var calls = new AtomicInteger();
        LangChainIntentExtractor.Sender sender = messages -> { calls.incrementAndGet(); return null; };
        assertEquals(IntentExtractor.Status.NOT_CONFIGURED, extractor(AgentWorkflowSettings.Mode.OFF,
                "openai-compatible", sender).extract(input).status());
        assertEquals(IntentExtractor.Status.NOT_CONFIGURED, extractor(AgentWorkflowSettings.Mode.SHADOW,
                "demo", sender).extract(input).status());
        assertEquals(0, calls.get());
    }
    @Test void carriesAssistantQuestionAndSourceIdsAndReportsActualUsage() {
        var result = extractor(AgentWorkflowSettings.Mode.SHADOW, "openai-compatible", messages -> {
            assertTrue(((SystemMessage) messages.get(0)).text().contains("untrusted data"));
            String payload = ((UserMessage) messages.get(1)).singleText();
            assertTrue(payload.contains("持续多久？")); assertTrue(payload.contains("u2"));
            assertFalse(payload.contains("synthetic-key"));
            return Response.from(AiMessage.from(VALID), new TokenUsage(100, 40), FinishReason.STOP);
        }).extract(input);
        assertEquals(IntentExtractor.Status.ACCEPTED, result.status());
        assertEquals(100, result.inputTokens()); assertEquals(40, result.outputTokens());
    }
    @Test void invalidJsonAndIncompleteOutputCannotBecomeFacts() {
        for (String text : List.of("not-json", VALID.replace("SYMPTOM_CONSULT", "UNKNOWN"))) {
            var result = extractor(AgentWorkflowSettings.Mode.SHADOW, "openai-compatible",
                    messages -> Response.from(AiMessage.from(text), null, FinishReason.STOP)).extract(input);
            assertEquals(IntentExtractor.Status.INVALID_OUTPUT, result.status()); assertNull(result.extraction());
        }
        assertEquals("INTENT_INCOMPLETE", extractor(AgentWorkflowSettings.Mode.SHADOW, "openai-compatible",
                messages -> Response.from(AiMessage.from(VALID), null, FinishReason.LENGTH)).extract(input).failureCode());
    }
    @Test void providerFailuresAreSanitizedAndNotRetried() {
        var calls = new AtomicInteger();
        var result = extractor(AgentWorkflowSettings.Mode.SHADOW, "openai-compatible", messages -> {
            calls.incrementAndGet(); throw new java.io.IOException("private patient text and secret");
        }).extract(input);
        assertEquals(IntentExtractor.Status.UNAVAILABLE, result.status());
        assertEquals("INTENT_MODEL_IO_ERROR", result.failureCode()); assertEquals(1, calls.get());
        assertNull(result.inputTokens());
    }

    @Test void extractionThinkingIsIndependentOfAnswerGeneration() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var requestBody = new java.util.concurrent.atomic.AtomicReference<String>();
        server.createContext("/v1/chat/completions", exchange -> {
            requestBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] response = ("{\"choices\":[{\"finish_reason\":\"stop\",\"message\":{\"content\":"
                    + new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(VALID) + "}}]}")
                    .getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response); exchange.close();
        });
        server.start();
        try {
            var extracted = new LangChainIntentExtractor(new AgentWorkflowSettings(AgentWorkflowSettings.Mode.ENABLED, 8, 2048),
                    new IntentValidator(), "openai-compatible", "synthetic-key",
                    "http://127.0.0.1:" + server.getAddress().getPort() + "/v1", "synthetic-model", false)
                    .extract(input);
            assertEquals(IntentExtractor.Status.ACCEPTED, extracted.status());
            var payload = new com.fasterxml.jackson.databind.ObjectMapper().readTree(requestBody.get());
            assertFalse(payload.path("enable_thinking").asBoolean());
            assertEquals("json_object", payload.path("response_format").path("type").asText());
        } finally { server.stop(0); }
    }

    @Test void timeoutAuthenticationAndRateLimitHaveDistinctSafeCodes() {
        List<Exception> failures = List.of(
                new java.util.concurrent.ExecutionException(new java.net.http.HttpTimeoutException("private")),
                new dev.ai4j.openai4j.OpenAiHttpException(401, "private key"),
                new dev.ai4j.openai4j.OpenAiHttpException(429, "private provider body"));
        List<String> codes = List.of("INTENT_MODEL_TIMEOUT", "INTENT_MODEL_AUTH_FAILED", "INTENT_MODEL_RATE_LIMITED");
        for (int index = 0; index < failures.size(); index++) {
            Exception failure = failures.get(index);
            var calls = new AtomicInteger();
            var result = extractor(AgentWorkflowSettings.Mode.ENABLED, "openai-compatible", messages -> {
                calls.incrementAndGet(); throw failure;
            }).extract(input);
            assertEquals(IntentExtractor.Status.UNAVAILABLE, result.status());
            assertEquals(codes.get(index), result.failureCode());
            assertNull(result.extraction()); assertNull(result.inputTokens()); assertNull(result.outputTokens());
            assertEquals(1, calls.get()); assertFalse(result.toString().contains("private"));
        }
    }
}
