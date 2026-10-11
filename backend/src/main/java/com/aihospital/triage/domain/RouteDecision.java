package com.aihospital.triage.domain;

/** Workflow branch, not a diagnosis or a booking authorization. */
public record RouteDecision(Route route, Reason reason) {
    public enum Route { GUIDANCE, ASSESSMENT }
    public enum Reason { IMMEDIATE_CARE, HUMAN_REVIEW, NEEDS_INFORMATION, READY_FOR_ASSESSMENT }
}
