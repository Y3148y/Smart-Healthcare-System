package com.aihospital.triage.domain;

import com.aihospital.shared.model.Models.TriageResult;

/** Application boundary for a replaceable triage implementation. */
public interface TriageEngine {
    boolean requiresImmediateCare(String symptoms);
    /** True when the dialogue needs a material fact before creating a booking recommendation. */
    boolean needsClarification(String symptoms);
    /** A safe, non-diagnostic medical explanation plus the most relevant next question. */
    String clarificationPrompt(String symptoms);
    TriageResult triage(String sessionId, String symptoms, String patient);
}
