package com.aihospital.triage.infrastructure.demo;

import com.aihospital.catalog.domain.DoctorDirectory;
import com.aihospital.knowledge.domain.KnowledgeCatalog;
import com.aihospital.knowledge.domain.KnowledgeCatalog.Retrieval;
import com.aihospital.observation.domain.CallLogStore;
import com.aihospital.observation.application.AnswerCallAudit;
import com.aihospital.shared.model.Models.*;
import com.aihospital.triage.domain.Disposition;
import com.aihospital.triage.domain.NarrationModel;
import com.aihospital.triage.domain.TriageEngine;
import com.aihospital.triage.domain.CurrentRequestIntent;
import com.aihospital.triage.domain.TriageProgress;
import com.aihospital.triage.domain.TriageSafetyPolicy;
import com.aihospital.triage.infrastructure.llm.StructuredDecisionModel;
import com.aihospital.tools.application.HospitalToolExecutor;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;

/** Demo routing policy. The LLM may explain the result but cannot replace safety decisions. */
@Component
public class RuleBasedTriageEngine implements TriageEngine {
    private final TriageSafetyPolicy safety;
    private final DoctorDirectory doctors;
    private final KnowledgeCatalog knowledge;
    private final NarrationModel narration;
    private final CallLogStore calls;
    private final HospitalToolExecutor toolExecutor;
    private final StructuredDecisionModel structuredDecisions;

    public RuleBasedTriageEngine(TriageSafetyPolicy safety, DoctorDirectory doctors,
                                 KnowledgeCatalog knowledge, NarrationModel narration, CallLogStore calls,
                                 HospitalToolExecutor toolExecutor, StructuredDecisionModel structuredDecisions) {
        this.safety = safety;
        this.doctors = doctors;
        this.knowledge = knowledge;
        this.narration = narration;
        this.calls = calls;
        this.toolExecutor = toolExecutor;
        this.structuredDecisions = structuredDecisions;
    }

    @Override public SafetyAssessment assessSafety(String symptoms) { return safety.assess(symptoms); }
    @Override public boolean requiresImmediateCare(String symptoms) { return safety.requiresImmediateCare(symptoms); }

    private static final String PRESCRIPTION_REFUSAL = "本演示系统不提供诊断、处方或药物建议，亦不能自动生成治疗方案。"
            + "你希望判断就医方向或生成预约，请补充最主要的不适、持续时间和变化；"
            + "如果需要人工协助，可在会话页选择“需要人工导诊？提交申请”（演示系统仅记录申请，不保证实时响应）。";

    /**
     * A known symptom alone is useful for a conversation, but normally is not enough
     * to attach a simulated appointment.  We wait for a duration/change/associated
     * symptom, unless the user explicitly asks for a booking direction or a possible
     * fracture needs prompt offline assessment.
     */
    @Override public boolean needsClarification(String symptoms) {
        return needsClarification(symptoms,symptoms);
    }

    @Override public boolean needsClarification(String symptoms,String currentRequest) {
        if (requiresImmediateCare(symptoms)) return false;
        if (hasDiagnosisOrPrescriptionIntent(currentRequest)) return true;
        // 「明显出血」量级不明，既不该升级急症，也不该落到普通分诊或预约。走确定性追问，
        // 服务据此置「待补充信息」，而该处置按 D5 不可预约。
        if (safety.requiresBleedingClarification(symptoms)) return true;
        // A preference about booking cannot suppress offline safety guidance.
        if (safety.assess(symptoms).humanReviewRecommended() || possibleFracture(symptoms)) return false;
        var bookingIntent = CurrentRequestIntent.booking(currentRequest);
        if (bookingIntent == CurrentRequestIntent.BookingIntent.DECLINED) return true;
        List<DepartmentCandidate> candidates = candidatesFor(symptoms);
        if (candidates.isEmpty()) return true;
        if (bookingIntent == CurrentRequestIntent.BookingIntent.REQUESTED
                || CurrentRequestIntent.directionRequested(currentRequest)) return false;
        return !(hasTimeCourse(symptoms) && hasClinicalQualifier(symptoms));
    }

    private boolean hasDiagnosisOrPrescriptionIntent(String text) {
        return CurrentRequestIntent.restricted(text);
    }

