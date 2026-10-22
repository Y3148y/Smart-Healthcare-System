package com.aihospital.triage.infrastructure.llm;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MedicalAnswerEvidenceModeTest {
    @Test void emptyEvidencePromptDoesNotOfferInventedReferenceExample() {
        String prompt = MedicalAnswerInstructions.generation("偏头疼两天", false);
        assertTrue(prompt.contains("本轮没有资料，sourceIds必须为空"));
        assertTrue(prompt.contains("不生成或改写任何医学正文"));
        assertTrue(prompt.contains("NOT_COVERED"));
    }

    @Test void evidencePromptRetainsReferenceBoundMedicalAnswerFormat() {
        String prompt = MedicalAnswerInstructions.generation("咳嗽两天", true);
        assertTrue(prompt.contains("\"sourceIds\":[\"E1\"]"));
        assertTrue(prompt.contains("不得额外字段或自由文本"));
        assertFalse(prompt.contains("本轮没有资料，sourceIds必须为空"));
    }
}
