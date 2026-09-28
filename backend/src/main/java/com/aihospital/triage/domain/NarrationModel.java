package com.aihospital.triage.domain;

public interface NarrationModel {
    record Answer(String text, String status, String modelName) {}
    Answer explain(String symptom, String department, String candidateDepartments, String evidence, String fallback);
}
