package com.aihospital.triage.application;

import com.aihospital.triage.domain.IntentExtraction;
import com.aihospital.triage.domain.SessionFacts;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** Source-checked append-only merge. Denials never erase earlier affirmative observations. */
public final class SessionFactReducer {
    public SessionFacts merge(SessionFacts previous, IntentExtraction proposal, IntentInput input, long version) {
        if (proposal.schemaVersion() != 1) throw new IllegalArgumentException("Unsupported fact schema");
        if (version < previous.version()) throw new IllegalArgumentException("Stale fact version");
        Map<String, IntentInput.SourceMessage> sources = input.messages().stream()
                .collect(Collectors.toMap(IntentInput.SourceMessage::messageId, Function.identity()));
        for (var event : proposal.events()) {
            if (event.subject() == null || event.temporality() == null || event.assertion() == null)
                throw new IllegalArgumentException("Explicit fact classification required");
            validateSources(event.sourceRefs(), sources);
            if (event.symptom() == null || event.symptom().isBlank()
                    || event.sourceRefs().stream().noneMatch(ref -> ref.quote().contains(event.symptom())))
                throw new IllegalArgumentException("Fact symptom has no patient source");
        }
        for (var conflict : proposal.conflicts()) validateSources(conflict.sourceRefs(), sources);
        var events = new LinkedHashSet<>(previous.events());
        events.addAll(proposal.events());
        var conflicts = new LinkedHashSet<>(previous.conflicts());
        conflicts.addAll(proposal.conflicts());
        if (version == previous.version() && (!events.equals(new LinkedHashSet<>(previous.events()))
                || !conflicts.equals(new LinkedHashSet<>(previous.conflicts()))))
            throw new IllegalArgumentException("Changed facts require a new version");
        return new SessionFacts(version, events.stream().toList(), conflicts.stream().toList());
    }

    private void validateSources(List<IntentExtraction.SourceRef> refs, Map<String, IntentInput.SourceMessage> sources) {
        if (refs.isEmpty()) throw new IllegalArgumentException("Fact source required");
        for (var ref : refs) {
            var source = sources.get(ref.messageId());
            if (source == null || !"USER".equals(source.role()) || ref.quote() == null || ref.quote().isBlank()
                    || !source.content().contains(ref.quote())) throw new IllegalArgumentException("Invalid fact source");
        }
    }
}
