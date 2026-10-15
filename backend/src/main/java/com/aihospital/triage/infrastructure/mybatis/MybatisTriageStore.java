package com.aihospital.triage.infrastructure.mybatis;

import com.aihospital.shared.model.Models.TriageResult;
import com.aihospital.triage.domain.TriageRecords.*;
import com.aihospital.triage.domain.TriageStore;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static com.aihospital.shared.infrastructure.mybatis.RowValues.*;

@Repository
public class MybatisTriageStore implements TriageStore {
    private final TriageMapper mapper;
    private final ObjectMapper json;
    private final TriageTurnMapper turns;

    public MybatisTriageStore(TriageMapper mapper, ObjectMapper json, TriageTurnMapper turns) {
        this.mapper = mapper;
        this.json = json;
        this.turns = turns;
    }

    @Override @Transactional public com.aihospital.triage.domain.TriageTurn.Claim beginTurn(
            String sessionId, String requestId, String content, int leaseSeconds) {
        if (mapper.lockMessageSession(sessionId) == null) throw new IllegalArgumentException("Session does not exist");
        reconcileLocked(sessionId);
        var old = turns.find(sessionId, requestId);
        String hash = com.aihospital.triage.domain.AnswerEvidence.hash(content);
        if (old != null) {
            String outcome = !hash.equals(string(old,"content_hash")) ? "CONFLICT"
                    : "COMPLETED".equals(string(old,"status")) ? "REPLAY"
                    : "RUNNING".equals(string(old,"status")) ? "BUSY" : "INTERRUPTED";
            return new com.aihospital.triage.domain.TriageTurn.Claim(readTurn(old), outcome);
        }
        if (!turns.running(sessionId).isEmpty()) return new com.aihospital.triage.domain.TriageTurn.Claim(null,"BUSY");
        String status = mapper.sessionStatus(sessionId);
        if ("紧急提示".equals(status)) return new com.aihospital.triage.domain.TriageTurn.Claim(null,"BLOCKED");
        String userId = appendMessage(sessionId,"USER",content);
        var now = turns.now();
        turns.insert(UUID.randomUUID().toString(),sessionId,requestId,hash,userId,now,now.plusSeconds(leaseSeconds));
        mapper.markProcessing(sessionId,content.substring(0,Math.min(24,content.length())),
                content.substring(0,Math.min(120,content.length())),now);
        return new com.aihospital.triage.domain.TriageTurn.Claim(readTurn(turns.find(sessionId,requestId)),"CLAIMED");
    }
    @Override @Transactional public void completeTurn(com.aihospital.triage.domain.TriageTurn turn, Runnable persistAnswer) {
        mapper.lockMessageSession(turn.sessionId());
        var active = readTurn(turns.find(turn.sessionId(),turn.requestId()));
        if (active == null || !turn.id().equals(active.id()) || !"RUNNING".equals(active.status())
                || !active.expiresAt().isAfter(turns.now()))
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.CONFLICT,
                    "本轮处理期限已结束，请刷新会话核对；旧结果未保存");
        persistAnswer.run();
        var messages = messages(turn.sessionId());
        var last = messages.get(messages.size()-1);
        if (!"ASSISTANT".equals(last.role())) throw new IllegalStateException("Turn completion requires an answer");
        if (turns.finish(turn.id(),"COMPLETED",last.id(),turns.now(),null) != 1)
            throw new IllegalStateException("Turn ownership changed");
    }
    @Override @Transactional public void failTurn(com.aihospital.triage.domain.TriageTurn turn, String failureCode) {
        mapper.lockMessageSession(turn.sessionId());
        if (turns.finish(turn.id(),"FAILED",null,turns.now(),failureCode) == 1)
            mapper.markRetry(turn.sessionId(),turns.now());
    }
    @Override @Transactional public void reconcileTurns(String sessionId) {
        if (mapper.lockMessageSession(sessionId) != null) reconcileLocked(sessionId);
    }
    private void reconcileLocked(String sessionId) {
        var now = turns.now();
        for (var row : turns.running(sessionId)) {
            var active = readTurn(row);
            if (!active.expiresAt().isAfter(now) && turns.finish(active.id(),"INTERRUPTED",null,now,"TURN_LEASE_EXPIRED") == 1)
                mapper.markRetry(sessionId,now);
        }
        // Upgrade requires stopping pre-ledger instances. Keep legacy messages intact;
        // an untracked processing marker cannot authorize replay of the old operation.
        if (turns.latest(sessionId) == null && "处理中".equals(mapper.sessionStatus(sessionId)))
            mapper.markRetry(sessionId,now);
    }
    @Override public com.aihospital.triage.domain.TriageTurn turn(String sessionId,String requestId) {
        return readTurn(turns.find(sessionId,requestId));
    }
    @Override public com.aihospital.triage.domain.TriageTurn latestTurn(String sessionId) { return readTurn(turns.latest(sessionId)); }
    private static com.aihospital.triage.domain.TriageTurn readTurn(java.util.Map<String,Object> row) {
        if (row == null || row.isEmpty()) return null;
        return new com.aihospital.triage.domain.TriageTurn(string(row,"id"),string(row,"session_id"),string(row,"request_id"),
                string(row,"status"),string(row,"user_message_id"),string(row,"assistant_message_id"),dateTime(row,"started_at"),
                dateTime(row,"expires_at"),value(row,"finished_at") == null ? null : dateTime(row,"finished_at"),string(row,"failure_code"));
    }

    @Override public List<Session> sessions(String patient) {
        return mapper.sessions(patient).stream().map(row -> new Session(string(row, "id"), string(row, "title"),
                string(row, "preview"), string(row, "status"), dateTime(row, "created_at"),
                dateTime(row, "updated_at"))).toList();
    }
    @Override @Transactional public void createSession(String id, String patient, LocalDateTime now) {
        mapper.insertSession(id, patient, "新会话", "尚未描述症状", "进行中", now, now);
        mapper.insertEligibility(id, now);
    }
    @Override public List<Message> messages(String sessionId) {
        return mapper.messages(sessionId).stream().map(row -> new Message(string(row, "id"), string(row, "role"),
                string(row, "content"), dateTime(row, "created_at"), readProvenance(string(row, "meta_json")))).toList();
    }
    @Override public List<Assessment> assessments(String sessionId) {
        return mapper.assessments(sessionId).stream().map(row -> new Assessment(integer(row, "version_number"),
                readResult(string(row, "result_json")), dateTime(row, "created_at"),
                string(row, "assistant_message_id"))).toList();
    }
    @Override public HumanReview humanReview(String sessionId) {
        var row = mapper.humanReview(sessionId);
        if (row == null || row.isEmpty()) return null;
        return new HumanReview(string(row, "id"), string(row, "session_id"), string(row, "patient_id"),
                string(row, "reason"), string(row, "status"), dateTime(row, "created_at"));
    }
    @Override @Transactional public HumanReview createHumanReview(String sessionId, String patient, String reason, LocalDateTime now) {
        mapper.lockMessageSession(sessionId);
        HumanReview existing=humanReview(sessionId);
        if(existing!=null)return existing;
        String id = UUID.randomUUID().toString();
        mapper.insertHumanReview(id, sessionId, patient, reason, now);
        return new HumanReview(id, sessionId, patient, reason, "PENDING", now);
    }
    @Override @Transactional public void saveAssessmentAndAnswer(String sessionId, int version, TriageResult result) {
        try {
            String assessmentId = UUID.randomUUID().toString();
            mapper.insertAssessment(assessmentId, sessionId, version, json.writeValueAsString(result), LocalDateTime.now());
            ResponseProvenance provenance = new ResponseProvenance(result.modelStatus(), result.evidence().size(),
                    result.tools().size(), (int) result.tools().stream().filter(trace -> !trace.success()).count(), result.answerEvidence());
            String assistantMessageId = appendAssistantMessage(sessionId, result.summary(), provenance);
            mapper.insertAnchor(assessmentId, assistantMessageId);
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("无法保存分诊结果", ex);
        }
    }
    @Override public List<TimelineEvent> timeline(String patient) {
        return mapper.timeline(patient).stream().map(row -> new TimelineEvent(string(row, "session_id"),
                string(row, "title"), string(row, "id"), string(row, "content"), "患者自述",
                dateTime(row, "created_at"))).toList();
    }
    @Override @Transactional public String appendMessage(String sessionId, String role, String content) {
        // Allocate insertion order under a database row lock, independent of clock precision/UUID.
        if (mapper.lockMessageSession(sessionId) == null)
            throw new IllegalArgumentException("Session does not exist");
        long sequence = mapper.nextMessageSequence(sessionId);
        String id = UUID.randomUUID().toString();
        mapper.insertMessage(id, sessionId, role, content, LocalDateTime.now());
        mapper.insertMessageOrder(id, sessionId, sequence);
        return id;
    }
    @Override @Transactional public String appendAssistantMessage(String sessionId, String content,
                                                                   ResponseProvenance provenance) {
        String id = appendMessage(sessionId, "ASSISTANT", content);
        try { mapper.insertMessageProvenance(id, json.writeValueAsString(provenance)); }
        catch (JsonProcessingException ex) { throw new IllegalStateException("无法保存回答来源状态", ex); }
        return id;
    }
    @Override public void updateSession(String id, String title, String preview, String status) {
        mapper.updateSession(id, title, preview, status, LocalDateTime.now());
    }
    private TriageResult readResult(String value) {
        try { return json.readValue(value, TriageResult.class); }
        catch (JsonProcessingException ex) { throw new IllegalStateException("分诊结果损坏", ex); }
    }
    private ResponseProvenance readProvenance(String value) {
        if (value == null || value.isBlank()) return null;
        try { return json.readValue(value, ResponseProvenance.class); }
        catch (JsonProcessingException ex) { throw new IllegalStateException("回答来源状态损坏", ex); }
    }
}
