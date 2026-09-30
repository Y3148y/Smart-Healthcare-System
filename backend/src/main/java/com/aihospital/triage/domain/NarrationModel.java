package com.aihospital.triage.domain;

import java.util.List;

public interface NarrationModel {
    record Turn(String role, String content) {}
    record Answer(String text, String status, String modelName) {}
    record RuntimeStatus(boolean configured, String mode, String modelName, String detail) {}
    Answer explain(String symptom, String department, String candidateDepartments, String evidence, String fallback,
                   List<Turn> history);

    /** Optional conversational layer; structured triage and safety remain server controlled. */
    default Answer guide(String symptom, String candidateDepartments, String evidence, String fallback,
                         List<Turn> history) {
        return new Answer(fallback, "DEMO", "");
    }
    /** Low-risk general conversation without retrieved evidence; never grants booking eligibility. */
    default Answer guideGeneral(String symptom, String fallback, List<Turn> history) {
        return new Answer(fallback, "DEMO_UNGROUNDED", "");
    }
    default RuntimeStatus runtimeStatus() {
        return new RuntimeStatus(false, "demo", "", "未配置兼容模型，当前将使用规则与本地知识资料兜底回答。");
    }
}
