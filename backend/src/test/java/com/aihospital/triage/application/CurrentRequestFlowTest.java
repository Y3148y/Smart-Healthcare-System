package com.aihospital.triage.application;
import com.aihospital.triage.domain.CurrentRequestIntent;
import com.aihospital.triage.domain.NarrationModel;
import com.aihospital.triage.domain.TriageRecords.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;
@SpringBootTest
class CurrentRequestFlowTest {
    @Test void evidenceDiagnosticsSurviveReloadAndLaterAssessment() {
        String p=patient(),id=create(p);
        var first=service.send(id,p,"我咳嗽");
        var firstAnswer=first.messages().get(first.messages().size()-1);
        assertNotNull(firstAnswer.provenance().answerEvidence());
        assertEquals("NOT_VERIFIED",firstAnswer.provenance().answerEvidence().semanticSupport());
        assertEquals(firstAnswer.provenance(), service.conversation(id,p).messages().get(1).provenance());
        var next=service.send(id,p,"咳嗽两天，咳白痰，我想挂号");
        assertFalse(next.assessments().isEmpty());
        var result=next.assessments().get(0).result();
        assertNotNull(result.answerEvidence());
        assertEquals(result.answerEvidence(), next.messages().get(next.messages().size()-1).provenance().answerEvidence());
        assertEquals(firstAnswer.provenance(),service.conversation(id,p).messages().get(1).provenance());
    }
    @Autowired TriageConversationService service;
    private String patient(){return "intent-test-"+UUID.randomUUID();}
    private String create(String p){return service.create(p,new Eligibility(true,true,true)).session().id();}
    private String model(Conversation c){return c.messages().get(c.messages().size()-1).provenance().modelStatus();}
    @Test void oldPrescriptionRequestDoesNotBlockLaterBookingDirection(){
        String p=patient(),id=create(p);
        assertEquals("POLICY_REFUSAL",model(service.send(id,p,"咳嗽两天，请给我开药")));
        var next=service.send(id,p,"我只想挂号，咳嗽两天，没有胸痛，也没有呼吸困难");
        assertNotEquals("POLICY_REFUSAL",model(next));assertFalse(next.assessments().isEmpty());
        assertEquals("呼吸内科",next.assessments().get(0).result().department());
        assertEquals("咳嗽两天，请给我开药",next.messages().get(0).content());
    }
    @Test void explicitWithdrawalDoesNotHideAnotherActiveRequest(){
        assertFalse(CurrentRequestIntent.restricted("不用开药了，我只想挂号"));
        assertTrue(CurrentRequestIntent.restricted("不用开药，但是请开处方"));
        assertTrue(CurrentRequestIntent.restricted("不要开药，只想知道用什么药"));
        assertEquals("我只想挂号",CurrentRequestIntent.latest(List.of(new NarrationModel.Turn("USER","给我开药"),new NarrationModel.Turn("ASSISTANT","拒绝"),new NarrationModel.Turn("USER","我只想挂号")),"fallback"));
    }
    @Test void renewedPrescriptionRequestRemainsRefused(){
        String p=patient(),id=create(p);service.send(id,p,"咳嗽两天，没有胸痛，我想挂号");
        var next=service.send(id,p,"给我开处方");assertEquals("POLICY_REFUSAL",model(next));
    }
    @Test void requestWithdrawalKeepsSafetyGate(){
        String p=patient(),id=create(p);var c=service.send(id,p,"不要开药，我胸痛，喘不过气");
        assertEquals("SAFETY_RULE",model(c));assertFalse(c.assessments().isEmpty());assertNull(c.assessments().get(0).result().doctor());
        assertTrue(c.assessments().get(0).result().safetyAssessment().stopRoutineFlow());
    }
    @Test void declinedBookingDoesNotCreateAssessmentEvenWhenClinicalQualifiersArePresent(){
        String p=patient(),id=create(p);
        var c=service.send(id,p,"咳嗽两天，咳白痰，暂时不挂号，只问日常注意事项");
        assertTrue(c.assessments().isEmpty());
        assertNotEquals("POLICY_REFUSAL",model(c));
        assertEquals("ASSISTANT",c.messages().get(c.messages().size()-1).role());
    }
    @Test void currentChoiceCanSwitchInBothDirectionsWithoutChangingHistory(){
        String p=patient(),id=create(p);
        assertTrue(service.send(id,p,"咳嗽两天，咳白痰，不预约").assessments().isEmpty());
        var requested=service.send(id,p,"现在我要挂号");
        assertEquals(1,requested.assessments().size());
        var original=requested.assessments().get(0);
        var declined=service.send(id,p,"不预约了，只问日常注意事项");
        assertEquals(1,declined.assessments().size());
        assertEquals(original,declined.assessments().get(0));
        var requestedAgain=service.send(id,p,"现在想预约");
        assertEquals(2,requestedAgain.assessments().size());
        assertEquals(original,requestedAgain.assessments().get(0));
        assertEquals("呼吸内科",requestedAgain.assessments().get(1).result().department());
    }
    @Test void decliningBookingDoesNotSuppressEmergency(){
        String p=patient(),id=create(p);
        var c=service.send(id,p,"不挂号，我胸痛，喘不过气");
        assertEquals("SAFETY_RULE",model(c));
        assertEquals("紧急",c.assessments().get(0).result().riskLevel());
        assertNull(c.assessments().get(0).result().doctor());
    }
    @Test void decliningBookingDoesNotSuppressUrgentOfflineAdvice(){
        String p=patient(),id=create(p);
        var c=service.send(id,p,"我手摔断了，不预约");
        assertFalse(c.assessments().isEmpty());
        assertEquals("尽快就医",c.assessments().get(0).result().riskLevel());
        assertNull(c.assessments().get(0).result().doctor());
    }
    @Test void decliningBookingDoesNotBypassBleedingClarificationOrPrescriptionPolicy(){
        String p=patient(),id=create(p);
        assertEquals("CLARIFICATION",model(service.send(id,p,"明显出血，不预约")));
        String p2=patient(),id2=create(p2);
        assertEquals("POLICY_REFUSAL",model(service.send(id2,p2,"不预约，给我开药")));
    }
    @Test void directionQuestionIsStillAnsweredWithoutInventingNewMedicalMapping(){
        String p=patient(),id=create(p);
        var c=service.send(id,p,"咳嗽两天，挂什么科");
        assertFalse(c.assessments().isEmpty());
        assertEquals("呼吸内科",c.assessments().get(0).result().department());
        String p2=patient(),id2=create(p2);
        var declined=service.send(id2,p2,"咳嗽两天，咳白痰，不挂号，只想知道挂什么科");
        assertTrue(declined.assessments().isEmpty());
        assertNotEquals("POLICY_REFUSAL",model(declined));
    }
}
