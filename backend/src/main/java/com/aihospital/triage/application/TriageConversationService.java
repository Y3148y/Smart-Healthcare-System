package com.aihospital.triage.application;

import com.aihospital.shared.model.Models.TriageResult;
import com.aihospital.shared.model.Models.Doctor;
import com.aihospital.shared.model.Models.DepartmentCandidate;
import com.aihospital.triage.domain.Disposition;
import com.aihospital.triage.domain.TriageEngine;
import com.aihospital.triage.domain.NarrationModel;
import com.aihospital.catalog.application.DoctorCatalogService;
import com.aihospital.triage.domain.TriageStore;
import com.aihospital.triage.domain.TriageRecords.Assessment;
import com.aihospital.triage.domain.TriageRecords.Conversation;
import com.aihospital.triage.domain.TriageRecords.Eligibility;
import com.aihospital.triage.domain.TriageRecords.HumanReview;
import com.aihospital.triage.domain.TriageRecords.Message;
import com.aihospital.triage.domain.TriageRecords.ResponseProvenance;
import com.aihospital.triage.domain.TriageRecords.Session;
import com.aihospital.triage.domain.TriageRecords.TimelineEvent;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class TriageConversationService {
    private final TriageStore store;
    private final DoctorCatalogService catalog;
    private final com.aihospital.observation.domain.CallLogStore calls;
    private final TriageWorkflow workflow;
    private final Set<String> inFlight = java.util.concurrent.ConcurrentHashMap.newKeySet();
    @org.springframework.beans.factory.annotation.Value("${ai.triage.turn-lease-seconds:180}")
    private int turnLeaseSeconds = 180;
    private static final org.slf4j.Logger log = org.slf4j.LoggerFactory.getLogger(TriageConversationService.class);

    public TriageConversationService(TriageStore store, TriageEngine triageEngine, DoctorCatalogService catalog,
                                    com.aihospital.observation.domain.CallLogStore calls) {
        this(store,triageEngine,catalog,calls,null);
    }
    @org.springframework.beans.factory.annotation.Autowired
    public TriageConversationService(TriageStore store, TriageEngine triageEngine, DoctorCatalogService catalog,
                                    com.aihospital.observation.domain.CallLogStore calls, AgentWorkflowCoordinator agentWorkflow) {
        this.store = store;
        this.catalog = catalog;
        this.calls = calls;
        this.workflow = new TriageWorkflow(triageEngine, agentWorkflow);
    }

    public List<Session> sessions(String patient) {
        return store.sessions(patient);
    }

    public Conversation create(String patient, Eligibility eligibility) {
        if (eligibility == null || !eligibility.adultConfirmed() || !eligibility.forSelfConfirmed() || !eligibility.notPregnantConfirmed())
            // D3: 边界说明而非孕产状态的确认。孕产排除只是此处的一次性自我声明，
            // 后续轮次不做复查，因此不能向患者表述为系统已完成孕产核验。
            // 文案依据：GPT 对 D3 的复裁（选 A），并已用中文孕产引用替换 NG253。
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,
                    "当前演示仅支持成年人本人、非孕产期的非急症预问诊。"
                    + "本系统不提供孕产期常规预问诊：如果您正在或可能怀孕、近期分娩，请停止普通分诊并咨询线下医疗人员。"
                    + "孕期或可能怀孕时如有出血：出血量大、持续不止，或伴剧烈腹痛、头晕晕厥，请立即寻求急诊帮助；"
                    + "少量出血也请尽快联系线下医疗人员，不要自行处理。"
                    + "此处填写的是一次性自我声明，不是系统对孕产状态的确认或排除。");
        String id = java.util.UUID.randomUUID().toString();
        store.createSession(id, patient, LocalDateTime.now());
        return conversation(id, patient);
    }

    public Conversation conversation(String id, String patient) {
        requireOwner(id,patient);
        store.reconcileTurns(id);
        Session session = requireOwner(id, patient);
        List<Message> messages = store.messages(id);
        List<Assessment> assessments = store.assessments(id);
        return new Conversation(session, messages, associateLegacyAnchors(messages, assessments), store.humanReview(id),store.latestTurn(id));
    }

    public com.aihospital.triage.domain.TriageTurn turn(String id,String patient,String requestId) {
        requireOwner(id,patient);
        store.reconcileTurns(id);
        var turn = store.turn(id,requestId);
        if (turn == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"轮次不存在");
        return turn;
    }

    public Session requireOwner(String id, String patient) {
        return sessions(patient).stream().filter(session -> session.id().equals(id)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在"));
    }

    public Conversation send(String id, String patient, String text) {
        return send(id,patient,text,null);
    }

    public Conversation send(String id,String patient,String text,
            java.util.function.Consumer<com.aihospital.triage.domain.TriageProgress> progress) {
        return send(id,patient,text,null,progress);
    }
    public Conversation send(String id,String patient,String text,String requestId,
            java.util.function.Consumer<com.aihospital.triage.domain.TriageProgress> progress) {
        Session session = requireOwner(id, patient);
        if (!inFlight.add(id))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "该会话正在处理，请等待或刷新会话核对");
        try {
            return sendOwned(id, patient, text, requestId, progress, session);
        } finally {
            inFlight.remove(id);
        }
    }

    private Conversation sendOwned(String id,String patient,String text,String requestId,
            java.util.function.Consumer<com.aihospital.triage.domain.TriageProgress> progress, Session session) {
        java.util.function.Consumer<com.aihospital.triage.domain.TriageProgress> updates=progress==null?stage->{}:progress;
        String content = text == null ? "" : text.trim();
        if (content.isBlank() || content.length() > 2000)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "症状描述应为 1 至 2000 字");

        String key = requestId == null ? java.util.UUID.randomUUID().toString() : requestId;
        if (!key.matches("[A-Za-z0-9][A-Za-z0-9_-]{7,79}"))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"轮次请求标识格式无效");
        if (turnLeaseSeconds < 30 || turnLeaseSeconds > 900) throw new IllegalStateException("Invalid turn lease configuration");
        var claim = store.beginTurn(id,key,content,turnLeaseSeconds);
        if ("REPLAY".equals(claim.outcome())) return conversation(id,patient);
        if (!"CLAIMED".equals(claim.outcome())) throw new ResponseStatusException(HttpStatus.CONFLICT,
                switch(claim.outcome()) {
                    case "CONFLICT" -> "同一请求标识不能更换问题内容";
                    case "INTERRUPTED" -> "本轮已终止，请先查看保存状态；如需重新提问，请发起新轮次";
                    case "BLOCKED" -> "紧急会话不能继续普通分诊，请立即寻求线下帮助";
                    default -> "该会话正在处理，请等待或刷新会话核对";
                });
        var turn = claim.turn();
        String userMessageId = turn.userMessageId();
        try (var invocation = com.aihospital.shared.diagnostics.InvocationContext.open(com.aihospital.shared.diagnostics.InvocationContext.Origin.WORKFLOW);
             var trace = com.aihospital.shared.diagnostics.TurnTraceContext.open(userMessageId,
                com.aihospital.triage.domain.TriageSafetyPolicy.POLICY_VERSION);
             var diagnostic = com.aihospital.shared.diagnostics.SyntheticReviewDiagnostics.open(id,patient,key,userMessageId,
                     () -> store.messages(id).stream().filter(m -> "USER".equals(m.role())).map(Message::content).toList())) {
            try {
                Conversation result = processTurn(id, patient, content, session, updates, progress, trace,turn);
                var provenance = result.messages().stream().filter(m -> "ASSISTANT".equals(m.role()))
                        .reduce((a,b) -> b).map(Message::provenance).orElse(null);
                boolean success = provenance != null && !Set.of("FALLBACK", "FALLBACK_UNGROUNDED", "VALIDATION_BLOCKED", "EVIDENCE_BLOCKED", "SOURCE_EXCERPT").contains(provenance.modelStatus())
                        && provenance.toolFailures() == 0;
                recordTerminal(trace, success, provenance == null ? "NO_ANSWER" : provenance.modelStatus());
                return result;
            } catch (RuntimeException failure) {
                try { store.failTurn(turn,"TURN_PROCESSING_FAILED"); }
                catch (RuntimeException unavailable) { log.warn("Turn failure state unavailable causeType={}",unavailable.getClass().getSimpleName()); }
                recordTerminal(trace, false, "FAILED/" + failure.getClass().getSimpleName());
                throw failure;
            }
        }
    }

    private void recordTerminal(com.aihospital.shared.diagnostics.TurnTraceContext trace, boolean success, String status) {
        var metadata = com.aihospital.shared.diagnostics.TurnTraceContext.metadata();
        try {
            calls.record(new com.aihospital.shared.model.Models.CallLog(java.util.UUID.randomUUID().toString(),
                    LocalDateTime.now(), "会话处理", "", metadata.route() + "/" + status,
                    0, 0, metadata.elapsedMs(), success, List.of()));
        } catch (RuntimeException unavailable) {
            // Do not replace a completed safety reply or mask the original failure with an audit write failure.
            log.warn("Turn terminal audit unavailable traceId={} causeType={}", metadata.traceId(), unavailable.getClass().getSimpleName());
        }
    }

    private Conversation processTurn(String id, String patient, String content, Session session,
            java.util.function.Consumer<com.aihospital.triage.domain.TriageProgress> updates,
            java.util.function.Consumer<com.aihospital.triage.domain.TriageProgress> progress,
            com.aihospital.shared.diagnostics.TurnTraceContext trace, com.aihospital.triage.domain.TriageTurn turn) {
        String title = "新会话".equals(session.title()) ? content.substring(0, Math.min(24, content.length())) : session.title();

        Conversation current = conversation(id, patient);
        List<NarrationModel.Turn> history = current.messages().stream()
                .filter(message -> "USER".equals(message.role()) || "ASSISTANT".equals(message.role()))
                .map(message -> new NarrationModel.Turn(message.role(), message.content())).toList();
        List<String> patientTexts = current.messages().stream().filter(message -> "USER".equals(message.role()))
                .map(Message::content).toList();
        String combined = patientTexts.stream().collect(Collectors.joining("。"));
        var computed = workflow.execute(current, patient, combined, content, history, updates, progress != null, trace);
        TriageWorkflow.Outcome outcome = computed instanceof TriageWorkflow.AssessmentOutcome assessed
                ? new TriageWorkflow.AssessmentOutcome(withCurrentAvailability(assessed.result())) : computed;
        if (outcome instanceof TriageWorkflow.GuidanceOutcome guided) {
            TriageEngine.Guidance guidance = guided.guidance();
            updates.accept(com.aihospital.triage.domain.TriageProgress.SAVING);
            if ("POLICY_REFUSAL".equals(guidance.modelStatus())) trace.route("POLICY_REFUSAL");
            store.completeTurn(turn, () -> {
                store.appendAssistantMessage(id, guidance.text(), new ResponseProvenance(guidance.modelStatus(),
                        guidance.knowledgeHits(), guidance.localToolCalls(), guidance.toolFailures(), guidance.answerEvidence()));
                update(id, title, content.substring(0, Math.min(120, content.length())), Disposition.PENDING);
            });
            return conversation(id, patient);
        }

        TriageResult result = ((TriageWorkflow.AssessmentOutcome) outcome).result();
        // Retrieval failure must never discard a safety signal. EMERGENCY already bypasses this
        // branch; URGENT previously did not, so an ungrounded result silently downgraded a
        // "see a doctor today" signal to 待补充信息 and hid it from the session. A flagged
        // disposition is reported even when nothing could be grounded, with grounded=false so
        // the UI still shows that the reasoning is not evidence-backed.
        updates.accept(com.aihospital.triage.domain.TriageProgress.SAVING);
        if (result.safetyAssessment() != null && result.safetyAssessment().humanReviewRecommended()) trace.route("SAFETY");
        if ("POLICY_REFUSAL".equals(result.modelStatus())) trace.route("POLICY_REFUSAL");
        if (!result.grounded() && !result.safetyAssessment().humanReviewRecommended()) {
            store.completeTurn(turn, () -> {
                store.appendAssistantMessage(id, result.summary(), provenance(result));
                update(id, title, content.substring(0, Math.min(120, content.length())), Disposition.PENDING);
            });
            return conversation(id, patient);
        }
        int version = current.assessments().size() + 1;
        store.completeTurn(turn, () -> {
            store.saveAssessmentAndAnswer(id, version, result);
            update(id, title, content.substring(0, Math.min(120, content.length())), Disposition.sessionStatus(result.riskLevel()));
        });
        return conversation(id, patient);
    }

    public List<TimelineEvent> timeline(String patient) {
        return store.timeline(patient);
    }

    public TriageResult latestResult(String id, String patient) {
        List<Assessment> assessments = conversation(id, patient).assessments();
        return assessments.isEmpty() ? null : assessments.get(assessments.size() - 1).result();
    }

    public HumanReview requestHumanReview(String id, String patient, String reason) {
        requireOwner(id, patient);
        HumanReview existing = store.humanReview(id);
        if (existing != null) return existing;
        String normalized = reason == null || reason.isBlank() ? "患者主动申请人工导诊" : reason.trim();
        if (normalized.length() > 500) normalized = normalized.substring(0, 500);
        try {
            return store.createHumanReview(id, patient, normalized, LocalDateTime.now());
        } catch (DuplicateKeyException concurrentRequest) {
            // The unique session constraint is the final concurrency guard. If another
            // request won the insert race, return its row so duplicate submissions remain idempotent.
            HumanReview createdByConcurrentRequest = store.humanReview(id);
            if (createdByConcurrentRequest != null) return createdByConcurrentRequest;
            throw concurrentRequest;
        }
    }

    private TriageResult withCurrentAvailability(TriageResult result) {
        if (Disposition.EMERGENCY.equals(result.riskLevel())) return result;
        boolean schedulingAvailable = Disposition.isBookable(result.riskLevel()) && result.grounded()
                && !"SOURCE_EXCERPT".equals(result.modelStatus())
                && result.tools().stream()
                .anyMatch(trace -> "doctor_schedule_search".equals(trace.tool()) && trace.success());
        Doctor available = !schedulingAvailable ? null : catalog.doctors(result.department()).stream()
                .filter(doctor -> doctor.remaining() > 0).findFirst().orElse(null);
        List<DepartmentCandidate> candidates = result.candidates() == null ? List.of() : result.candidates().stream()
                .map(candidate -> new DepartmentCandidate(candidate.department(), candidate.reason(),
                        !schedulingAvailable ? null : catalog.doctors(candidate.department()).stream()
                                .filter(doctor -> doctor.remaining() > 0).findFirst().orElse(null)))
                .toList();
        return new TriageResult(result.sessionId(), result.riskLevel(), result.confidence(), result.department(),
                available, result.summary(), result.safetyTip(), result.evidence(), result.tools(), candidates,
                result.modelStatus(), result.modelName(), result.createdAt(), result.safetyAssessment(),
                result.grounded(), result.groundingMessage(), result.answerEvidence());
    }

    private List<Assessment> associateLegacyAnchors(List<Message> messages, List<Assessment> assessments) {
        Set<String> used = new HashSet<>();
        assessments.stream().map(Assessment::assistantMessageId).filter(id -> id != null).forEach(used::add);
        return assessments.stream().map(assessment -> {
            if (assessment.assistantMessageId() != null) return assessment;
            String matched = messages.stream()
                    .filter(message -> "ASSISTANT".equals(message.role()) && !used.contains(message.id()))
                    .filter(message -> message.content().equals(assessment.result().summary()))
                    .map(Message::id).findFirst().orElse(null);
            if (matched != null) used.add(matched);
            return new Assessment(assessment.version(), assessment.result(), assessment.createdAt(), matched);
        }).toList();
    }

    private String append(String sessionId, String role, String content) {
        return store.appendMessage(sessionId, role, content);
    }

    private ResponseProvenance provenance(TriageResult result) {
        return new ResponseProvenance(result.modelStatus(), result.evidence().size(), result.tools().size(),
                (int) result.tools().stream().filter(trace -> !trace.success()).count(), result.answerEvidence());
    }

    private void update(String id, String title, String preview, String status) {
        store.updateSession(id, title, preview, status);
    }
}