    /**
     * A prescription or diagnosis request is a compliance refusal, not a triage
     * outcome, so it is returned verbatim and never handed to the model.  Per
     * 国卫办医发〔2022〕2号 第二十一条 an AI system may not substitute for a
     * clinician, and a refusal that the model is allowed to reword is not a
     * guarantee.
     */
    /** Exposed so tests can pin the intent pattern without an API key or a running model. */
    public boolean requiresHumanHandover(String text) { return hasDiagnosisOrPrescriptionIntent(text); }

    private NarrationModel.Answer prescriptionRefusal() {
        return new NarrationModel.Answer(PRESCRIPTION_REFUSAL, "POLICY_REFUSAL", "");
    }

    /**
     * The single audit entry point for a compliance refusal.  Every refusal must
     * reach the call log as 合规拒答 with success=true, otherwise the admin view
     * shows it as an ordinary 预问诊引导 and the compliance trail is lost.
     */
    private void recordComplianceRefusal(long startedAtNanos, String user, List<ToolTrace> trace) {
        calls.record(new CallLog(UUID.randomUUID().toString(), LocalDateTime.now(), "合规拒答", user,
                "POLICY_REFUSAL", 0, 0, elapsedMillis(startedAtNanos), true, List.copyOf(trace)));
    }

    /**
     * A refusal is a terminal result, not a triage assessment: no department, no
     * doctor and no grounded flag, so {@code TriageConversationService} keeps the
     * session at 待补充信息 and {@code SimulationBookingService} blocks booking.
     *
     * <p>Unreachable through {@code TriageConversationService} today: it consults
     * {@code needsClarification} first, which returns true for this intent, so the
     * service takes the {@link #clarificationPrompt} branch.  It is retained as a
     * defence-in-depth guard so that a future change to the clarification rules
     * cannot leak a prescription request into ordinary routing.
     */
    private TriageResult prescriptionRefusalResult(long callStarted, String sessionId, String user,
            SafetyAssessment safetyAssessment, List<ToolTrace> trace) {
        NarrationModel.Answer answer = prescriptionRefusal();
        recordComplianceRefusal(callStarted, user, trace);
        return new TriageResult(sessionId, "待补充信息", 0, null, null, answer.text(),
                "本系统不提供诊断、处方或治疗建议；本建议不构成诊断、处方或治疗意见。", List.of(),
                List.copyOf(trace), List.of(), answer.status(), "", LocalDateTime.now(),
                safetyAssessment, false, "问诊或处方类请求按合规策略拒绝，未生成科室与预约建议");
    }

    @Override public Guidance clarificationPrompt(String text, List<NarrationModel.Turn> history) {
        return clarificationPrompt(text,history,stage->{});
    }
    @Override public Guidance clarificationPrompt(String text,List<NarrationModel.Turn> history,
            java.util.function.Consumer<TriageProgress> progress) {
        long started = System.nanoTime();
        // 未经临床审核: 追问内容按裁定列出部位、量、持续情况与伴随表现。固定文案而非模型
        // 生成，因为这是安全分流的一部分，不该由模型改写措辞。
        if (safety.requiresBleedingClarification(text)) {
            String prompt = "请先补充出血的具体情况：是哪个部位出血、出了多少（少量还是较多）、"
                    + "是否持续不止，以及有没有出现头晕、眼前发黑、心慌、气短或晕厥。"
                    + "在补充这些信息之前，系统不会提供普通号源，也不会给出科室推荐。"
                    + "如果出血量大、持续不止，或伴剧烈腹痛、头晕晕厥，请立即线下就医或拨打 120。";
            calls.record(new CallLog(UUID.randomUUID().toString(), LocalDateTime.now(), "预问诊追问", "出血待追问",
                    "UR-BLEEDING-UNCLEAR-001", 0, 0, elapsedMillis(started), true, List.of()));
            return new Guidance(prompt, "CLARIFICATION", 0, 0, 0);
        }
        if (hasDiagnosisOrPrescriptionIntent(CurrentRequestIntent.latest(history,text))) {
            NarrationModel.Answer answer = prescriptionRefusal();
            recordComplianceRefusal(started, "患者", List.of());
            return new Guidance(answer.text(), answer.status(), 0, 0, 0);
        }
        List<DepartmentCandidate> candidates = candidatesFor(text);
        String departments = candidates.stream().map(DepartmentCandidate::department)
                .reduce("", (left, right) -> left.isBlank() ? right : left + "、" + right);
        progress.accept(TriageProgress.KNOWLEDGE_RETRIEVAL);
        HospitalToolExecutor.Execution retrievalExecution = toolExecutor.execute("medical_knowledge_retrieve",
                java.util.Map.of("query", CurrentRequestIntent.medicalRetrievalQuery(safety.removeNegatedRedFlags(text))));
        Retrieval retrieval = retrievalExecution.data() instanceof Retrieval found
                ? found : new Retrieval(List.of(), false, retrievalExecution.trace().error());
        List<Evidence> evidence = retrieval.evidence();
        String fallback = guidedFallback(text, candidates, evidence, history);
        progress.accept(TriageProgress.ANSWER_GENERATION);
        NarrationModel.Answer answer = narration.answerWithEvidence(text, "", departments, evidence,
                fallback, history, new NarrationModel.ServiceContext(List.of(), false), true,
                retrievalStatus(retrieval, retrievalExecution.trace().success()));
        long elapsed = elapsedMillis(started);
        AnswerCallAudit.records("预问诊引导", "患者", answer, elapsed,
                List.of(retrievalExecution.trace())).forEach(calls::record);
        return new Guidance(answer.text(), answer.status(), evidence.size(), 1,
                retrievalExecution.trace().success() ? 0 : 1, answer.diagnostics());
    }

