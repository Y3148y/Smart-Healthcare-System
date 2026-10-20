package com.aihospital.triage.infrastructure.llm;
import com.aihospital.triage.domain.AnswerEvidence.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
class ClaimProofReviewTest {
    @Test void serverBoundUnitIdsCoverTheExactDraftWithoutAskingTheReviewerToRecopyIt() throws Exception {
        String text="条件A成立时，才描述现象A。\n\n数值3.5不应在小数点切开！最后一项😀 ";
        var source=new Draft(List.of(new Paragraph(text,"GENERAL_INFORMATION",List.of("E1"))),List.of(),List.of());
        var units=AnswerSupportReviewer.claimUnits(source);
        assertEquals(3,units.size());assertEquals(text,units.stream().map(AnswerSupportReviewer.ClaimUnit::text).collect(java.util.stream.Collectors.joining()));
        var matching=List.of(new Reference("E1","synthetic source","source",text,"hash","UNKNOWN"));
        var claims=units.stream().map(unit->Map.of("claimId",unit.id(),"referenceId","E1","evidenceQuote",unit.text().strip(),"supported",true,"conditionsPreserved",true)).toList();
        var root=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(AnswerSupportReviewerTest.PASS);
        root.set("paragraphChecks",json.valueToTree(List.of(Map.of("paragraphIndex",0,"claims",claims))));
        assertEquals("PASSED",AnswerSupportReviewer.parseWithProof(json.writeValueAsString(root),source,matching).status());
        for(var changed:List.of(claims.subList(0,2),List.of(claims.get(0),claims.get(0),claims.get(2)))) {
            root.set("paragraphChecks",json.valueToTree(List.of(Map.of("paragraphIndex",0,"claims",changed))));
            assertThrows(Exception.class,()->AnswerSupportReviewer.parseWithProof(json.writeValueAsString(root),source,matching));
        }
        root.set("paragraphChecks",json.valueToTree(List.of(Map.of("paragraphIndex",0,"claims",claims))));
        assertThrows(Exception.class,()->AnswerSupportReviewer.parseWithProof(json.writeValueAsString(root).replace("P0S0","P1S0"),source,matching));
        assertEquals("REJECTED",AnswerSupportReviewer.parseWithProof(json.writeValueAsString(root).replace("\"supported\":true","\"supported\":false"),source,matching).status());
    }
    @Test void boundUnitPromptKeepsFullContextAndStillRequiresExactSourceQuotes() {
        var decision=AnswerSupportReviewer.review("question",List.of(),refs,draft,false,65536,262144,true,messages->{
            String data=((dev.langchain4j.data.message.UserMessage)messages.get(1)).singleText();
            assertTrue(data.contains("claimUnits"));assertTrue(data.contains("P0S0"));assertTrue(data.contains(draft.paragraphs().get(0).text()));
            String result=proof(draft.paragraphs().get(0).text(),"E1","第一阶段",true,true)
                    .replace("\"text\":\"第一阶段可出现现象A。\"","\"claimId\":\"P0S0\"");
            return dev.langchain4j.model.output.Response.from(dev.langchain4j.data.message.AiMessage.from(result),null,dev.langchain4j.model.output.FinishReason.STOP);
        });
        assertEquals("PASSED",decision.status());
        var excessive=new Draft(List.of(new Paragraph("一句。".repeat(25),"GENERAL_INFORMATION",List.of("E1"))),List.of(),List.of());
        assertThrows(Exception.class,()->AnswerSupportReviewer.claimUnits(excessive));
    }
    @Test void twoParagraphIdentitiesCannotBeSwappedAndMixedIdentityFormatsCannotHideMissingText() throws Exception {
        var two=new Draft(List.of(new Paragraph("第一阶段可出现现象A。","GENERAL_INFORMATION",List.of("E1")),
                new Paragraph("第二阶段描述现象B。","GENERAL_INFORMATION",List.of("E1"))),List.of(),List.of());
        var root=(com.fasterxml.jackson.databind.node.ObjectNode)json.readTree(AnswerSupportReviewerTest.PASS);
        var checks=root.putArray("paragraphChecks");
        for(var unit:AnswerSupportReviewer.claimUnits(two))checks.addObject().put("paragraphIndex",unit.paragraphIndex()).putArray("claims")
                .addObject().put("claimId",unit.id()).put("referenceId","E1").put("evidenceQuote",unit.text()).put("supported",true).put("conditionsPreserved",true);
        assertEquals("PASSED",AnswerSupportReviewer.parseWithProof(json.writeValueAsString(root),two,refs).status());
        ((com.fasterxml.jackson.databind.node.ObjectNode)checks.get(0).path("claims").get(0)).put("claimId","P1S0");
        ((com.fasterxml.jackson.databind.node.ObjectNode)checks.get(1).path("claims").get(0)).put("claimId","P0S0");
        assertThrows(Exception.class,()->AnswerSupportReviewer.parseWithProof(json.writeValueAsString(root),two,refs));
        var one=new Draft(List.of(new Paragraph(two.paragraphs().get(0).text()+two.paragraphs().get(1).text(),"GENERAL_INFORMATION",List.of("E1"))),List.of(),List.of());
        for(int mode=0;mode<3;mode++) {
            var items=json.createArrayNode();
            for(int sentence=0;sentence<2;sentence++) {
                var item=items.addObject().put("referenceId","E1").put("evidenceQuote",two.paragraphs().get(sentence).text()).put("supported",true).put("conditionsPreserved",true);
                if((mode==0&&sentence==0)||(mode==1&&sentence==1)||mode==2)item.put("claimId","P0S"+sentence);
                if((mode==0&&sentence==1)||(mode==1&&sentence==0)||mode==2)item.put("text",two.paragraphs().get(sentence).text());
            }
            root.set("paragraphChecks",json.valueToTree(List.of(Map.of("paragraphIndex",0,"claims",items))));
            assertThrows(Exception.class,()->AnswerSupportReviewer.parseWithProof(json.writeValueAsString(root),one,refs));
        }
    }
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
