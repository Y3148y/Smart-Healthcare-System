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
        var disputed = new java.util.LinkedHashSet<SymptomEvent>();
        for (var conflict : conflicts) {
            var linked = events.stream().filter(event -> overlapsSource(event.sourceRefs(), conflict.sourceRefs())).toList();
            // A correction without a link to a symptom may affect the whole window.
            // Keep that uncertainty global rather than guessing its intended event.
            if (linked.isEmpty()) return Set.of();
            disputed.addAll(linked);
        }
        return events.stream().filter(event -> event.subject() == IntentExtraction.Subject.SELF
                && event.temporality() == IntentExtraction.Temporality.CURRENT
                && event.assertion() == IntentExtraction.Assertion.AFFIRMED)
                .filter(event -> disputed.stream().noneMatch(other -> sameContext(event, other)))
                .filter(event -> events.stream().noneMatch(other -> sameContext(event, other)
                        && other.assertion() != IntentExtraction.Assertion.AFFIRMED))
                .map(event -> event.symptom().strip()).collect(Collectors.toUnmodifiableSet());
    }

    private boolean sameContext(SymptomEvent left, SymptomEvent right) {
        return left.subject() == right.subject() && left.temporality() == right.temporality()
                && left.symptom().equals(right.symptom());
    }

    private boolean overlapsSource(List<IntentExtraction.SourceRef> events, List<IntentExtraction.SourceRef> conflicts) {
        return events.stream().anyMatch(event -> conflicts.stream().anyMatch(conflict ->
                event.messageId().equals(conflict.messageId())
                        && (event.quote().contains(conflict.quote()) || conflict.quote().contains(event.quote()))));
    }
}
