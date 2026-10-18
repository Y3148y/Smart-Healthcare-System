package com.aihospital.triage.domain;
/** Real pipeline milestones, never model tokens or medical conclusions. */
public enum TriageProgress { SAFETY_CHECK, KNOWLEDGE_RETRIEVAL, ANSWER_GENERATION, SERVICE_RESPONSE, SCHEDULE_LOOKUP, SAVING }