    @Override public TriageResult triage(String sessionId, String text, String user, List<NarrationModel.Turn> history) {
        return triage(sessionId,text,user,history,stage->{});
    }
    @Override public TriageResult triage(String sessionId,String text,String user,List<NarrationModel.Turn> history,
            java.util.function.Consumer<TriageProgress> progress) {
        long callStarted = System.nanoTime();
        List<ToolTrace> trace = new ArrayList<>();
        HospitalToolExecutor.Execution symptomExecution = toolExecutor.execute("symptom_tag_search", java.util.Map.of("query", text));
        SafetyAssessment safetyAssessment = symptomExecution.data() instanceof SafetyAssessment found
                ? found : safety.assess(text);
        boolean emergency = safetyAssessment.stopRoutineFlow();
        trace.add(symptomExecution.trace());
        if (!emergency && hasDiagnosisOrPrescriptionIntent(CurrentRequestIntent.latest(history,text)))
            return prescriptionRefusalResult(callStarted, sessionId, user, safetyAssessment, trace);
        List<DepartmentCandidate> candidates = emergency ? List.of() : candidatesFor(text);
        String department = emergency ? "急诊科" : candidates.size() > 1 || candidates.isEmpty()
                ? "全科医学科" : candidates.get(0).department();
        int structuredConfidence = -1;
        if (!emergency && candidates.size() > 1 && structuredDecisions.enabled()) {
            progress.accept(TriageProgress.ANSWER_GENERATION);
            long decisionStarted = System.nanoTime();
            StructuredDecisionModel.Proposal proposal = structuredDecisions.propose(text, candidates, history);
            calls.record(new CallLog(UUID.randomUUID().toString(), LocalDateTime.now(), "结构化分诊决策", user,
                    proposal.status() + "/" + structuredDecisions.modelName(), 0, 0, elapsedMillis(decisionStarted),
                    StructuredDecisionModel.Proposal.ACCEPTED.equals(proposal.status()), List.of()));
            if (proposal.decision().isPresent()) {
                StructuredDecisionModel.Decision decision = proposal.decision().get();
                String chosen = decision.department();
                department = chosen;
                structuredConfidence = decision.confidence();
                candidates = candidates.stream()
                        .map(candidate -> candidate.department().equals(chosen)
                                ? new DepartmentCandidate(candidate.department(), decision.basis(), candidate.doctor())
                                : candidate)
                        .toList();
            }
        }
        String retrievalQuery = CurrentRequestIntent.medicalRetrievalQuery(safety.removeNegatedRedFlags(text));
        if(!emergency)progress.accept(TriageProgress.KNOWLEDGE_RETRIEVAL);
        HospitalToolExecutor.Execution retrievalExecution = emergency ? null
                : toolExecutor.execute("medical_knowledge_retrieve", java.util.Map.of("query", retrievalQuery));
        Retrieval retrieval = emergency ? new Retrieval(List.of(), false, "安全规则直接接管，未检索医学资料")
                : retrievalExecution.data() instanceof Retrieval found
                ? found : new Retrieval(List.of(), false, retrievalExecution.trace().error());
        List<Evidence> evidence = retrieval.evidence();
        if (retrievalExecution != null) trace.add(retrievalExecution.trace());
        List<NarrationModel.ServiceState> services = new ArrayList<>();
        if (!emergency) {
            HospitalToolExecutor.Execution departmentExecution = toolExecutor.execute("department_search", java.util.Map.of("department", department));
            trace.add(departmentExecution.trace());
            if (departmentExecution.trace().success() && departmentExecution.data() instanceof java.util.Map<?, ?> state
                    && state.get("status") instanceof String status && state.get("message") instanceof String message) {
                services.add(new NarrationModel.ServiceState(department, status, message));
            } else {
                services.add(new NarrationModel.ServiceState(department, "QUERY_FAILED", "科室能力查询失败，当前状态无法确认"));
            }
        }
        Doctor doctor = null;
        if (!emergency) {
            progress.accept(TriageProgress.SCHEDULE_LOOKUP);
            HospitalToolExecutor.Execution scheduleExecution = toolExecutor.execute("doctor_schedule_search", java.util.Map.of("department", department));
            trace.add(scheduleExecution.trace());
            if (scheduleExecution.data() instanceof List<?> slots)
                doctor = slots.stream().filter(Doctor.class::isInstance).map(Doctor.class::cast).findFirst().orElse(null);
        }
        boolean possibleFracture = possibleFracture(text);
        String safetyTip = emergency ? safety.emergencyAdvice(safetyAssessment)
                : possibleFracture ? "如果怀疑骨折，请尽快到线下医疗机构评估；若骨头外露、伤口大量出血或肢体明显变形，应立即急诊。模拟预约不能代替及时就医。"
                : candidates.size() > 1 ? "症状涉及多个科室方向，建议先咨询全科或人工导诊；本建议不构成诊断、处方或治疗意见。"
                : "本建议仅用于辅助分诊和挂号参考，不构成诊断、处方或治疗意见。";
        if (needsFeverCaveat(text, safetyAssessment)) safetyTip = safetyTip + " " + FEVER_CAVEAT;
        String fallback = fallbackAnswer(department, emergency, candidates);
        boolean grounded = emergency || retrieval.grounded();
        int confidence = emergency ? 100 : !grounded ? 35 : structuredConfidence > 0 ? structuredConfidence
                : "全科医学科".equals(department) ? 55 : 72;
        String risk = emergency ? Disposition.EMERGENCY : possibleFracture || "URGENT".equals(safetyAssessment.acuity())
                ? Disposition.URGENT : candidates.size() > 1
                ? Disposition.MULTI : confidence < 60 ? Disposition.PENDING : Disposition.ROUTINE;
        boolean bookable = grounded && Disposition.isBookable(risk);
        if(!emergency)progress.accept(TriageProgress.ANSWER_GENERATION);
        NarrationModel.Answer answer = emergency ? new NarrationModel.Answer(fallback, "SAFETY_RULE", "")
                : narration.answerWithEvidence(text, department,
                        candidates.stream().map(DepartmentCandidate::department)
                                .reduce("", (left, right) -> left.isBlank() ? right : left + "、" + right),
                        evidence, !grounded ? "目前资料不足以支持具体分诊，暂不生成预约建议；可以继续咨询或申请人工导诊。" : fallback, history,
                        new NarrationModel.ServiceContext(services, bookable), false,
                        retrievalStatus(retrieval, retrievalExecution.trace().success()));
        TriageResult result = new TriageResult(sessionId, risk, confidence, department, bookable ? doctor : null, answer.text(),
                safetyTip, evidence, List.copyOf(trace), candidates, answer.status(), answer.modelName(), LocalDateTime.now(),
                safetyAssessment, grounded, grounded ? retrieval.message() : "知识相关度不足，已拒绝无依据生成并建议人工复核", answer.diagnostics());
        long totalElapsed = elapsedMillis(callStarted);
        AnswerCallAudit.records("分诊工作流", user, answer, totalElapsed, trace).forEach(calls::record);
        return result;
    }

