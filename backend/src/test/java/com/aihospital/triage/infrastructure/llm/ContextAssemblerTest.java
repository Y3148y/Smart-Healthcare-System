package com.aihospital.triage.infrastructure.llm;

import com.aihospital.triage.domain.AnswerEvidence.*;
import com.aihospital.triage.domain.NarrationModel.Turn;
import dev.langchain4j.data.message.UserMessage;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class ContextAssemblerTest {
    @Test void cumulativeBudgetBlocksGenerationAndReviewBeforeAnyProviderRequest(){
        try(var scope=com.aihospital.shared.diagnostics.TurnTraceContext.open("u","policy")){
            var allocated=new com.aihospital.shared.diagnostics.TurnTraceContext.Execution("CONTEXT","SYNTHETIC","BOUNDED_ESTIMATE",0,
                    null,null,null,null,"WORKFLOW",new com.aihospital.shared.diagnostics.TurnTraceContext.ContextBudget(500,65536,0,0,"UTF8_BYTES_WITH_FRAMING"));
            ContextAssembler.reserve(allocated,512);
            var model=new OptionalNarrationModel();
            ReflectionTestUtils.setField(model,"mode","openai-compatible");ReflectionTestUtils.setField(model,"apiKey","synthetic-test-key");
            ReflectionTestUtils.setField(model,"model","synthetic");
            var answer=model.answerWithEvidence("synthetic","","",List.of(),"fallback",List.of(),null,false,"NO_MATCH");
            assertEquals("CONTEXT_TURN_INPUT_BUDGET_EXCEEDED",answer.diagnostics().failure().code());
            assertEquals("PROMPT_PREPARATION",answer.diagnostics().generation().phase());
            var requests=new AtomicInteger();
            var review=AnswerSupportReviewer.review("synthetic",List.of(),List.of(),new Draft(List.of(),List.of(),List.of()),false,
                    messages->{requests.incrementAndGet();return null;});
            assertEquals(0,requests.get());assertEquals("REVIEW_CONTEXT_TURN_INPUT_BUDGET_EXCEEDED",review.failure().code());
        }
    }
    private Reference ref(String id,String text){return new Reference(id,"synthetic","local",text,"hash-"+id,"UNKNOWN");}

    @Test void oversizedCurrentIsRejectedRatherThanTruncated(){
        var error=assertThrows(ContextAssembler.BudgetExceeded.class,()->new ContextAssembler(65536)
                .assemble("instructions","x".repeat(4001),"",List.of()));
        assertEquals("CONTEXT_CURRENT_MESSAGE_TOO_LARGE",error.code);
    }
    @Test void rawEvidenceIsNeverSubstringClipped(){
        assertThrows(ContextAssembler.BudgetExceeded.class,()->new ContextAssembler(65536)
                .assemble("instructions","current","x".repeat(4001),List.of()));
    }
    @Test void referenceSelectionKeepsWholeChunksAndStableIds(){
        var second=ref("E2","complete conditional evidence");
        assertEquals(List.of(second),ContextAssembler.references(List.of(ref("E1","x".repeat(4001)),second)));
    }
    @Test void budgetDropsCompleteOldestRoundWithoutOrphaningAssistant(){
        var assembler=new ContextAssembler(65536);
        int mandatory=assembler.assemble("instructions","current","evidence",List.of()).estimatedInputTokens();
        var result=new ContextAssembler(mandatory).assemble("instructions","current","evidence",List.of(
                new Turn("USER","old"),new Turn("ASSISTANT","old question"),new Turn("USER","current")));
        assertEquals(2,result.historyMessagesRemoved());
        assertEquals(3,result.messages().size());
        assertEquals("current",((UserMessage)result.messages().get(2)).text());
    }
    @Test void rankedEvidenceIsRemovedWholeWhenMandatoryPayloadNeedsSpace(){
        var first=ref("E1","first complete condition");var second=ref("E2","second complete condition");
        int one=new ContextAssembler(65536).structured(b->"instructions","","current",List.of(first),List.of()).estimatedInputTokens();
        var bounded=new ContextAssembler(one).structured(b->"instructions","","current",List.of(first,second),List.of());
        assertEquals(List.of(first),bounded.references());assertEquals(1,bounded.referencesRemoved());
        assertEquals(one,bounded.estimatedInputTokens());
        assertTrue(((UserMessage)bounded.messages().get(1)).text().contains("hash-E1"));
        assertFalse(((UserMessage)bounded.messages().get(1)).text().contains("E2"));
    }
    @Test void droppingAllEvidenceIsNotMisrepresentedAsAnEmptyRetrieval(){
        var error=assertThrows(ContextAssembler.BudgetExceeded.class,()->new ContextAssembler(65536)
                .structured(b->"instructions","","current",List.of(ref("E1","x".repeat(4001))),List.of()));
        assertEquals("CONTEXT_EVIDENCE_BUDGET_EXCEEDED",error.code);
    }
    @Test void reviewOverBudgetNeverCallsProvider(){
        var calls=new AtomicInteger();
        var review=AnswerSupportReviewer.review("synthetic",List.of(),List.of(),
                new Draft(List.of(),List.of(),List.of()),false,512,messages->{calls.incrementAndGet();return null;});
        assertEquals(0,calls.get());assertEquals("UNAVAILABLE",review.status());
        assertEquals("REVIEW_CONTEXT_INPUT_BUDGET_EXCEEDED",review.failure().code());
    }
    @Test void generationBudgetFailureIsNotAProviderFailure(){
        var model=new OptionalNarrationModel();
        ReflectionTestUtils.setField(model,"mode","openai-compatible");
        ReflectionTestUtils.setField(model,"apiKey","synthetic-test-key");
        ReflectionTestUtils.setField(model,"model","synthetic");
        ReflectionTestUtils.setField(model,"inputTokenBudget",512);
        // No provider URL: prompt assembly must fail before constructing a client.
        var answer=model.answerWithEvidence("synthetic","","",List.of(),"fallback",List.of(),null,false,"NO_MATCH");
        assertEquals("VALIDATION_BLOCKED",answer.status());
        assertEquals("ContextAssembler",answer.diagnostics().failure().causeType());
        assertEquals("PROMPT_PREPARATION",answer.diagnostics().generation().phase());
        assertTrue(answer.diagnostics().adoptedReferenceIds().isEmpty());
    }
    @Test void invalidBudgetIsExplicit(){
        assertThrows(ContextAssembler.BudgetExceeded.class,()->new ContextAssembler(511));
        assertThrows(ContextAssembler.BudgetExceeded.class,()->new ContextAssembler(262145));
    }
    @Test void legacyAdaptersReportBudgetFailureBeforeClientConstruction(){
        var model=new OptionalNarrationModel();
        ReflectionTestUtils.setField(model,"mode","openai-compatible");
        ReflectionTestUtils.setField(model,"apiKey","synthetic-test-key");
        ReflectionTestUtils.setField(model,"model","synthetic");
        ReflectionTestUtils.setField(model,"inputTokenBudget",512);
        // Intentionally no provider URL: a premature client build would hide the budget failure.
        for(var answer:List.of(model.explain("current","","","evidence","fallback",List.of()),
                model.guide("current","","evidence","fallback",List.of()),
                model.guideGeneral("current","fallback",List.of()))){
            assertEquals("VALIDATION_BLOCKED",answer.status());
            assertEquals("CONTEXT_BLOCKED",answer.diagnostics().validationStatus());
            assertEquals("ContextAssembler",answer.diagnostics().failure().causeType());
            assertEquals("PROMPT_PREPARATION",answer.diagnostics().generation().phase());
        }
    }
    @Test void recentWindowKeepsQuestionsAndNeverExceedsFiveUserTurns(){
        var history=new java.util.ArrayList<Turn>();
        for(int i=0;i<7;i++){
            history.add(new Turn("USER","synthetic "+i));
            if(i<6)history.add(new Turn("ASSISTANT","question "+i));
        }
        var result=new ContextAssembler(65536).assemble("instructions","synthetic 6","",history);
        assertEquals(5,result.messages().stream().skip(2).filter(UserMessage.class::isInstance).count());
        assertEquals(4,result.historyMessagesRemoved());
        assertEquals("synthetic 2",((UserMessage)result.messages().get(2)).text());
        assertTrue(result.messages().get(3).toString().contains("question 2"));
    }
    @Test void noOrphanAssistantWhenCharacterWindowCutsEarlierUser(){
        var result=new ContextAssembler(65536).assemble("instructions","current","",List.of(
                new Turn("USER","x".repeat(3990)),new Turn("ASSISTANT","prior question"),new Turn("USER","current")));
        assertEquals(3,result.messages().size());assertEquals(2,result.historyMessagesRemoved());
    }
    @Test void demoDoesNotRunBudgetAssemblyOrCallProvider(){
        var model=new OptionalNarrationModel();
        ReflectionTestUtils.setField(model,"mode","demo");
        ReflectionTestUtils.setField(model,"inputTokenBudget",1);
        var answer=model.answerWithEvidence("synthetic","","",List.of(),"demo fallback",List.of(),null,false,"NO_MATCH");
        assertEquals("DEMO_UNGROUNDED",answer.status());assertEquals("demo fallback",answer.text());
    }
}
