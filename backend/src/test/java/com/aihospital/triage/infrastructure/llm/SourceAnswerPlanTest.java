package com.aihospital.triage.infrastructure.llm;

import com.aihospital.triage.domain.AnswerEvidence;
import com.aihospital.shared.model.Models.Evidence;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class SourceAnswerPlanTest {
    final List<AnswerEvidence.Reference> refs=AnswerEvidence.references(List.of(
            new Evidence("Fixture","https://example.invalid","Only if condition X. Try action A.\n\nDo not infer benefit B.",.9,List.of("Scope"))));
    @Test void medicalBodyComesOnlyFromExactSourceWhileModelChoosesDialogueActs() throws Exception {
        var checked=SourceAnswerPlan.check("{\"sourceIds\":[\"E1\"],\"transition\":\"ACKNOWLEDGE\",\"questionKeys\":[\"DURATION\"],\"gap\":\"APPLICABILITY_UNKNOWN\"}",refs);
        assertTrue(checked.text().contains(refs.get(0).excerpt()));assertTrue(checked.text().contains("Fixture · Scope"));
        assertEquals(refs.get(0).excerpt(),checked.draft().paragraphs().get(0).text());
        assertEquals(List.of("E1"),checked.adopted());assertEquals(List.of("这种不适持续多久了？"),checked.draft().questions());
        assertTrue(checked.text().contains("不能确认"));
    }
    @Test void freeMedicalProseCannotEnterAsBodyTransitionQuestionOrGap() {
        for(String raw:List.of(
                "{\"sourceIds\":[\"E1\"],\"transition\":\"Action A causes benefit B\",\"questionKeys\":[],\"gap\":\"NONE\"}",
                "{\"sourceIds\":[\"E1\"],\"transition\":\"NONE\",\"questionKeys\":[\"Action A causes benefit B?\"],\"gap\":\"NONE\"}",
                "{\"sourceIds\":[\"E1\"],\"transition\":\"NONE\",\"questionKeys\":[],\"gap\":\"Action A causes benefit B\"}",
                "{\"sourceIds\":[\"E1\"],\"transition\":\"NONE\",\"questionKeys\":[],\"gap\":\"NONE\",\"text\":\"Action A causes benefit B\"}",
                "{\"paragraphs\":[{\"text\":\"Action A causes benefit B\",\"kind\":\"LIMITATION\",\"referenceIds\":[]}],\"questions\":[],\"uncovered\":[]}"
        ))assertThrows(Exception.class,()->SourceAnswerPlan.check(raw,refs));
    }
    @Test void strictIdentityTypesDuplicatesLimitsAndBudgetFailClosed() {
        String valid="{\"sourceIds\":[\"E1\"],\"transition\":\"NONE\",\"questionKeys\":[],\"gap\":\"NONE\"}";
        for(String raw:List.of(valid.replace("E1","E9"),valid.replace("[\"E1\"]","[1]"),
                valid.replace("[\"E1\"]","[\"E1\",\"E1\"]"),valid.replace("\"NONE\",\"questionKeys\"","null,\"questionKeys\""),
                valid.replace("\"questionKeys\":[]","\"questionKeys\":[\"DURATION\",\"DURATION\"]"),
                valid.replace("\"gap\":\"NONE\"","\"gap\":\"NONE\",\"gap\":\"NONE\""),valid+" {}"))
            assertThrows(Exception.class,()->SourceAnswerPlan.check(raw,refs));
        var huge=AnswerEvidence.references(List.of(new Evidence("Long","source","x".repeat(1300),1)));
        assertThrows(Exception.class,()->SourceAnswerPlan.check(valid,huge));
    }
    @Test void missingSourcesAllowOnlyFixedGapAndExistingQuestions() throws Exception {
        var checked=SourceAnswerPlan.check("{\"sourceIds\":[],\"transition\":\"REFERENCE\",\"questionKeys\":[\"LOCATION\"],\"gap\":\"NOT_COVERED\"}",List.of());
        assertTrue(checked.adopted().isEmpty());assertFalse(checked.text().contains("资料原文"));
        assertThrows(Exception.class,()->SourceAnswerPlan.check("{\"sourceIds\":[],\"transition\":\"NONE\",\"questionKeys\":[],\"gap\":\"NONE\"}",refs));
    }
}
