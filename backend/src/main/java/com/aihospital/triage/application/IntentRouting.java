package com.aihospital.triage.application;

import com.aihospital.triage.domain.*;
import java.util.*;
import static com.aihospital.triage.domain.IntentExtraction.*;

/** Typed composite intent routes, subordinate to raw-text safety and booking authorization. */
public record IntentRouting(Set<Route> routes,List<String> questions) {
    public enum Route { HUMAN_HELP, CLARIFY, MEDICAL_RETRIEVAL, DEPARTMENTS, SCHEDULES, BOOKING_CONFIRMATION }
    public IntentRouting {routes=Set.copyOf(routes);questions=List.copyOf(questions);}
    public static IntentRouting decide(IntentExtraction extraction,SessionFacts historical,IntentInput input) {
        var routes=EnumSet.noneOf(Route.class);var questions=new ArrayList<String>();
        if(extraction.intents().contains(Intent.HUMAN_HELP))return new IntentRouting(Set.of(Route.HUMAN_HELP),List.of());
        if(extraction.intents().contains(Intent.FIND_DEPARTMENT))routes.add(Route.DEPARTMENTS);
        if(extraction.intents().contains(Intent.FIND_DOCTOR))routes.add(Route.SCHEDULES);
        if(extraction.intents().contains(Intent.BOOKING_REQUEST)){routes.add(Route.SCHEDULES);routes.add(Route.BOOKING_CONFIRMATION);}
        if(extraction.intents().contains(Intent.GENERAL_HEALTH)||extraction.intents().contains(Intent.SYMPTOM_CONSULT))routes.add(Route.MEDICAL_RETRIEVAL);
        // Only explicit missing classifications justify these fixed questions; historical CURRENT is not promoted.
        if(extraction.intents().contains(Intent.SYMPTOM_CONSULT)) {
            if(extraction.missingInformation().contains(Field.SUBJECT))questions.add("请确认：这些不适是您本人出现的，还是在替他人咨询？");
            if(extraction.missingInformation().contains(Field.TEMPORALITY)) {
                var visible=input.messages().stream().map(IntentInput.SourceMessage::messageId).collect(java.util.stream.Collectors.toSet());
                boolean historicalSelf=historical.events().stream().anyMatch(e->e.subject()==Subject.SELF
                        &&e.sourceRefs().stream().noneMatch(ref->visible.contains(ref.messageId())));
                questions.add(historicalSelf?"会话曾记录过不适，但不能判断现在是否仍然存在。请说明本次是新出现、仍在持续，还是回顾以前的情况？":"请说明这些不适目前正在发生，还是以前出现过的情况？");
            }
        }
        if(!questions.isEmpty()){routes.add(Route.CLARIFY);routes.remove(Route.MEDICAL_RETRIEVAL);}
        return new IntentRouting(routes,questions);
    }
}
