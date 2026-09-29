package com.aihospital.triage.domain;

public interface NarrationModel {
    record Answer(String text, String status, String modelName) {}
    record RuntimeStatus(boolean configured, String mode, String modelName, String detail) {}
    Answer explain(String symptom, String department, String candidateDepartments, String evidence, String fallback);

    /** Optional conversational layer; structured triage and safety remain server controlled. */
    default Answer guide(String symptom, String candidateDepartments, String evidence, String fallback) {
        return new Answer(fallback, "DEMO", "");
    }
    default RuntimeStatus runtimeStatus() {
        return new RuntimeStatus(false, "demo", "", "未配置兼容模型，当前将使用规则与本地知识资料兜底回答。");
    }
}
