package com.aihospital.triage.application;

import com.aihospital.triage.domain.IntentExtraction;
import com.aihospital.triage.domain.SessionFacts;
import com.aihospital.triage.domain.TriageRecords.Conversation;

/** Source-checked model observations, not verified clinical facts or risk clearance. */
public interface SessionFactStore {
    SessionFacts stage(String patient, Conversation conversation, IntentInput input, IntentExtraction proposal);
    SessionFacts completed(String patient,String sessionId);
}
