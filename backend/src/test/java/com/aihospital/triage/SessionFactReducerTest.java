package com.aihospital.triage;

import com.aihospital.triage.application.IntentInput;
import com.aihospital.triage.application.SessionFactReducer;
import com.aihospital.triage.domain.IntentExtraction;
import com.aihospital.triage.domain.IntentExtraction.*;
import com.aihospital.triage.domain.SessionFacts;
import org.junit.jupiter.api.Test;
import java.util.List;
import java.util.Set;
import static org.junit.jupiter.api.Assertions.*;

class SessionFactReducerTest {
    private final SessionFactReducer reducer = new SessionFactReducer();
    private IntentInput input(String id, String text) {
        return new IntentInput(List.of(new IntentInput.SourceMessage(id, "USER", text)));
    }
    private SymptomEvent event(String id, String quote, Subject subject, Temporality time, Assertion assertion) {
        return new SymptomEvent("头痛", null, null, null, null, null, subject, time, assertion,
                List.of(new SourceRef(id, quote)));
    }
    private IntentExtraction proposal(SymptomEvent... events) {
        return new IntentExtraction(1, List.of(Intent.SYMPTOM_CONSULT), List.of(events), List.of(), List.of());
    }

    @Test void preservesSubjectTimeAndUnknownInsteadOfInventingCurrentSelfFacts() {
        for (var event : List.of(event("u", "头痛", Subject.OTHER, Temporality.CURRENT, Assertion.AFFIRMED),
                event("u", "头痛", Subject.SELF, Temporality.PAST, Assertion.AFFIRMED),
                event("u", "头痛", Subject.UNKNOWN, Temporality.UNKNOWN, Assertion.AFFIRMED),
                event("u", "头痛", Subject.SELF, Temporality.CURRENT, Assertion.UNCERTAIN))) {
            var state = reducer.merge(SessionFacts.empty(), proposal(event), input("u", "头痛"), 1);
            assertEquals(List.of(event), state.events());
            assertTrue(state.retrievalSymptoms().isEmpty());
        }
        var self = event("u", "头痛", Subject.SELF, Temporality.CURRENT, Assertion.AFFIRMED);
        assertEquals(Set.of("头痛"), reducer.merge(SessionFacts.empty(), proposal(self), input("u", "头痛"), 1).retrievalSymptoms());
    }

    @Test void laterDenialRetainsOriginalObservationButBlocksAffirmativeQuery() {
        var first = event("u1", "头痛", Subject.SELF, Temporality.CURRENT, Assertion.AFFIRMED);
        var state = reducer.merge(SessionFacts.empty(), proposal(first), input("u1", "头痛"), 1);
        var denial = event("u2", "没有头痛", Subject.SELF, Temporality.CURRENT, Assertion.NEGATED);
        var merged = reducer.merge(state, proposal(denial), input("u2", "没有头痛"), 2);
        assertEquals(List.of(first, denial), merged.events());
        assertTrue(merged.retrievalSymptoms().isEmpty());
        assertEquals(merged, reducer.merge(merged, proposal(denial), input("u2", "没有头痛"), 2));
        assertThrows(IllegalArgumentException.class, () -> reducer.merge(merged, proposal(), input("u2", "没有头痛"), 1));
        assertThrows(IllegalArgumentException.class, () -> reducer.merge(state, proposal(denial), input("u2", "没有头痛"), 1));
    }

    @Test void assistantOrFabricatedSourcesAreRejected() {
        var event = event("a", "头痛", Subject.SELF, Temporality.CURRENT, Assertion.AFFIRMED);
        var input = new IntentInput(List.of(new IntentInput.SourceMessage("a", "ASSISTANT", "头痛"),
                new IntentInput.SourceMessage("u", "USER", "没有其他信息")));
        assertThrows(IllegalArgumentException.class, () -> reducer.merge(SessionFacts.empty(), proposal(event), input, 1));
        assertThrows(IllegalArgumentException.class, () -> reducer.merge(SessionFacts.empty(), proposal(event), input("u", "头痛"), 1));
    }

