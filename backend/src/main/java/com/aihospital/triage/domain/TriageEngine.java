package com.aihospital.triage.domain;

import com.aihospital.shared.model.Models.TriageResult;

/** Application boundary for a replaceable triage implementation. */
public interface TriageEngine {
    boolean requiresImmediateCare(String symptoms);
    boolean needsClarification(String symptoms);
    TriageResult triage(String sessionId, String symptoms, String patient);
}
