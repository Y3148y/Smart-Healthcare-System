package com.aihospital.triage.infrastructure.llm;

import com.aihospital.triage.domain.AnswerEvidence.Reference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

class SyntheticReviewDecisionTest {
    @Test void onlyBoundDecisionFieldsSurviveAndUnknownReasoningIsNotStored() throws Exception {
        var json=new ObjectMapper();
        var raw=json.writeValueAsString(Map.of("claimsSupported",false,"reasoning","secret reasoning",
                "paragraphChecks",List.of(Map.of("paragraphIndex",0,"reasoning","other reasoning","claims",List.of(
                        Map.of("claimId","P0S0","referenceId","E1","evidenceQuote","合成依据","supported",false,"conditionsPreserved",true,"reasoning","inner reasoning"),
                        Map.of("claimId","not a claim identity","referenceId","E2","evidenceQuote","unbound non-evidence content","supported",false,"conditionsPreserved",false))))));
        var safe=AnswerSupportReviewer.diagnosticDecision(raw,List.of(new Reference("E1","合成标题","https://example.test","合成依据",null,null)));
        String text=safe.toString();
        assertFalse(text.contains("reasoning"));assertFalse(text.contains("unbound non-evidence content"));assertFalse(text.contains("not a claim identity"));
        var claims=safe.path("paragraphChecks").get(0).path("claims");
        assertEquals("合成依据",claims.get(0).path("evidenceQuote").asText());
        assertEquals("NOT_BOUND",claims.get(1).path("evidenceQuoteStatus").asText());
        assertEquals(64,claims.get(1).path("evidenceQuoteHash").asText().length());
    }
    @Test void invalidAndOversizedDecisionsNeverFallBackToRawStorage() {
        for(String raw:List.of("reasoning: secret", "x".repeat(8001),"{} {}","{\"claimsSupported\":true,\"claimsSupported\":false}"))
            assertTrue(AnswerSupportReviewer.diagnosticDecision(raw,List.of()).isEmpty());
    }
}