    static String retrievalStatus(Retrieval retrieval, boolean toolCallSucceeded) {
        if (!toolCallSucceeded || retrieval == null) return "DEPENDENCY_UNAVAILABLE";
        String message = retrieval.message() == null ? "" : retrieval.message();
        if (message.startsWith("DEPENDENCY_BLOCKED") || message.contains("SEARCH_UNAVAILABLE"))
            return "DEPENDENCY_UNAVAILABLE";
        return retrieval.evidence().isEmpty() ? "NO_MATCH" : "MATCHED";
    }

    private List<DepartmentCandidate> candidatesFor(String text) {
        text = safety.removeNegatedRedFlags(text);
        List<DepartmentCandidate> found = new ArrayList<>();
        if (text.matches("(?s).*(头晕|眩晕|头痛|头昏|站不稳).*"))
            found.add(new DepartmentCandidate("神经内科", "描述中有头晕、头痛或平衡相关不适", null));
        boolean menstrual = text.matches("(?s).*(痛经|经期腹痛|月经.{0,6}(痛|疼)|姨妈.{0,6}(痛|疼)).*");
        if (text.matches("(?s).*(胃|反酸|腹泻|便秘|消化不良).*") || text.contains("腹痛") && !menstrual)
            found.add(new DepartmentCandidate("消化内科", "描述中有腹部或消化道相关不适", null));
        if (text.matches("(?s).*(咳|痰|喘|胸闷|嗓子疼|嗓子痛|喉咙疼|喉咙痛|咽痛|咽喉痛).*"))
            found.add(new DepartmentCandidate("呼吸内科", "描述中有咳嗽、咽喉或呼吸道相关不适", null));
        if (text.matches("(?s).*(肩颈痛|颈肩痛|关节痛|腰痛|扭伤|骨折|摔断|摔伤|骨头断|手.{0,3}断了|脚.{0,3}断了).*"))
            found.add(new DepartmentCandidate("骨科", "描述中有外伤、疑似骨折或骨关节不适，需线下评估", null));
        if (menstrual) found.add(new DepartmentCandidate("妇科", "描述中有经期疼痛相关不适", null));
        return found;
    }

