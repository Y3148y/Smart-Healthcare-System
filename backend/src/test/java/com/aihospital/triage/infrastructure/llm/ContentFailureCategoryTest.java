package com.aihospital.triage.infrastructure.llm;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ContentFailureCategoryTest {
    @Test void jsonOptionCannotSilentlyRunOnUnsupportedAdapterAndLegacyMetadataStaysUnknown() throws Exception {
        var model = new OptionalNarrationModel();
        org.springframework.test.util.ReflectionTestUtils.setField(model, "mode", "openai-compatible");
        org.springframework.test.util.ReflectionTestUtils.setField(model, "apiKey", "synthetic-key");
        org.springframework.test.util.ReflectionTestUtils.setField(model, "structuredJson", true);
        assertThrows(IllegalArgumentException.class, model::validateCredential);
        org.springframework.test.util.ReflectionTestUtils.setField(model, "enableThinking", "true");
        assertDoesNotThrow(model::validateCredential);
        var old = new com.fasterxml.jackson.databind.ObjectMapper().readValue(
                "{\"phase\":\"MODEL_CALL\",\"thinkingEnabled\":true,\"transport\":\"CONFIGURED_HTTP\"}",
                com.aihospital.triage.domain.AnswerEvidence.Generation.class);
        assertNull(old.jsonObjectEnabled());
    }
    @Test void categoriesDistinguishMutationAndUnsafeTextWithoutRelaxingExistingGates() {
        var model = new OptionalNarrationModel();
        assertNull(model.structuredContentFailure("资料不足以确认个人情况。"));
        assertEquals("OUTPUT_UNSAFE_PATTERN", model.structuredContentFailure("你患有某疾病。"));
        assertNull(model.structuredContentFailure("具体用药由医生决定。"));
        assertEquals("OUTPUT_MEDICATION_FILTER_CHANGED", model.structuredContentFailure("建议服用止痛药。"));
        assertNull(model.structuredContentFailure("保持休息并补充温水；具体用药由医生评估。"));
        assertEquals("OUTPUT_MEDICATION_FILTER_CHANGED", model.structuredContentFailure("建议每日服用抗生素。"));
        assertEquals("OUTPUT_TEXT_SIZE_INVALID", model.structuredContentFailure("a".repeat(1201)));
    }
}
