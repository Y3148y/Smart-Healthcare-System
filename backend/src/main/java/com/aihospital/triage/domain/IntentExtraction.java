package com.aihospital.triage.domain;

import java.util.List;

/** Model proposals with patient-source references; never a safety or booking authorization. */
public record IntentExtraction(int schemaVersion, List<Intent> intents, List<SymptomEvent> events,
                               List<Field> missingInformation, List<Conflict> conflicts) {
    public IntentExtraction {
        intents = List.copyOf(intents);
        events = List.copyOf(events);
        missingInformation = List.copyOf(missingInformation);
        conflicts = List.copyOf(conflicts);
    }
    public enum Intent { GENERAL_HEALTH, SYMPTOM_CONSULT, FIND_DEPARTMENT, FIND_DOCTOR, BOOKING_REQUEST, HUMAN_HELP }
    public enum Field { SYMPTOM, BODY_SITE, ONSET, DURATION, TRIGGER, SEVERITY, SUBJECT, TEMPORALITY, ASSERTION }
    public enum Subject { SELF, OTHER, UNKNOWN }
    public enum Temporality { CURRENT, PAST, UNKNOWN }
    public enum Assertion { AFFIRMED, NEGATED, UNCERTAIN }
    public record SourceRef(String messageId, String quote) {}
    public record SymptomEvent(String symptom, String bodySite, String onset, String duration, String trigger,
                               String severity, Subject subject, Temporality temporality, Assertion assertion,
                               List<SourceRef> sourceRefs) {
        public SymptomEvent { sourceRefs = List.copyOf(sourceRefs); }
    }
    public record Conflict(Field field, List<SourceRef> sourceRefs) {
        public Conflict { sourceRefs = List.copyOf(sourceRefs); }
    }
}
