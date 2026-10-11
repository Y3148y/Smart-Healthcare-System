package com.aihospital.triage;

import com.aihospital.triage.application.AgentWorkflowSettings;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import static org.junit.jupiter.api.Assertions.*;

class AgentWorkflowSettingsTest {
    @Test void defaultsOffAndAllowsBoundedShadowSettings() {
        new ApplicationContextRunner().withBean(AgentWorkflowSettings.class).run(context -> {
            assertNull(context.getStartupFailure());
            var settings = context.getBean(AgentWorkflowSettings.class);
            assertEquals(AgentWorkflowSettings.Mode.OFF, settings.mode());
            assertEquals(8, settings.extractionTimeoutSeconds());
            assertEquals(2048, settings.extractionMaxTokens());
        });
        new ApplicationContextRunner().withBean(AgentWorkflowSettings.class)
                .withPropertyValues("ai.agent.mode=SHADOW", "ai.agent.extraction-timeout-seconds=3")
                .run(context -> assertEquals(AgentWorkflowSettings.Mode.SHADOW, context.getBean(AgentWorkflowSettings.class).mode()));
    }
    @Test void rejectsUnknownModeOrUnboundedExtraction() {
        assertEquals(AgentWorkflowSettings.Mode.ENABLED,new AgentWorkflowSettings(AgentWorkflowSettings.Mode.ENABLED,8,2048).mode());
        for (String property : new String[]{"ai.agent.mode=UNKNOWN",
                "ai.agent.extraction-timeout-seconds=0", "ai.agent.extraction-max-tokens=100000",
                "ai.agent.max-steps=4", "ai.agent.max-tool-calls=5", "ai.agent.planning-timeout-seconds=9",
                "ai.agent.total-planning-seconds=26"}) {
            new ApplicationContextRunner().withBean(AgentWorkflowSettings.class).withPropertyValues(property)
                    .run(context -> assertNotNull(context.getStartupFailure()));
        }
    }
}
