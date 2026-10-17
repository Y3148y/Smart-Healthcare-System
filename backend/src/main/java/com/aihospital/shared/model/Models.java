package com.aihospital.shared.model;

import java.time.LocalDateTime;
import java.util.List;

public final class Models {
    private Models() {}
    public record LoginRequest(String username, String password) {}
    public record LoginResponse(String token, String role, String displayName) {}
    public record SendMessage(String content, String requestId) {
        public SendMessage(String content) { this(content,null); }
    }
    public record Doctor(String id, String name, String title, String department, String period, String date, int remaining, int total, int fee) {}
    public record Evidence(String title, String source, String excerpt, double score, List<String> sectionPath) {
        public Evidence { sectionPath = sectionPath == null ? List.of() : List.copyOf(sectionPath); }
        public Evidence(String title, String source, String excerpt, double score) {
            this(title, source, excerpt, score, List.of());
        }
    }
    public record ToolTrace(String tool, String label, String input, String outcome, long elapsedMs,
                            boolean success, String error) {}
    public record SafetySignal(String ruleCode, String category, String evidence, String reason) {}
    public record SafetyAssessment(String policyVersion, String acuity, boolean stopRoutineFlow,
                                   boolean humanReviewRecommended, List<SafetySignal> signals,
                                   List<String> actions) {}
    public record DepartmentCandidate(String department, String reason, Doctor doctor) {}
    public record TriageResult(String sessionId, String riskLevel, int confidence, String department, Doctor doctor,
                               String summary, String safetyTip, List<Evidence> evidence, List<ToolTrace> tools,
                               List<DepartmentCandidate> candidates,
                               String modelStatus, String modelName, LocalDateTime createdAt,
                               SafetyAssessment safetyAssessment, boolean grounded, String groundingMessage,
                               com.aihospital.triage.domain.AnswerEvidence.Diagnostics answerEvidence) {
        public TriageResult(String sessionId, String riskLevel, int confidence, String department, Doctor doctor,
                String summary, String safetyTip, List<Evidence> evidence, List<ToolTrace> tools,
                List<DepartmentCandidate> candidates, String modelStatus, String modelName, LocalDateTime createdAt,
                SafetyAssessment safetyAssessment, boolean grounded, String groundingMessage) {
            this(sessionId, riskLevel, confidence, department, doctor, summary, safetyTip, evidence, tools,
                    candidates, modelStatus, modelName, createdAt, safetyAssessment, grounded, groundingMessage, null);
        }
    }
    public record ChatSession(String id, String title, String preview, String status, LocalDateTime createdAt) {}
    public record Appointment(String id, String registrationNo, String patient, Doctor doctor, String status, String triageSessionId, LocalDateTime createdAt) {}
    public record KnowledgeDocument(String id, String title, String body, int chunks, String status, LocalDateTime updatedAt) {}
    public record Tool(String code, String name, String description, boolean enabled, int order) {}
    public record TokenUsage(Integer inputTokens,Integer outputTokens,String source) {
        public TokenUsage {
            if(source==null||!java.util.Set.of("PROVIDER_REPORTED","NOT_REPORTED","NOT_APPLICABLE","LEGACY_RECORDED").contains(source)
                    ||inputTokens!=null&&inputTokens<0||outputTokens!=null&&outputTokens<0)
                throw new IllegalArgumentException("Invalid token usage");
            if("NOT_REPORTED".equals(source)&&(inputTokens!=null||outputTokens!=null)
                    ||"NOT_APPLICABLE".equals(source)&&(!Integer.valueOf(0).equals(inputTokens)||!Integer.valueOf(0).equals(outputTokens))
                    ||"PROVIDER_REPORTED".equals(source)&&inputTokens==null&&outputTokens==null)
                throw new IllegalArgumentException("Inconsistent token usage source");
        }
        public static TokenUsage reported(Integer input,Integer output) {
            input=input==null||input<0?null:input;output=output==null||output<0?null:output;
            return new TokenUsage(input,output,input==null&&output==null?"NOT_REPORTED":"PROVIDER_REPORTED");
        }
        public static TokenUsage local(){return new TokenUsage(0,0,"NOT_APPLICABLE");}
        public static TokenUsage legacy(int input,int output){return new TokenUsage(input>0?input:null,output>0?output:null,"LEGACY_RECORDED");}
    }
    public record CallLog(String id, LocalDateTime time, String purpose, String user, String model, int inputTokens, int outputTokens,
                          long elapsedMs, boolean success, List<ToolTrace> tools, String traceId,TokenUsage usage) {
        public CallLog {usage=usage==null?TokenUsage.legacy(inputTokens,outputTokens):usage;}
        public CallLog(String id, LocalDateTime time, String purpose, String user, String model, int inputTokens, int outputTokens,
                       long elapsedMs, boolean success, List<ToolTrace> tools, String traceId) {
            this(id,time,purpose,user,model,inputTokens,outputTokens,elapsedMs,success,tools,traceId,null);
        }
        public CallLog(String id, LocalDateTime time, String purpose, String user, String model, int inputTokens, int outputTokens,
                       long elapsedMs, boolean success, List<ToolTrace> tools) {
            this(id, time, purpose, user, model, inputTokens, outputTokens, elapsedMs, success, tools,
                    com.aihospital.shared.diagnostics.TurnTraceContext.currentId());
        }
    }
    public record Dashboard(int sessions, int appointments, int completed, double acceptanceRate, int documents, int toolCalls) {}
}
