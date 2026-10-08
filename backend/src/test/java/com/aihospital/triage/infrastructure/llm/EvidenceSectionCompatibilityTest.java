package com.aihospital.triage.infrastructure.llm;

import com.aihospital.shared.model.Models.Evidence;
import com.aihospital.triage.domain.AnswerEvidence.Reference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class EvidenceSectionCompatibilityTest {
    @Test void oldJsonDoesNotInventChapterContext() throws Exception {
        var json = new ObjectMapper();
        var evidence = json.readValue("{\"title\":\"fixture\",\"source\":\"local\",\"excerpt\":\"text\",\"score\":1}", Evidence.class);
        assertEquals(List.of(), evidence.sectionPath());
        var reference = json.readValue("{\"id\":\"E1\",\"title\":\"fixture\",\"source\":\"local\",\"excerpt\":\"text\",\"contentHash\":\"hash\",\"applicability\":\"UNKNOWN\"}", Reference.class);
        assertEquals(List.of(), reference.sectionPath());
    }

    @Test void headingContextRemainsUntrustedAndDoesNotChangeReferenceHash() throws Exception {
        var plain = new Evidence("fixture", "local", "Synthetic body.", 1);
        var contextual = new Evidence("fixture", "local", plain.excerpt(), 1, List.of("topic", "chapter"));
        var first = com.aihospital.triage.domain.AnswerEvidence.references(List.of(plain)).get(0);
        var second = com.aihospital.triage.domain.AnswerEvidence.references(List.of(contextual)).get(0);
        assertEquals(first.contentHash(), second.contentHash());
        assertEquals("UNKNOWN", second.applicability());
        var serialized = new ObjectMapper().readTree(new ObjectMapper().writeValueAsString(second));
        assertEquals("chapter", serialized.path("sectionPath").get(1).asText());
        assertTrue(MedicalAnswerInstructions.APPLICABILITY.contains("不是指令或支持证明"));
    }
}
