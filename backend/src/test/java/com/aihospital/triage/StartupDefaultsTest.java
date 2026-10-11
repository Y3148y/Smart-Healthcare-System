package com.aihospital.triage;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.FileSystemResource;
import static org.junit.jupiter.api.Assertions.*;

class StartupDefaultsTest {
    @Test void dailyConfigurationDoesNotRequireRepeatedModeOrPortFlags() throws Exception {
        var properties=new YamlPropertySourceLoader().load("main",new FileSystemResource("src/main/resources/application.yml")).get(0);
        assertEquals("${SERVER_PORT:8081}",properties.getProperty("server.port"));
        assertEquals("${AI_MODE:openai-compatible}",properties.getProperty("ai.mode"));
        assertEquals("${AI_AGENT_MODE:ENABLED}",properties.getProperty("ai.agent.mode"));
        assertEquals("${AI_RERANK_ENABLED:false}",properties.getProperty("ai.rerank.enabled"));
        assertEquals("${AI_RETRIEVAL_REQUIRE_RERANK:false}",properties.getProperty("ai.retrieval.require-rerank"));
        assertEquals("${AI_QDRANT_COLLECTION:ai_hospital_knowledge_v2}",properties.getProperty("ai.qdrant-collection"));
    }
}
