package com.aihospital.triage.infrastructure.llm;
import com.aihospital.triage.domain.AnswerEvidence.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class ClaimProofReviewTest {
    private final ObjectMapper json=new ObjectMapper();
    private final Draft draft=new Draft(List.of(new Paragraph("第一阶段可出现现象A。","GENERAL_INFORMATION",List.of("E1"))),List.of(),List.of());
    private final List<Reference> refs=List.of(new Reference("E1","阶段资料","source","第一阶段可出现现象A。第二阶段描述现象B。","hash","UNKNOWN"));
    private String proof(String text,String id,String quote,boolean supported,boolean conditions)throws Exception{
        var root=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(AnswerSupportReviewerTest.PASS);
        root.set("paragraphChecks",json.valueToTree(List.of(Map.of("paragraphIndex",0,"claims",List.of(Map.of(
                "text",text,"referenceId",id,"evidenceQuote",quote,"supported",supported,"conditionsPreserved",conditions))))));
        return json.writeValueAsString(root);
    }
    @Test void evidenceProofRequiresCompleteParagraphCoverageAndLiteralAdoptedSourceQuote()throws Exception{
        assertEquals("PASSED",AnswerSupportReviewer.parseWithProof(proof(draft.paragraphs().get(0).text(),"E1","第一阶段可出现现象A。",true,true),draft,refs).status());
        assertThrows(Exception.class,()->AnswerSupportReviewer.parseWithProof(AnswerSupportReviewerTest.PASS,draft,refs));
        assertThrows(Exception.class,()->AnswerSupportReviewer.parseWithProof(proof("第一阶段","E1","第一阶段",true,true),draft,refs));
        assertEquals("REJECTED",AnswerSupportReviewer.parseWithProof(proof(draft.paragraphs().get(0).text(),"E2","第一阶段",true,true),draft,refs).status());
        assertEquals("REJECTED",AnswerSupportReviewer.parseWithProof(proof(draft.paragraphs().get(0).text(),"E1","伪造的资料原句",true,true),draft,refs).status());
    }
    @Test void explicitConditionOrSupportFailureRejectsEvenWhenSixOldChecksAreAllTrue()throws Exception{
        assertTrue(AnswerSupportReviewer.PROOF_INSTRUCTIONS.contains("前一阶段"));
        for(boolean[] values:List.of(new boolean[]{false,true},new boolean[]{true,false}))
            assertEquals("REJECTED",AnswerSupportReviewer.parseWithProof(proof(draft.paragraphs().get(0).text(),"E1","第一阶段",values[0],values[1]),draft,refs).status());
    }
    @Test void overflowingParagraphIdentityCannotWrapToAValidIndex() throws Exception {
        String valid=proof(draft.paragraphs().get(0).text(),"E1","第一阶段",true,true);
        for(String index:List.of("4294967296","-4294967296"))
            assertThrows(Exception.class,()->AnswerSupportReviewer.parseWithProof(valid.replace("\"paragraphIndex\":0","\"paragraphIndex\":"+index),draft,refs));
    }
}
