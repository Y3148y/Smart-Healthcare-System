package com.aihospital.triage.infrastructure.llm;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class NoEvidenceFallbackTest {
    @Test void distinguishesMissingEvidenceFromRetrievalDependencyFailure() {
        String noMatch = OptionalNarrationModel.noEvidenceMessage("NO_MATCH");
        String dependencyUnavailable = OptionalNarrationModel.noEvidenceMessage("DEPENDENCY_UNAVAILABLE");
        assertTrue(noMatch.contains("没有检索到足以回答"));
        assertFalse(noMatch.contains("暂不可用"));
        assertTrue(dependencyUnavailable.contains("暂不可用"));
        assertTrue(dependencyUnavailable.contains("无法确认是否有相关资料"));
        assertFalse(dependencyUnavailable.contains("没有检索到"));
    }
}
