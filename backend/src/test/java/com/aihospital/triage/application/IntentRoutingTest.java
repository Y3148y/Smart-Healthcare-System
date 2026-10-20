package com.aihospital.triage.application;
import com.aihospital.triage.domain.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static com.aihospital.triage.domain.IntentExtraction.*;
import static org.junit.jupiter.api.Assertions.*;
class IntentRoutingTest {
    private IntentInput input(){return new IntentInput(List.of(new IntentInput.SourceMessage("now","USER","想咨询目前的不适")));}
    @Test void compositeRoutesDoNotGrantBookingOrDropTheMedicalIntent() {
        var result=IntentRouting.decide(new IntentExtraction(1,List.of(Intent.SYMPTOM_CONSULT,Intent.FIND_DOCTOR,Intent.BOOKING_REQUEST),List.of(),List.of(),List.of()),SessionFacts.empty(),input());
        assertEquals(Set.of(IntentRouting.Route.MEDICAL_RETRIEVAL,IntentRouting.Route.SCHEDULES,IntentRouting.Route.BOOKING_CONFIRMATION),result.routes());
        assertTrue(result.questions().isEmpty());
    }
    @Test void explicitHumanRequestIsLocalAndHistoricalCurrentOnlyCausesAQuestionNotAFact() {
        var extraction=new IntentExtraction(1,List.of(Intent.HUMAN_HELP,Intent.GENERAL_HEALTH),List.of(),List.of(),List.of());
        assertEquals(Set.of(IntentRouting.Route.HUMAN_HELP),IntentRouting.decide(extraction,SessionFacts.empty(),input()).routes());
        var old=new SymptomEvent("不适",null,null,null,null,null,Subject.SELF,Temporality.CURRENT,Assertion.AFFIRMED,List.of(new SourceRef("old","以前的不适")));
        var current=new IntentExtraction(1,List.of(Intent.SYMPTOM_CONSULT),List.of(),List.of(Field.SUBJECT,Field.TEMPORALITY),List.of());
        var result=IntentRouting.decide(current,new SessionFacts(1,List.of(old),List.of()),input());
        assertTrue(result.routes().contains(IntentRouting.Route.CLARIFY));assertFalse(result.routes().contains(IntentRouting.Route.MEDICAL_RETRIEVAL));
        assertEquals(2,result.questions().size());assertTrue(result.questions().get(1).contains("不能判断现在"));
        assertFalse(result.questions().get(1).contains("以前的不适"));
    }
}
