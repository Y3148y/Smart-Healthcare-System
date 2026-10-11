package com.aihospital.triage.domain;

import com.aihospital.triage.domain.IntentExtraction.SymptomEvent;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/** Rebuildable append-only projection, never a risk clearance or booking authorization. */
public record SessionFacts(long version, List<SymptomEvent> events, List<IntentExtraction.Conflict> conflicts) {
    public SessionFacts {
        if (version < 0) throw new IllegalArgumentException("Negative fact version");
        events = List.copyOf(events);
        conflicts = List.copyOf(conflicts);
    }

    public static SessionFacts empty() { return new SessionFacts(0, List.of(), List.of()); }

    /** Exact-term projection: no clinical synonym or event-identity inference. */
    public Set<String> retrievalSymptoms() {
        if (!conflicts.isEmpty()) return Set.of();
        return events.stream().filter(event -> event.subject() == IntentExtraction.Subject.SELF
                && event.temporality() == IntentExtraction.Temporality.CURRENT
                && event.assertion() == IntentExtraction.Assertion.AFFIRMED)
                .filter(event -> events.stream().noneMatch(other -> sameContext(event, other)
                        && other.assertion() != IntentExtraction.Assertion.AFFIRMED))
                .map(event -> event.symptom().strip()).collect(Collectors.toUnmodifiableSet());
    }

    private boolean sameContext(SymptomEvent left, SymptomEvent right) {
        return left.subject() == right.subject() && left.temporality() == right.temporality()
                && left.symptom().equals(right.symptom());
    }
}
