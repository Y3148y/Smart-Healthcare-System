package com.aihospital.triage.infrastructure.llm;

import com.aihospital.triage.domain.AnswerEvidence;
import com.aihospital.triage.domain.NarrationModel;
import com.aihospital.shared.model.Models.Evidence;
import dev.langchain4j.data.message.*;
import dev.langchain4j.model.output.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class SourceSelectionReviewTest {
    final List<AnswerEvidence.Reference> refs=AnswerEvidence.references(List.of(
            new Evidence("Fixture A","source","Only if X. Original A.",1),new Evidence("Fixture B","source","Original B.",1)));
    SourceAnswerPlan.VerifiedPlan plan(String ids) throws Exception {
        return SourceAnswerPlan.check("{\"sourceIds\":"+ids+",\"transition\":\"REFERENCE\",\"questionKeys\":[],\"gap\":\"NONE\"}",refs);
    }
    @Test void onlyFactoryBoundImmutablePlansEnterSelectionReview() throws Exception {
        assertTrue(Arrays.stream(SourceAnswerPlan.VerifiedPlan.class.getDeclaredConstructors())
                .allMatch(c->java.lang.reflect.Modifier.isPrivate(c.getModifiers())));
        var bound=plan("[\"E1\"]");
        assertEquals(refs.get(0).excerpt(),bound.draft().paragraphs().get(0).text());
        assertThrows(UnsupportedOperationException.class,()->bound.draft().paragraphs().clear());
        assertThrows(UnsupportedOperationException.class,()->bound.draft().paragraphs().get(0).referenceIds().clear());
        assertThrows(UnsupportedOperationException.class,()->bound.references().clear());
        assertThrows(UnsupportedOperationException.class,()->bound.adopted().clear());
    }
    @Test void selectedIdsMustEachBeCoveredAndSemanticFalseNeverBecomesPassed() throws Exception {
        var bound=plan("[\"E1\",\"E2\"]");var ids=List.of("E1","E2");
        assertEquals("PASSED",AnswerSupportReviewer.parseSelection(AnswerSupportReviewerTest.selectionDecision(null,ids),bound).status());
        for(String code:List.of("OFF_TOPIC","CONDITION_NOT_ESTABLISHED","GAP_INACCURATE","QUESTION_NOT_JUSTIFIED","SERVICE_CONTENT")) {
            var result=AnswerSupportReviewer.parseSelection(AnswerSupportReviewerTest.selectionDecision(code,ids),bound);
            assertEquals("REJECTED",result.status());assertTrue(result.issues().contains(code));
        }
        String valid=AnswerSupportReviewerTest.selectionDecision(null,ids);
        for(String invalid:List.of(AnswerSupportReviewerTest.PASS,valid.replace("E2","E1"),valid.replace("E2","E9"),
                AnswerSupportReviewerTest.selectionDecision(null,List.of("E1")),valid.replace("true","\"true\""),
                valid.replace("\"gapAccurate\":true,",""),valid.replace("\"gapAccurate\":true","\"gapAccurate\":true,\"gapAccurate\":false"),
                valid.replace("\"onTopic\":true","\"onTopic\":true,\"extra\":true"),valid+" {}"))
            assertThrows(Exception.class,()->AnswerSupportReviewer.parseSelection(invalid,bound));
        assertEquals("REJECTED",AnswerSupportReviewer.parseSelection(valid.replace("\"conditionsPreserved\":true","\"conditionsPreserved\":false"),bound).status());
    }
    @Test void oneCallUsesSelectionContractAndKeepsPatientWindowBudgetUsageAndStopChecks() throws Exception {
        var bound=plan("[\"E1\"]");var count=new AtomicInteger();
        var result=AnswerSupportReviewer.reviewSelection("current",List.of(new NarrationModel.Turn("USER","synthetic"),new NarrationModel.Turn("ASSISTANT","not patient evidence")),
                bound,false,65536,262144,messages->{
                    count.incrementAndGet();String system=((SystemMessage)messages.get(0)).text(),payload=((UserMessage)messages.get(1)).singleText();
                    assertTrue(system.contains("适用"));assertTrue(system.contains("任何一处不满足"));
                    assertFalse(system.contains(AnswerSupportReviewer.PROOF_INSTRUCTIONS));
                    assertTrue(payload.contains("ATTRIBUTED_SOURCE_QUOTES"));assertTrue(payload.contains("selectedReferenceIds"));
                    assertFalse(payload.contains("claimUnits"));assertFalse(payload.contains("not patient evidence"));
                    return Response.from(AiMessage.from(AnswerSupportReviewerTest.selectionDecision(null,List.of("E1"))),new TokenUsage(7,9),FinishReason.STOP);
                });
        assertEquals(1,count.get());assertEquals("PASSED",result.status());assertEquals(7,result.inputTokens());assertEquals(9,result.outputTokens());
        var limited=AnswerSupportReviewer.reviewSelection("current",List.of(),bound,false,512,262144,messages->{fail("over-budget requests must not be sent");return null;});
        assertEquals("UNAVAILABLE",limited.status());assertTrue(limited.failure().code().contains("CONTEXT"));
        var truncated=AnswerSupportReviewer.reviewSelection("current",List.of(),bound,false,65536,262144,messages->Response.from(AiMessage.from("{}"),null,FinishReason.LENGTH));
        assertEquals("UNAVAILABLE",truncated.status());assertEquals("REVIEW_TRUNCATED",truncated.failure().code());
        var timedOut=AnswerSupportReviewer.reviewSelection("current",List.of(),bound,false,65536,262144,messages->{throw new java.util.concurrent.TimeoutException("private detail");});
        assertEquals("UNAVAILABLE",timedOut.status());assertFalse(timedOut.toString().contains("private detail"));
    }
    @Test void emptySelectionStillRequiresAccurateGapAndNecessaryQuestions() throws Exception {
        var bound=SourceAnswerPlan.check("{\"sourceIds\":[],\"transition\":\"NONE\",\"questionKeys\":[\"DURATION\"],\"gap\":\"NOT_COVERED\"}",refs);
        assertEquals("PASSED",AnswerSupportReviewer.parseSelection(AnswerSupportReviewerTest.selectionDecision(null,List.of()),bound).status());
        assertEquals("REJECTED",AnswerSupportReviewer.parseSelection(AnswerSupportReviewerTest.selectionDecision("GAP_INACCURATE",List.of()),bound).status());
        assertEquals("REJECTED",AnswerSupportReviewer.parseSelection(AnswerSupportReviewerTest.selectionDecision("QUESTION_NOT_JUSTIFIED",List.of()),bound).status());
    }
}
