package com.aihospital.triage.application;
import com.aihospital.triage.domain.*;
import org.junit.jupiter.api.Test;
import java.util.*;
import static com.aihospital.triage.domain.IntentExtraction.*;
import static org.junit.jupiter.api.Assertions.*;
class SessionEventTimelineTest {
    private SymptomEvent event(Subject subject,Assertion assertion,List<SourceRef> refs){return new SymptomEvent("咳嗽",null,null,null,null,null,subject,Temporality.CURRENT,assertion,refs);}
    @Test void correctionRequiresAnExplicitLinkedPatientSourceAndDoesNotDeleteHistory() {
        var source=new SourceRef("m1","我没有咳嗽");var old=event(Subject.SELF,Assertion.NEGATED,List.of(source));
        var corrected=event(Subject.SELF,Assertion.AFFIRMED,List.of(source,new SourceRef("m2","更正：我咳嗽")));
        var facts=new SessionFacts(2,List.of(old,corrected),List.of());var timeline=new SessionEventTimeline().project(facts);
        assertEquals("SUPERSEDED",timeline.get(0).state());assertEquals(timeline.get(0).id(),timeline.get(1).corrects());
        assertEquals(2,facts.events().size());assertEquals(Set.of("咳嗽"),facts.retrievalSymptoms());
        assertEquals(timeline,new SessionEventTimeline().project(facts));
    }
    @Test void unlinkedDenialDifferentSubjectAndAmbiguousCorrectionsDoNotGuessAnEpisode() {
        var source=new SourceRef("m1","我咳嗽");var old=event(Subject.SELF,Assertion.AFFIRMED,List.of(source));
        var denial=event(Subject.SELF,Assertion.NEGATED,List.of(new SourceRef("m2","没有咳嗽")));
        var other=event(Subject.OTHER,Assertion.AFFIRMED,List.of(source));
        var correction=event(Subject.UNKNOWN,Assertion.UNCERTAIN,List.of(source,new SourceRef("m3","更正：咳嗽的主体不确定")));
        var timeline=new SessionEventTimeline().project(new SessionFacts(3,List.of(old,other,correction),List.of()));
        assertEquals("AMBIGUOUS_CORRECTION",timeline.get(2).state());assertNull(timeline.get(2).corrects());
        assertTrue(new SessionFacts(2,List.of(old,denial),List.of()).retrievalSymptoms().isEmpty());
        assertNotEquals(new SessionEventTimeline().identity(old),new SessionEventTimeline().identity(other));
    }
}
