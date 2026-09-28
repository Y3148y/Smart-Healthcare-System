package com.aihospital.triage.domain;

public interface NarrationModel {
    record Answer(String text, String status, String modelName) {}
    Answer explain(String symptom, String department, String candidateDepartments, String evidence, String fallback);

    /** Optional conversational layer; structured triage and safety remain server controlled. */
    default Answer guide(String symptom, String candidateDepartments, String evidence, String fallback) {
        return new Answer(fallback, "DEMO", "");
    }
}
