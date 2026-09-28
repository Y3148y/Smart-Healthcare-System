package com.aihospital.triage;

import com.aihospital.catalog.infrastructure.demo.DemoDoctorDirectory;
import com.aihospital.knowledge.infrastructure.demo.InMemoryKnowledgeCatalog;
import com.aihospital.observation.infrastructure.demo.InMemoryCallLogStore;
import com.aihospital.triage.domain.NarrationModel;
import com.aihospital.triage.domain.TriageSafetyPolicy;
import com.aihospital.triage.infrastructure.demo.RuleBasedTriageEngine;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RuleBasedTriageEngineTest {
    private final TriageSafetyPolicy safety = new TriageSafetyPolicy();

    private RuleBasedTriageEngine engine(NarrationModel narration) {
        return new RuleBasedTriageEngine(safety, new DemoDoctorDirectory(),
                new InMemoryKnowledgeCatalog(), narration, new InMemoryCallLogStore());
    }

    @Test
    void affirmedRedFlagsAreUrgent() {
        assertTrue(safety.requiresImmediateCare("突然剧烈胸痛，呼吸困难，出冷汗"));
        assertTrue(safety.requiresImmediateCare("喘不上气并且意识不清"));
    }

    @Test
    void negatedRedFlagsAreNotUrgent() {
        assertFalse(safety.requiresImmediateCare("咳嗽三天，没有胸痛和呼吸困难"));
        assertFalse(safety.requiresImmediateCare("否认胸痛，无晕厥"));
    }

    @Test
    void affirmedFlagAfterAdversativeWordIsUrgent() {
        assertTrue(safety.requiresImmediateCare("没有咳嗽，但是现在呼吸困难"));
    }

    @Test
    void expandedEmergencySignalsAreUrgent() {
        assertTrue(safety.requiresImmediateCare("脸肿而且吞咽不了"));
        assertTrue(safety.requiresImmediateCare("突然抽搐后意识不清"));
        assertTrue(safety.requiresImmediateCare("呕血和黑便"));
        assertFalse(safety.requiresImmediateCare("没有胸痛，也没有呼吸困难"));
    }

    @Test
    void bundledProfessionalKnowledgeIsSearchableAndTraceable() {
        NarrationModel narration = mock(NarrationModel.class);
        when(narration.explain(anyString(), anyString(), anyString(), anyString(), anyString()))
            .thenAnswer(invocation -> new NarrationModel.Answer(invocation.getArgument(4), "DEMO", ""));
        InMemoryKnowledgeCatalog knowledge = new InMemoryKnowledgeCatalog();
        RuleBasedTriageEngine service = new RuleBasedTriageEngine(safety, new DemoDoctorDirectory(), knowledge,
                narration, new InMemoryCallLogStore());

        var respiratory = knowledge.search("咳嗽胸闷挂什么科");
        assertFalse(respiratory.isEmpty());
        assertTrue(respiratory.get(0).source().startsWith("https://"));
        assertTrue(respiratory.stream().anyMatch(item -> item.title().contains("呼吸") && item.source().startsWith("https://")));

        var triage = service.triage("test-rag", "咳嗽三天，没有胸痛和呼吸困难", "张三");
        assertNotNull(triage.doctor());
        assertTrue(triage.evidence().get(0).source().startsWith("https://"));
        assertTrue(triage.evidence().stream().anyMatch(item -> item.source().startsWith("https://")));
    }

    @Test
    void dizzinessGetsRelevantAnswerInsteadOfRespiratoryFallback() {
        NarrationModel narration = mock(NarrationModel.class);
        when(narration.explain(anyString(), anyString(), anyString(), anyString(), anyString()))
            .thenAnswer(invocation -> new NarrationModel.Answer(invocation.getArgument(4), "FALLBACK", "configured-model"));
        RuleBasedTriageEngine service = engine(narration);
        var result = service.triage("dizziness-case", "我最近头晕想吐", "张三");
        assertTrue(result.department().equals("神经内科"));
        assertTrue(result.summary().contains("头晕") && result.summary().contains("突然"));
        assertTrue(result.evidence().stream().anyMatch(item -> item.title().contains("头晕")));
        assertTrue(result.modelStatus().equals("FALLBACK"));
    }

    @Test
    void symptomsAcrossDepartmentsRetainCandidatesInsteadOfArbitraryFirstMatch() {
        NarrationModel narration = mock(NarrationModel.class);
        when(narration.explain(anyString(), anyString(), anyString(), anyString(), anyString()))
            .thenAnswer(invocation -> new NarrationModel.Answer(invocation.getArgument(4), "DEMO", ""));
        RuleBasedTriageEngine service = engine(narration);
        var result = service.triage("mixed-case", "头晕三天，同时腹痛并反酸", "张三");
        assertTrue(result.department().equals("全科医学科"));
        assertTrue(result.riskLevel().equals("多科室参考"));
        assertTrue(result.candidates().stream().anyMatch(candidate -> candidate.department().equals("神经内科")));
        assertTrue(result.candidates().stream().anyMatch(candidate -> candidate.department().equals("消化内科")));
    }
}
