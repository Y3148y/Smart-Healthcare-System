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
}
