package com.aihospital.triage.application;

import com.aihospital.shared.model.Models.TriageResult;
import com.aihospital.shared.model.Models.Doctor;
import com.aihospital.shared.model.Models.DepartmentCandidate;
import com.aihospital.triage.domain.Disposition;
import com.aihospital.triage.domain.TriageEngine;
import com.aihospital.triage.domain.NarrationModel;
import com.aihospital.catalog.application.DoctorCatalogService;
import com.aihospital.triage.domain.TriageStore;
import com.aihospital.triage.domain.TriageRecords.*;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

@Service
public class TriageConversationService {
    private final TriageStore store;
    private final TriageEngine triageEngine;
    private final DoctorCatalogService catalog;

    public TriageConversationService(TriageStore store, TriageEngine triageEngine, DoctorCatalogService catalog) {
        this.store = store;
        this.triageEngine = triageEngine;
        this.catalog = catalog;
    }

    public List<Session> sessions(String patient) {
        return store.sessions(patient);
    }

    public Conversation create(String patient, Eligibility eligibility) {
        if (eligibility == null || !eligibility.adultConfirmed() || !eligibility.forSelfConfirmed() || !eligibility.notPregnantConfirmed())
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "当前演示仅支持成年人本人、非孕产期的非急症预问诊");
        String id = java.util.UUID.randomUUID().toString();
        store.createSession(id, patient, LocalDateTime.now());
        return conversation(id, patient);
    }

    public Conversation conversation(String id, String patient) {
        Session session = requireOwner(id, patient);
        List<Message> messages = store.messages(id);
        List<Assessment> assessments = store.assessments(id);
        return new Conversation(session, messages, associateLegacyAnchors(messages, assessments), store.humanReview(id));
    }

    public Session requireOwner(String id, String patient) {
        return sessions(patient).stream().filter(session -> session.id().equals(id)).findFirst()
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.NOT_FOUND, "会话不存在"));
    }

    public Conversation send(String id, String patient, String text) {
        Session session = requireOwner(id, patient);
        if ("紧急提示".equals(session.status()))
            throw new ResponseStatusException(HttpStatus.CONFLICT, "紧急会话不能继续普通分诊，请立即寻求线下帮助");
        String content = text == null ? "" : text.trim();
        if (content.isBlank() || content.length() > 2000)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "症状描述应为 1 至 2000 字");

        append(id, "USER", content);
        String title = "新会话".equals(session.title()) ? content.substring(0, Math.min(24, content.length())) : session.title();
        update(id, title, content.substring(0, Math.min(120, content.length())), "处理中");

        Conversation current = conversation(id, patient);
        List<NarrationModel.Turn> history = current.messages().stream()
                .filter(message -> "USER".equals(message.role()) || "ASSISTANT".equals(message.role()))
                .map(message -> new NarrationModel.Turn(message.role(), message.content())).toList();
        List<String> patientTexts = current.messages().stream().filter(message -> "USER".equals(message.role()))
                .map(Message::content).toList();
        String combined = patientTexts.stream().collect(Collectors.joining("。"));
        if (!triageEngine.requiresImmediateCare(combined) && triageEngine.needsClarification(combined)) {
            TriageEngine.Guidance guidance = triageEngine.clarificationPrompt(combined, history);
            store.appendAssistantMessage(id, guidance.text(), new ResponseProvenance(guidance.modelStatus(),
                    guidance.knowledgeHits(), guidance.localToolCalls(), guidance.toolFailures()));
            update(id, title, content.substring(0, Math.min(120, content.length())), Disposition.PENDING);
            return conversation(id, patient);
        }

        final TriageResult result;
        try {
            result = withCurrentAvailability(triageEngine.triage(id, combined, patient, history));
        } catch (RuntimeException ex) {
            update(id, title, content.substring(0, Math.min(120, content.length())), "待重试");
            throw ex;
        }
        if (!result.grounded() && !Disposition.EMERGENCY.equals(result.riskLevel())) {
            store.appendAssistantMessage(id, result.summary(), provenance(result));
            update(id, title, content.substring(0, Math.min(120, content.length())), Disposition.PENDING);
            return conversation(id, patient);
        }
        int version = current.assessments().size() + 1;
        store.saveAssessmentAndAnswer(id, version, result);
        update(id, title, content.substring(0, Math.min(120, content.length())), Disposition.sessionStatus(result.riskLevel()));
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
        return store.createHumanReview(id, patient, normalized, LocalDateTime.now());
    }

    private TriageResult withCurrentAvailability(TriageResult result) {
        if (Disposition.EMERGENCY.equals(result.riskLevel())) return result;
        boolean schedulingAvailable = Disposition.isBookable(result.riskLevel()) && result.grounded()
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
                result.grounded(), result.groundingMessage());
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
                (int) result.tools().stream().filter(trace -> !trace.success()).count());
    }

    private void update(String id, String title, String preview, String status) {
        store.updateSession(id, title, preview, status);
    }
}
