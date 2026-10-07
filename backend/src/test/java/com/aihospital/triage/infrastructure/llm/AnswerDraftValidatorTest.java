package com.aihospital.triage.infrastructure.llm;

import com.aihospital.shared.model.Models.Evidence;
import com.aihospital.triage.domain.AnswerEvidence;
import com.aihospital.triage.domain.TriageRecords.ResponseProvenance;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class AnswerDraftValidatorTest {
    @Test void paragraphKindFailureHasSafeSpecificCode() {
        var error=assertThrows(AnswerDraftValidator.InvalidDraftException.class,
                ()->AnswerDraftValidator.check(draft("SERVICE_INFORMATION","[]"),refs));
        assertEquals("OUTPUT_PARAGRAPH_KIND_INVALID",error.code());
    }
    @Test void missingReferencesAndOversizeHaveDifferentCodes() {
        var missing=assertThrows(AnswerDraftValidator.InvalidDraftException.class,
                ()->AnswerDraftValidator.check(draft("LIMITATION","null"),refs));
        assertEquals("OUTPUT_REFERENCES_MISSING",missing.code());
        var tooLong=assertThrows(AnswerDraftValidator.InvalidDraftException.class,
                ()->AnswerDraftValidator.check(draft("LIMITATION","[]").replace("general reference","x".repeat(1201)),refs));
        assertEquals("OUTPUT_TEXT_SIZE_INVALID",tooLong.code());
    }
    private final List<AnswerEvidence.Reference> refs = AnswerEvidence.references(List.of(
            new Evidence("direction", "source", "Conditional direction, not personal stability", .8)));
    private String draft(String kind, String ids) {
        return "{\"paragraphs\":[{\"text\":\"general reference\",\"kind\":\"" + kind
                + "\",\"referenceIds\":" + ids + "}],\"questions\":[],\"uncovered\":[\"personal severity unknown\"]}";
    }
    @Test void adoptedIsDistinctFromRetrievedAndNotSemanticCertification() throws Exception {
        var checked = AnswerDraftValidator.check(draft("LIMITATION", "[]"), refs);
        assertEquals(1, refs.size()); assertTrue(checked.adopted().isEmpty());
        assertEquals("UNKNOWN", refs.get(0).applicability());
        assertEquals(64, refs.get(0).contentHash().length());
    }
    @Test void existingReferenceAccepted() throws Exception {
        assertEquals(List.of("E1"), AnswerDraftValidator.check(draft("GENERAL_INFORMATION", "[\"E1\"]"), refs).adopted());
    }
    @Test void unknownReferenceBlocked() {
        assertThrows(Exception.class, () -> AnswerDraftValidator.check(draft("GENERAL_INFORMATION", "[\"E9\"]"), refs));
    }
    @Test void unsupportedGeneralInformationBlocked() {
        assertThrows(Exception.class, () -> AnswerDraftValidator.check(draft("GENERAL_INFORMATION", "[]"), refs));
    }
    @Test void noEvidenceAllowsOnlyUncitedLimitationParagraph() throws Exception {
        var limitation = AnswerDraftValidator.check(draft("LIMITATION", "[]"), List.of());
        assertTrue(limitation.adopted().isEmpty());
        assertThrows(Exception.class, () -> AnswerDraftValidator.check(draft("GENERAL_INFORMATION", "[]"), List.of()));
    }
    @Test void patientConclusionKindBlocked() {
        assertThrows(Exception.class, () -> AnswerDraftValidator.check(draft("PATIENT_CONCLUSION", "[\"E1\"]"), refs));
    }
    @Test void plainTextAndExtraPropertiesBlocked() {
        assertThrows(Exception.class, () -> AnswerDraftValidator.check("plain text", refs));
        assertThrows(Exception.class, () -> AnswerDraftValidator.check(draft("LIMITATION", "[]").replace("\"questions\"", "\"risk\":\"ROUTINE\",\"questions\""), refs));
    }
    @Test void oldProvenanceReadableAndNewDiagnosticsRoundTrip() throws Exception {
        var json = new ObjectMapper();
        assertNull(json.readValue("{\"modelStatus\":\"LIVE\",\"knowledgeHits\":1,\"localToolCalls\":1,\"toolFailures\":0}", ResponseProvenance.class).answerEvidence());
        var diagnostic = new AnswerEvidence.Diagnostics("trace", "MATCHED", "REFERENCE_INTEGRITY_PASSED",
                refs, List.of(), List.of("unknown"), "NOT_VERIFIED");
        var original = new ResponseProvenance("LIVE", 1, 1, 0, diagnostic);
        assertEquals(original, json.readValue(json.writeValueAsString(original), ResponseProvenance.class));
    }
}
