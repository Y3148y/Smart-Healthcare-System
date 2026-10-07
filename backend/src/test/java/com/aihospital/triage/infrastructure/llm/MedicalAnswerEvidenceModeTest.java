package com.aihospital.triage.infrastructure.llm;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class MedicalAnswerEvidenceModeTest {
    @Test void emptyEvidencePromptDoesNotOfferInventedReferenceExample() {
        String prompt = MedicalAnswerInstructions.generation("偏头疼两天", false);
        assertTrue(prompt.contains("本轮没有可引用片段"));
        assertTrue(prompt.contains("\"kind\":\"LIMITATION\",\"referenceIds\":[]"));
        assertFalse(prompt.contains("\"referenceIds\":[\"E1\"]"));
    }

    @Test void evidencePromptRetainsReferenceBoundMedicalAnswerFormat() {
        String prompt = MedicalAnswerInstructions.generation("咳嗽两天", true);
        assertTrue(prompt.contains("\"kind\":\"GENERAL_INFORMATION\",\"referenceIds\":[\"E1\"]"));
        assertFalse(prompt.contains("本轮没有可引用片段"));
    }
}