    @Test void explicitConflictBlocksDerivedQueriesWithoutDiscardingEvents() {
        var event = event("u", "头痛", Subject.SELF, Temporality.CURRENT, Assertion.AFFIRMED);
        var conflict = new Conflict(Field.ASSERTION, event.sourceRefs());
        var proposal = new IntentExtraction(1, List.of(Intent.SYMPTOM_CONSULT), List.of(event), List.of(), List.of(conflict));
        var state = reducer.merge(SessionFacts.empty(), proposal, input("u", "头痛"), 1);
        assertEquals(1, state.events().size());
        assertEquals(List.of(conflict), state.conflicts());
        assertTrue(state.retrievalSymptoms().isEmpty());
    }

    @Test void otherPersonsPastDenialDoesNotOverrideCurrentSelfAffirmation() {
        var self = event("u", "头痛", Subject.SELF, Temporality.CURRENT, Assertion.AFFIRMED);
        var other = event("u", "头痛", Subject.OTHER, Temporality.PAST, Assertion.NEGATED);
        var state = reducer.merge(SessionFacts.empty(), proposal(self, other), input("u", "头痛"), 1);
        assertEquals(Set.of("头痛"), state.retrievalSymptoms());
        var invalid = new IntentExtraction(2, List.of(), List.of(), List.of(), List.of());
        assertThrows(IllegalArgumentException.class, () -> reducer.merge(state, invalid, input("u", "头痛"), 2));
    }

    @Test void sourceLinkedConflictBlocksItsSymptomWithoutDiscardingUnrelatedObservation() {
        var head = event("u1", "头痛", Subject.SELF, Temporality.CURRENT, Assertion.AFFIRMED);
        var abdomen = new SymptomEvent("腹痛", null, null, null, null, null,
                Subject.SELF, Temporality.CURRENT, Assertion.AFFIRMED, List.of(new SourceRef("u2", "腹痛")));
        var conflict = new Conflict(Field.ASSERTION, abdomen.sourceRefs());
        var proposal = new IntentExtraction(1, List.of(Intent.SYMPTOM_CONSULT), List.of(head, abdomen),
                List.of(), List.of(conflict));
        var sources = new IntentInput(List.of(new IntentInput.SourceMessage("u1", "USER", "头痛"),
                new IntentInput.SourceMessage("u2", "USER", "腹痛")));
        var state = reducer.merge(SessionFacts.empty(), proposal, sources, 1);
        assertEquals(Set.of("头痛"), state.retrievalSymptoms());
        assertEquals(List.of(head, abdomen), state.events());
        assertEquals(List.of(conflict), state.conflicts());
    }

    @Test void broadOrUnlinkedConflictCannotBeNarrowedByGuessing() {
        var head = event("u1", "头痛", Subject.SELF, Temporality.CURRENT, Assertion.AFFIRMED);
        var abdomen = new SymptomEvent("腹痛", null, null, null, null, null,
                Subject.SELF, Temporality.CURRENT, Assertion.AFFIRMED, List.of(new SourceRef("u1", "腹痛")));
        for (var conflict : List.of(
                new Conflict(Field.SUBJECT, List.of(new SourceRef("u1", "头痛和腹痛"))),
                new Conflict(Field.SUBJECT, List.of(new SourceRef("u2", "说不清楚是谁"))))) {
            var proposal = new IntentExtraction(1, List.of(Intent.SYMPTOM_CONSULT), List.of(head, abdomen),
                    List.of(), List.of(conflict));
            var sources = new IntentInput(List.of(new IntentInput.SourceMessage("u1", "USER", "头痛和腹痛"),
                    new IntentInput.SourceMessage("u2", "USER", "说不清楚是谁")));
            assertTrue(reducer.merge(SessionFacts.empty(), proposal, sources, 1).retrievalSymptoms().isEmpty());
        }
    }
}
