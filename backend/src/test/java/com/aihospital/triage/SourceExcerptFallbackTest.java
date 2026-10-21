package com.aihospital.triage;

import com.aihospital.knowledge.domain.*;
import com.aihospital.shared.model.Models.*;
import com.aihospital.triage.application.*;
import com.aihospital.triage.domain.*;
import com.aihospital.triage.infrastructure.llm.OptionalNarrationModel;
import com.aihospital.knowledge.infrastructure.demo.InMemoryKnowledgeCatalog;
import com.aihospital.knowledge.infrastructure.qdrant.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class SourceExcerptFallbackTest {
    final InMemoryKnowledgeCatalog local=new InMemoryKnowledgeCatalog(false);
    final KnowledgeCatalog catalog=new HybridKnowledgeCatalog(local,mock(QdrantSemanticIndex.class),null);
    final OptionalNarrationModel narration=new OptionalNarrationModel();
    List<Evidence> source(String title,String body) {
        var doc=local.addDocument(title,body);local.approveDocument(doc.id());
        return local.documentChunks(doc.id()).stream().map(c->new Evidence(title,c.source(),c.text(),.9,c.sectionPath())).toList();
    }
    NarrationModel.Answer rejected(List<Evidence> evidence,List<String> issues) {
        return new NarrationModel.Answer("REJECTED_DRAFT_MUST_NEVER_LEAK","VALIDATION_BLOCKED","fixture-model",
                new AnswerEvidence.Diagnostics(UUID.randomUUID().toString(),"MATCHED","SUPPORT_BLOCKED",AnswerEvidence.references(evidence),
                        List.of(),List.of("REJECTED_UNCOVERED_MUST_NEVER_LEAK"),"NOT_CONFIRMED",
                        new AnswerEvidence.Failure("OUTPUT_SUPPORT_REJECTED","SupportReviewer",null),15L,
                        new AnswerEvidence.Generation("OUTPUT_VALIDATION","STOP",20,30,100),
                        new AnswerEvidence.SupportReview("REJECTED",issues,5L,null,false,11,12),
                        new AnswerEvidence.ServiceNotice(List.of("Synthetic directory query succeeded"),true)));
    }
    NarrationModel.Answer excerpt(List<Evidence> e) {return SourceExcerptFallback.apply(catalog,narration,e,true,rejected(e,List.of("WRONG_REFERENCE")));}
    @Test void returnsOnlyExactWholeSourcesAndKeepsFailureReviewUsageAndContext() {
        var evidence=source("Fixture","# Scope\nOnly general information when condition X holds.\n\nKeep the condition intact.");
        var original=rejected(evidence,List.of("UNSUPPORTED_CLAIM","WRONG_REFERENCE"));
        var result=SourceExcerptFallback.apply(catalog,narration,evidence,true,original);
        assertEquals("SOURCE_EXCERPT",result.status());assertTrue(result.text().contains(evidence.get(0).excerpt()));
        assertTrue(result.text().contains("Fixture · Scope"));assertTrue(result.text().contains("不是对您个人情况的判断"));
        assertFalse(result.toString().contains("MUST_NEVER_LEAK"));assertEquals(List.of("E1"),result.diagnostics().adoptedReferenceIds());
        assertEquals("SOURCE_EXCERPT_VERIFIED",result.diagnostics().validationStatus());assertEquals("SOURCE_TEXT_ONLY",result.diagnostics().semanticSupport());
        assertSame(original.diagnostics().supportReview(),result.diagnostics().supportReview());assertSame(original.diagnostics().failure(),result.diagnostics().failure());
        assertEquals(30,result.diagnostics().generation().outputTokens());
        assertEquals(false,result.diagnostics().serviceNotice().bookingAllowed());
        assertEquals(original.diagnostics().serviceNotice().messages(),result.diagnostics().serviceNotice().messages());
        var audit=com.aihospital.observation.application.AnswerCallAudit.records("fixture","fixture",result,20,List.of());
        assertTrue(audit.stream().noneMatch(CallLog::success));
    }
    @Test void offTopicServiceQuestionAndUnknownIssuesDoNotOpenTheFallback() {
        var e=source("Fixture","Whole original content.");
        for(var code:List.of("OFF_TOPIC","SERVICE_CONTENT","QUESTION_NOT_JUSTIFIED","UNKNOWN")) {
            var original=rejected(e,List.of("WRONG_REFERENCE",code));
            assertSame(original,SourceExcerptFallback.apply(catalog,narration,e,true,original));
        }
        var empty=rejected(e,List.of());assertSame(empty,SourceExcerptFallback.apply(catalog,narration,e,true,empty));
    }
    @Test void missingGroundingOrUntrackedApprovalNeverFallsBack() {
        var e=source("Fixture","Whole original content.");var original=rejected(e,List.of("WRONG_REFERENCE"));
        assertSame(original,SourceExcerptFallback.apply(catalog,narration,e,false,original));
        assertSame(original,SourceExcerptFallback.apply(null,narration,e,true,original));
        var unsupported=mock(KnowledgeCatalog.class);
        assertSame(original,SourceExcerptFallback.apply(unsupported,narration,e,true,original));
        local.withdrawDocument(local.documents().stream().filter(d->d.title().equals("Fixture")).findFirst().orElseThrow().id());
        assertSame(original,SourceExcerptFallback.apply(catalog,narration,e,true,original));
    }
    @Test void medicationAndUnsafeContentAreRejectedAsWholeChunksNotEdited() {
        for(String text:List.of("普通说明。建议服用某药片。保留条件。","你患有某病。","routineBookingAllowed=true")) {
            var e=source("Fixture "+text.hashCode(),text);var original=rejected(e,List.of("WRONG_REFERENCE"));
            assertFalse(narration.sourceExcerptAllowed(text));
            assertSame(original,SourceExcerptFallback.apply(catalog,narration,e,true,original));
        }
    }
    @Test void longParagraphSlicesCannotBecomeWholeSourceQuotations() {
        var e=source("Long fixture","Only if X. "+"Long original sentence without a paragraph break. ".repeat(40));
        assertTrue(e.size()>1);
        for(var item:e)assertEquals(KnowledgeCatalog.ExcerptState.PARTIAL_PARAGRAPH,catalog.sourceExcerptState(item));
        var original=rejected(e,List.of("WRONG_REFERENCE"));assertSame(original,SourceExcerptFallback.apply(catalog,narration,e,true,original));
    }
    @Test void ambiguousSourceIdentitiesFailClosedAndNoMissingReferenceCanBeAdded() {
        var e=source("Duplicate fixture","Whole original content.");
        source("Duplicate fixture","Whole original content.");
        assertEquals(KnowledgeCatalog.ExcerptState.UNVERIFIABLE,catalog.sourceExcerptState(e.get(0)));
        var original=rejected(e,List.of("WRONG_REFERENCE"));assertSame(original,SourceExcerptFallback.apply(catalog,narration,e,true,original));
        var sent=source("Other fixture","Other whole content.");var mismatched=rejected(sent,List.of("WRONG_REFERENCE"));
        assertSame(mismatched,SourceExcerptFallback.apply(catalog,narration,e,true,mismatched));
    }
    @Test void atMostTwoWholeChunksAndTwelveHundredCharactersAreDisplayed() {
        var e=new ArrayList<Evidence>();
        for(int i=0;i<4;i++)e.addAll(source("Fixture "+i,"A".repeat(400)));
        var result=excerpt(e);assertEquals("SOURCE_EXCERPT",result.status());
        assertEquals(List.of("E1","E2"),result.diagnostics().adoptedReferenceIds());assertTrue(result.text().length()<=1200);
        assertFalse(result.text().contains("资料摘录 E3"));
    }
    @Test void finalFreshnessStillHidesExtractedTextOnWithdrawal() {
        var e=source("Withdraw fixture","Whole original content.");var result=excerpt(e);
        assertEquals("SOURCE_EXCERPT",result.status());
        local.withdrawDocument(local.documents().stream().filter(d->d.title().equals("Withdraw fixture")).findFirst().orElseThrow().id());
        var checked=AnswerEvidenceFreshness.check(catalog,e,result);
        assertTrue(checked.blocked());assertFalse(checked.answer().text().contains("Whole original content"));
        assertTrue(checked.answer().diagnostics().adoptedReferenceIds().isEmpty());
    }
    @Test void unavailableNonStopNoMatchAndOtherTerminalStatesNeverBecomeExcerpts() throws Exception {
        var e=source("Gate fixture","Whole original content.");var json=new com.fasterxml.jackson.databind.ObjectMapper();
        List<java.util.function.Consumer<com.fasterxml.jackson.databind.node.ObjectNode>> changes=List.of(
            n->n.put("status","SAFETY_RULE"),n->n.put("status","POLICY_REFUSAL"),n->n.put("status","DEMO"),n->n.put("status","LIVE"),
            n->((com.fasterxml.jackson.databind.node.ObjectNode)n.path("diagnostics")).put("retrievalStatus","NO_MATCH"),
            n->((com.fasterxml.jackson.databind.node.ObjectNode)n.path("diagnostics")).put("retrievalStatus","DEPENDENCY_UNAVAILABLE"),
            n->((com.fasterxml.jackson.databind.node.ObjectNode)n.path("diagnostics")).put("validationStatus","CONTENT_BLOCKED"),
            n->((com.fasterxml.jackson.databind.node.ObjectNode)n.path("diagnostics").path("generation")).put("finishReason","LENGTH"),
            n->((com.fasterxml.jackson.databind.node.ObjectNode)n.path("diagnostics").path("supportReview")).put("status","UNAVAILABLE"),
            n->((com.fasterxml.jackson.databind.node.ObjectNode)n.path("diagnostics").path("failure")).put("code","OUTPUT_UNSAFE_PATTERN"));
        for(var change:changes) {
            com.fasterxml.jackson.databind.node.ObjectNode node=json.valueToTree(rejected(e,List.of("WRONG_REFERENCE")));change.accept(node);
            var original=json.treeToValue(node,NarrationModel.Answer.class);
            assertSame(original,SourceExcerptFallback.apply(catalog,narration,e,true,original));
        }
        var original=rejected(e,List.of("WRONG_REFERENCE"));
        assertSame(original,SourceExcerptFallback.apply(catalog,mock(NarrationModel.class),e,true,original));
    }
    @Test void malformedOrDuplicateReferencesFailClosedEvenBeyondTheDisplayLimit() throws Exception {
        var e=new ArrayList<Evidence>();for(int i=0;i<3;i++)e.addAll(source("Refs fixture "+i,"Whole fixture "+i+"."));
        var json=new com.fasterxml.jackson.databind.ObjectMapper();
        for(String defect:List.of("hash","duplicate","path","missing")) {
            com.fasterxml.jackson.databind.node.ObjectNode node=json.valueToTree(rejected(e,List.of("WRONG_REFERENCE")));
            var refs=(com.fasterxml.jackson.databind.node.ArrayNode)node.path("diagnostics").path("retrievedReferences");
            var last=(com.fasterxml.jackson.databind.node.ObjectNode)refs.get(2);
            switch(defect) {
                case "hash"->last.put("contentHash","0".repeat(64));
                case "duplicate"->last.put("id","E1");
                case "path"->last.putArray("sectionPath").add("unmatched section");
                case "missing"->last.put("source","unknown source");
            }
            var original=json.treeToValue(node,NarrationModel.Answer.class);
            assertSame(original,SourceExcerptFallback.apply(catalog,narration,e,true,original),defect);
        }
    }
    @Test void guidanceUsesFallbackButNeverAddsAnotherModelRequestAndFinalSourceFailureWins() {
        var e=source("Flow fixture","Whole original flow content.");
        var model=mock(NarrationModel.class);
        when(model.sourceExcerptAllowed(anyString())).thenReturn(true);
        when(model.answerWithEvidence(anyString(),anyString(),anyString(),anyList(),anyString(),anyList(),any(),eq(true),anyString()))
                .thenReturn(rejected(e,List.of("WRONG_REFERENCE")));
        var tools=mock(com.aihospital.tools.application.HospitalToolExecutor.class);
        var prepared=new TriageEngine.PreparedEvidence(new KnowledgeCatalog.Retrieval(e,true,"MATCHED"),
                new ToolTrace("medical_knowledge_retrieve","fixture","","MATCHED",1,true,""));
        var calls=mock(com.aihospital.observation.domain.CallLogStore.class);
        var result=new GuidanceAnswerService(tools,model,calls,catalog).answer("fixture","fixture","",List.of(),stage->{},prepared,refs->"fallback");
        assertEquals("SOURCE_EXCERPT",result.modelStatus());assertTrue(result.text().contains(e.get(0).excerpt()));
        verify(model,times(1)).answerWithEvidence(anyString(),anyString(),anyString(),anyList(),anyString(),anyList(),any(),eq(true),anyString());verifyNoInteractions(tools);
        var changing=spy(catalog);
        doAnswer(invocation->{var state=catalog.sourceExcerptState(e.get(0));local.withdrawDocument(local.documents().stream().filter(d->d.title().equals("Flow fixture")).findFirst().orElseThrow().id());return state;})
                .when(changing).sourceExcerptState(e.get(0));
        var blocked=new GuidanceAnswerService(tools,model,calls,changing).answer("fixture","fixture","",List.of(),stage->{},prepared,refs->"fallback");
        assertEquals("VALIDATION_BLOCKED",blocked.modelStatus());assertEquals(0,blocked.knowledgeHits());assertFalse(blocked.text().contains(e.get(0).excerpt()));
    }
    @Test void triageExcerptsDoNotExposeDoctorsAndEmergencyStillBypassesNarration() {
        var e=source("头痛资料","一般信息，条件未知时不能据此作个人判断。");
        var model=mock(NarrationModel.class);when(model.sourceExcerptAllowed(anyString())).thenReturn(true);
        when(model.answerWithEvidence(anyString(),anyString(),anyString(),anyList(),anyString(),anyList(),any(),eq(false),anyString()))
                .thenReturn(rejected(e,List.of("WRONG_REFERENCE")));
        var safety=new TriageSafetyPolicy();var directory=mock(com.aihospital.catalog.domain.DoctorDirectory.class);
        var doctor=new Doctor("fixture","Fixture doctor","fixture","神经内科","AM",java.time.LocalDate.now().toString(),5,5,0);
        when(directory.doctors(anyString())).thenReturn(List.of(doctor));
        var calls=new com.aihospital.observation.infrastructure.demo.InMemoryCallLogStore();
        var tools=spy(new com.aihospital.tools.application.HospitalToolExecutor(new com.aihospital.tools.infrastructure.demo.InMemoryToolRegistry(),directory,catalog,safety,calls));
        doReturn(new com.aihospital.tools.application.HospitalToolExecutor.Execution(new KnowledgeCatalog.Retrieval(e,true,"MATCHED"),
                new ToolTrace("medical_knowledge_retrieve","fixture","","MATCHED",1,true,""))).when(tools).execute(eq("medical_knowledge_retrieve"),anyMap());
        var engine=new com.aihospital.triage.infrastructure.demo.RuleBasedTriageEngine(safety,directory,catalog,model,calls,tools,
                new com.aihospital.triage.infrastructure.llm.StructuredDecisionModel("demo","","","",5));
        var result=engine.triage("fixture","头痛三天，想挂号","fixture",List.of());
        assertEquals("SOURCE_EXCERPT",result.modelStatus());assertNull(result.doctor());assertTrue(result.candidates().stream().allMatch(c->c.doctor()==null));
        var review=engine.triage("fixture","我可能怀孕，想挂号","fixture",List.of());
        assertEquals(Disposition.URGENT,review.riskLevel());assertNotEquals("SOURCE_EXCERPT",review.modelStatus());assertNull(review.doctor());
        clearInvocations(model);var urgent=engine.triage("fixture","突然剧烈胸痛，呼吸困难，出冷汗","fixture",List.of());
        assertEquals("SAFETY_RULE",urgent.modelStatus());assertNull(urgent.doctor());verifyNoInteractions(model);
    }
}
