package com.aihospital.triage.infrastructure.demo;

import com.aihospital.knowledge.domain.KnowledgeCatalog.Retrieval;
import com.aihospital.shared.model.Models.Evidence;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.assertEquals;

class RetrievalOutcomeClassificationTest {
    @Test void distinguishesAnActualNoMatchFromRequiredDependencyFailure() {
        assertEquals("NO_MATCH", RuleBasedTriageEngine.retrievalStatus(
                new Retrieval(List.of(), false, "没有命中相关片段"), true));
        assertEquals("DEPENDENCY_UNAVAILABLE", RuleBasedTriageEngine.retrievalStatus(
                new Retrieval(List.of(), false, "DEPENDENCY_BLOCKED_UNRERANKED; candidates=2"), true));
        assertEquals("DEPENDENCY_UNAVAILABLE", RuleBasedTriageEngine.retrievalStatus(
                new Retrieval(List.of(), false, "本地执行异常"), false));
        assertEquals("MATCHED", RuleBasedTriageEngine.retrievalStatus(
                new Retrieval(List.of(new Evidence("资料", "source", "excerpt", 0.8)), true, "matched"), true));
    }
}
