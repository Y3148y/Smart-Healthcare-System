package com.aihospital.triage.domain;

import com.aihospital.shared.model.Models.TriageResult;
import com.aihospital.triage.domain.TriageRecords.*;
import java.time.LocalDateTime;
import java.util.List;

/** The application layer uses this port without knowing MyBatis or JSON storage. */
public interface TriageStore {
    List<Session> sessions(String patient);
    void createSession(String id, String patient, LocalDateTime now);
    List<Message> messages(String sessionId);
    List<Assessment> assessments(String sessionId);
    HumanReview humanReview(String sessionId);
    HumanReview createHumanReview(String sessionId, String patient, String reason, LocalDateTime now);
    void saveAssessmentAndAnswer(String sessionId, int version, TriageResult result);
    List<TimelineEvent> timeline(String patient);
    String appendMessage(String sessionId, String role, String content);
    String appendAssistantMessage(String sessionId, String content, ResponseProvenance provenance);
    void updateSession(String id, String title, String preview, String status);
}
