package com.aihospital.knowledge;

import com.aihospital.knowledge.infrastructure.qdrant.HybridKnowledgeCatalog;
import com.aihospital.tools.application.HospitalToolExecutor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.util.ReflectionTestUtils;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest
class RetrievalThresholdConfigurationTest {
    @Autowired HospitalToolExecutor toolExecutor;
    @Autowired HybridKnowledgeCatalog catalog;

    @Test void defaultThresholdsComeFromApplicationConfiguration() {
        assertEquals(0.28, (double) ReflectionTestUtils.getField(toolExecutor, "retrievalMinScore"), 0.0001);
        assertEquals(0.28, (double) ReflectionTestUtils.getField(catalog, "lexicalMinScore"), 0.0001);
        assertEquals(0.45, (double) ReflectionTestUtils.getField(catalog, "semanticMinScore"), 0.0001);
    }
}
