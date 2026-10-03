package com.aihospital.triage.domain;
/** Real pipeline milestones, never model tokens or medical conclusions. */
public enum TriageProgress { SAFETY_CHECK, KNOWLEDGE_RETRIEVAL, ANSWER_GENERATION, SCHEDULE_LOOKUP, SAVING }
