package com.aihospital.triage.infrastructure.llm;

import com.aihospital.triage.application.*;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.output.*;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class LangChainIntentExtractorTest {
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
        assertEquals("INTENT_MODEL_UNAVAILABLE", result.failureCode()); assertEquals(1, calls.get());
        assertNull(result.inputTokens());
    }
}
