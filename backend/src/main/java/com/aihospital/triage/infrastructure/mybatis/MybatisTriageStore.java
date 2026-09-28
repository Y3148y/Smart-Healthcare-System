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

    public MybatisTriageStore(TriageMapper mapper, ObjectMapper json) {
        this.mapper = mapper;
        this.json = json;
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
                string(row, "content"), dateTime(row, "created_at"))).toList();
    }
    @Override public List<Assessment> assessments(String sessionId) {
        return mapper.assessments(sessionId).stream().map(row -> new Assessment(integer(row, "version_number"),
                readResult(string(row, "result_json")), dateTime(row, "created_at"),
                string(row, "assistant_message_id"))).toList();
    }
    @Override @Transactional public void saveAssessmentAndAnswer(String sessionId, int version, TriageResult result) {
        try {
            String assessmentId = UUID.randomUUID().toString();
            mapper.insertAssessment(assessmentId, sessionId, version, json.writeValueAsString(result), LocalDateTime.now());
            String assistantMessageId = appendMessage(sessionId, "ASSISTANT", result.summary());
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
    @Override public String appendMessage(String sessionId, String role, String content) {
        String id = UUID.randomUUID().toString();
        mapper.insertMessage(id, sessionId, role, content, LocalDateTime.now());
        return id;
    }
    @Override public void updateSession(String id, String title, String preview, String status) {
        mapper.updateSession(id, title, preview, status, LocalDateTime.now());
    }
    private TriageResult readResult(String value) {
        try { return json.readValue(value, TriageResult.class); }
        catch (JsonProcessingException ex) { throw new IllegalStateException("分诊结果损坏", ex); }
    }
}