    private boolean hasTimeCourse(String text) {
        return text.matches("(?s).*(今天|昨天|前天|刚刚|近日|最近|反复|持续|加重|缓解|突然|[0-9一二两三四五六七八九十半]+\\s*(小时|天|周|个月|年)).*");
    }

    private boolean hasClinicalQualifier(String text) {
        return text.matches("(?s).*(发热|体温|咳痰|痰|肿|麻|无力|外伤|摔|扭|经量|周期|恶心|呕吐|反酸|腹泻|便秘|出汗|无|没有|否认|不伴|影响|夜间).*" );
    }

    private static final Pattern FEVER_MENTION = Pattern.compile("发热|低热|发烧|体温|寒战");

    /**
     * D7, ruling Q6=b. The 39C threshold is kept for now, but NICE NG253 section 1.1 states
     * that suspected sepsis may not have a high temperature. Without this note, a patient
     * reporting 38C reads "no emergency signal found" as "no serious infection", which is the
     * reverse guarantee the ruling asked us to avoid.
     *
     * <p>未经临床审核: the wording is unreviewed and the threshold itself remains a registered
     * gap. This text adds no diagnostic claim, only a refusal to rule anything out.
     */
    private static final String FEVER_CAVEAT = "补充说明：体温没有达到本系统的急诊阈值，并不代表可以排除严重感染。"
            + "疑似脓毒症的人可能并不发热，低体温、反应变差、意识改变等表现也需要整体评估"
            + "（依据 NICE NG253，未经临床审核）。如症状加重或出现意识、呼吸、血压方面的异常，请立即线下就医。";

    private boolean needsFeverCaveat(String text, SafetyAssessment assessment) {
        if (text == null || !FEVER_MENTION.matcher(text).find()) return false;
        return assessment.signals().stream().noneMatch(signal -> "UR-FEVER-001".equals(signal.ruleCode()));
    }

    private boolean possibleFracture(String text) {
        return text != null && text.matches("(?s).*(骨折|摔断|骨头断|手摔断|脚摔断).*" );
    }

