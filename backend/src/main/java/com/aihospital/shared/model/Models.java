package com.aihospital.shared.model;

import java.time.LocalDateTime;
import java.util.List;

public final class Models {
    private Models() {}
    public record LoginRequest(String username, String password) {}
    public record LoginResponse(String token, String role, String displayName) {}
    public record SendMessage(String content) {}
    public record Doctor(String id, String name, String title, String department, String period, String date, int remaining, int total, int fee) {}
    public record Evidence(String title, String source, String excerpt, double score) {}
    public record ToolTrace(String tool, String label, String input, String outcome, long elapsedMs) {}
    public record DepartmentCandidate(String department, String reason, Doctor doctor) {}
    public record TriageResult(String sessionId, String riskLevel, int confidence, String department, Doctor doctor,
                               String summary, String safetyTip, List<Evidence> evidence, List<ToolTrace> tools,
                               List<DepartmentCandidate> candidates,
                               String modelStatus, String modelName, LocalDateTime createdAt) {}
    public record ChatSession(String id, String title, String preview, String status, LocalDateTime createdAt) {}
    public record Appointment(String id, String registrationNo, String patient, Doctor doctor, String status, String triageSessionId, LocalDateTime createdAt) {}
    public record KnowledgeDocument(String id, String title, String body, int chunks, String status, LocalDateTime updatedAt) {}
    public record Tool(String code, String name, String description, boolean enabled, int order) {}
    public record CallLog(String id, LocalDateTime time, String purpose, String user, String model, int inputTokens, int outputTokens,
                          long elapsedMs, boolean success, List<ToolTrace> tools) {}
    public record Dashboard(int sessions, int appointments, int completed, double acceptanceRate, int documents, int toolCalls) {}
}
