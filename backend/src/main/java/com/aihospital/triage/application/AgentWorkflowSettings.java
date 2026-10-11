package com.aihospital.triage.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Opt-in extraction and read-only guidance agent. Default OFF retains the original workflow. */
@Component
public record AgentWorkflowSettings(Mode mode, int extractionTimeoutSeconds, int extractionMaxTokens,
                                    int maxSteps, int maxToolCalls, int planningTimeoutSeconds, int totalPlanningSeconds) {
    public enum Mode { OFF, SHADOW, ENABLED }
    public AgentWorkflowSettings(Mode mode,int extractionTimeoutSeconds,int extractionMaxTokens) {
        this(mode,extractionTimeoutSeconds,extractionMaxTokens,3,4,8,25);
    }
    @org.springframework.beans.factory.annotation.Autowired
    public AgentWorkflowSettings(
            @Value("${ai.agent.mode:OFF}") Mode mode,
            @Value("${ai.agent.extraction-timeout-seconds:8}") int extractionTimeoutSeconds,
            @Value("${ai.agent.extraction-max-tokens:2048}") int extractionMaxTokens,
            @Value("${ai.agent.max-steps:3}") int maxSteps,
            @Value("${ai.agent.max-tool-calls:4}") int maxToolCalls,
            @Value("${ai.agent.planning-timeout-seconds:8}") int planningTimeoutSeconds,
            @Value("${ai.agent.total-planning-seconds:25}") int totalPlanningSeconds) {
        if (mode == null) throw new IllegalArgumentException("Agent mode is required");
        if (extractionTimeoutSeconds < 1 || extractionTimeoutSeconds > 35)
            throw new IllegalArgumentException("Intent timeout must be 1..35 seconds");
        if (extractionMaxTokens < 256 || extractionMaxTokens > 4096)
            throw new IllegalArgumentException("Intent output budget must be 256..4096 tokens");
        this.mode = mode;
        this.extractionTimeoutSeconds = extractionTimeoutSeconds;
        this.extractionMaxTokens = extractionMaxTokens;
        if(maxSteps<1||maxSteps>3||maxToolCalls<1||maxToolCalls>4||planningTimeoutSeconds<1
                ||planningTimeoutSeconds>8||totalPlanningSeconds<planningTimeoutSeconds||totalPlanningSeconds>25)
            throw new IllegalArgumentException("Agent planning budget exceeds authorized limits");
        this.maxSteps=maxSteps;this.maxToolCalls=maxToolCalls;
        this.planningTimeoutSeconds=planningTimeoutSeconds;this.totalPlanningSeconds=totalPlanningSeconds;
    }
}