    private String guidedFallback(String text, List<DepartmentCandidate> candidates, List<Evidence> evidence,
                                  List<NarrationModel.Turn> history) {
        if (candidates.isEmpty())
            return "我还不能据此判断合适的就医方向，也不会直接生成预约建议。请先说说最不舒服的部位、从什么时候开始，以及有没有明显加重或伴随不适。";
        if (candidates.size() > 1)
            return "你提到的症状可能涉及" + candidates.stream().map(DepartmentCandidate::department).reduce("", (a, b) -> a + "、" + b)
                    + "等不同方向。线上无法判断它们是否相关；请先告诉我哪一种最困扰你、持续多久、近期是否明显加重。确认后我会分别给出可预约方向。";
        String department = candidates.get(0).department();
        String knowledgeHint = evidence.isEmpty() ? "" : "参考已检索的“" + evidence.get(0).title() + "”，";
        if ("呼吸内科".equals(department))
            return knowledgeHint + "你提到的呼吸道或咽喉不适可先按一般呼吸道症状观察，但线上不能据此诊断。为了判断是否适合普通门诊，请问症状持续多久了，是否发热、咳痰或逐渐加重？目前暂不生成预约建议。";
        if ("神经内科".equals(department))
            return knowledgeHint + "头晕或头痛有多种原因，线上不能仅凭一句话确定原因。请问是突然开始还是反复出现、持续多久，是否伴随走路不稳或恶心？如出现说话不清或肢体无力，请立即急诊。";
        if ("消化内科".equals(department))
            return knowledgeHint + "胃部或腹部不适可能与消化系统有关，但暂不能下结论。请问最明显的位置、持续多久，以及有没有呕吐、腹泻、反酸或黑便？确认后再生成预约方向。";
        if ("妇科".equals(department))
            return knowledgeHint + "经期疼痛较常见，但线上不能判断具体原因。请问疼痛是否与平时不同、持续多久，以及经量或周期是否有明显变化？确认后我再给出预约参考。";
        if ("骨科".equals(department))
            return knowledgeHint + "外伤或关节疼痛通常需要结合受伤方式和活动情况评估。请问何时受伤、疼痛或肿胀是否加重、能否正常活动？若明显变形、出血不止或骨头外露，请立即急诊。";
        return "我会先帮你梳理症状，再决定是否生成预约建议。请补充持续时间和最明显的伴随不适。";
    }

    private String fallbackAnswer(String department, boolean urgent, List<DepartmentCandidate> candidates) {
        if (urgent) return "你描述的症状可能需要紧急评估。请立即拨打当地急救电话或前往急诊，不要等待普通门诊预约。";
        if (candidates.size() > 1) return "你提到了多个不同方向的不适，系统分别列出相关科室和模拟医生供参考；不能仅凭聊天判断它们是否有关。请优先处理更严重或突然出现的问题，必要时先由全科或人工导诊协调。";
        if ("骨科".equals(department)) return "你描述了外伤或疑似骨折，建议尽快到线下医疗机构由骨科或急诊评估，不要等待模拟预约来确认是否骨折。";
        if ("妇科".equals(department)) return "你描述了经期疼痛，可考虑妇科或全科评估；如果疼痛明显加重或与平时不同，请及时线下就医。";
        if ("神经内科".equals(department)) return "头晕、想吐可能有多种原因，单凭这句话无法判断。先考虑神经内科或全科评估：是突然开始还是反复发作？有没有走路不稳、肢体无力、说话不清或剧烈头痛？如有这些新出现的症状，请立即急诊。";
        if ("消化内科".equals(department)) return "你描述的胃部或腹部不适可先咨询消化内科，但现在无法判断原因。请补充持续多久、疼痛位置，以及有没有反复呕吐、呕血或黑便；症状明显加重时应及时就医。";
        if ("呼吸内科".equals(department)) return "你描述的咳嗽或胸闷可先咨询呼吸内科。请补充持续时间、是否发热或咳痰；若出现明显呼吸困难或持续胸痛，请立即急诊。";
        return "目前的信息不足以可靠推荐专科，建议先由全科医学科评估。请补充最不舒服的部位、持续时间，以及是否有发热、胸痛、呼吸困难或意识变化。";
    }

    private long elapsedMillis(long startedAtNanos) {
        return Math.max(1, (System.nanoTime() - startedAtNanos) / 1_000_000);
    }
}
