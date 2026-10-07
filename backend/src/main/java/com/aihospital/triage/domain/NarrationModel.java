package com.aihospital.triage.domain;

import java.util.List;

public interface NarrationModel {
    record Turn(String role, String content) {}
    record Answer(String text, String status, String modelName, AnswerEvidence.Diagnostics diagnostics) {
        public Answer(String text, String status, String modelName) { this(text, status, modelName, null); }
    }
    default Answer answerWithEvidence(String symptom, String department, String candidates,
            List<com.aihospital.shared.model.Models.Evidence> evidence, String fallback, List<Turn> history,
            ServiceContext services, boolean guidance, String retrievalStatus) {
        String excerpts = evidence.stream().map(com.aihospital.shared.model.Models.Evidence::excerpt)
                .reduce("", (a, b) -> a + " " + b);
        if (evidence.isEmpty()) return guideGeneral(symptom, fallback, history);
        return guidance ? guide(symptom, candidates, excerpts, fallback, history)
                : explainWithServices(symptom, department, candidates, excerpts, fallback, history, services);
    }
    record RuntimeStatus(boolean configured, String mode, String modelName, String detail) {}
    /** Query-time business facts, not medical evidence or a reservation authorization. */
    record ServiceState(String department, String status, String message) {}
    record ServiceContext(List<ServiceState> services, boolean routineBookingAllowed) {
        public ServiceContext { services = services == null ? List.of() : List.copyOf(services); }
    }
    Answer explain(String symptom, String department, String candidateDepartments, String evidence, String fallback,
                   List<Turn> history);

    /** Compatible adapters may ignore this context; the live adapter must include it in its prompt. */
    default Answer explainWithServices(String symptom, String department, String candidateDepartments,
                                      String evidence, String fallback, List<Turn> history, ServiceContext services) {
        return explain(symptom, department, candidateDepartments, evidence, fallback, history);
    }

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
