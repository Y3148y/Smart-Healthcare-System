package com.aihospital.triage.application;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/** Opt-in shadow extraction. Enabled execution is rejected until the tool workflow is integrated. */
@Component
public record AgentWorkflowSettings(Mode mode, int extractionTimeoutSeconds, int extractionMaxTokens) {
    public enum Mode { OFF, SHADOW, ENABLED }
    public AgentWorkflowSettings(
            @Value("${ai.agent.mode:OFF}") Mode mode,
            @Value("${ai.agent.extraction-timeout-seconds:8}") int extractionTimeoutSeconds,
            @Value("${ai.agent.extraction-max-tokens:2048}") int extractionMaxTokens) {
        if (mode == null || mode == Mode.ENABLED)
            throw new IllegalArgumentException("Agent execution is not integrated; use OFF or SHADOW");
        if (extractionTimeoutSeconds < 1 || extractionTimeoutSeconds > 35)
            throw new IllegalArgumentException("Intent timeout must be 1..35 seconds");
        if (extractionMaxTokens < 256 || extractionMaxTokens > 4096)
            throw new IllegalArgumentException("Intent output budget must be 256..4096 tokens");
        this.mode = mode;
        this.extractionTimeoutSeconds = extractionTimeoutSeconds;
        this.extractionMaxTokens = extractionMaxTokens;
    }
}
