package com.aihospital.triage;

import com.aihospital.catalog.infrastructure.demo.DemoDoctorDirectory;
import com.aihospital.knowledge.infrastructure.demo.InMemoryKnowledgeCatalog;
import com.aihospital.observation.infrastructure.demo.InMemoryCallLogStore;
import com.aihospital.triage.domain.NarrationModel;
import com.aihospital.triage.domain.TriageSafetyPolicy;
import com.aihospital.triage.infrastructure.demo.RuleBasedTriageEngine;
import com.aihospital.tools.application.HospitalToolExecutor;
import com.aihospital.tools.infrastructure.demo.InMemoryToolRegistry;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class RuleBasedTriageEngineTest {
    private final TriageSafetyPolicy safety = new TriageSafetyPolicy();

    private RuleBasedTriageEngine engine(NarrationModel narration) {
        var doctors = new DemoDoctorDirectory();
        var knowledge = new InMemoryKnowledgeCatalog();
        return new RuleBasedTriageEngine(safety, doctors, knowledge, narration, new InMemoryCallLogStore(),
                new HospitalToolExecutor(new InMemoryToolRegistry(), doctors, knowledge, safety, new InMemoryCallLogStore()));
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
        assertTrue(safety.requiresImmediateCare("我胸痛，喘不过气"));
        assertTrue(safety.requiresImmediateCare("胸痛，头晕"));
        assertTrue(safety.requiresImmediateCare("突然心口疼"));
        assertFalse(safety.requiresImmediateCare("没有胸痛，也没有呼吸困难"));
    }

    @Test
    void safetyMatrixPreservesCriticalSignalsAndSeparatesNegationAndHistory() {
        for (String symptom : java.util.List.of("胸痛，头晕", "突然胸口疼", "我爸胸痛并且冒冷汗",
                "喘不过气，嘴唇发紫", "突然说话不清", "骨头外露", "服药过量", "伤口大量出血")) {
            var assessment = safety.assess(symptom);
            assertTrue(assessment.stopRoutineFlow(), symptom);
            assertFalse(assessment.signals().isEmpty(), symptom);
            assertTrue(assessment.signals().get(0).ruleCode().startsWith("ER-"), symptom);
        }
        for (String symptom : java.util.List.of("咳嗽三天，没有胸痛和呼吸困难", "以前胸痛，现在只是咳嗽",
                "否认晕厥，也没有意识障碍"))
            assertFalse(safety.assess(symptom).stopRoutineFlow(), symptom);
        assertTrue(safety.assess("没有胸痛，但是现在喘不过气").stopRoutineFlow());
        assertTrue(safety.assess("头晕无力还胸痛").stopRoutineFlow());
        assertTrue(safety.assess("以前胸痛现在又胸痛").stopRoutineFlow());
        assertTrue(safety.assess("手摔断了").humanReviewRecommended());
        assertFalse(safety.assess("手摔断了").stopRoutineFlow());
    }

    @Test
    void unreviewedKnowledgeCannotInfluencePatientRetrieval() {
        InMemoryKnowledgeCatalog knowledge = new InMemoryKnowledgeCatalog();
        var uploaded = knowledge.addDocument("伪造医学资料", "咳嗽应该马上吃药。这段文本不应进入患者回答。");
        assertTrue(uploaded.status().equals("PENDING_REVIEW"));
        assertFalse(knowledge.search("咳嗽").stream().anyMatch(e -> e.title().equals("伪造医学资料")));
        assertFalse(knowledge.retrieve("完全无关的火星建筑材料", 3, 0.35).grounded());
        assertTrue(knowledge.retrieve("流鼻涕", 3, 0.28).evidence().stream()
                .anyMatch(item -> item.title().contains("流鼻涕")));
    }

    @Test
    void bundledProfessionalKnowledgeIsSearchableAndTraceable() {
        NarrationModel narration = mock(NarrationModel.class);
        when(narration.explain(anyString(), anyString(), anyString(), anyString(), anyString(), anyList()))
            .thenAnswer(invocation -> new NarrationModel.Answer(invocation.getArgument(4), "DEMO", ""));
        InMemoryKnowledgeCatalog knowledge = new InMemoryKnowledgeCatalog();
        RuleBasedTriageEngine service = new RuleBasedTriageEngine(safety, new DemoDoctorDirectory(), knowledge,
                narration, new InMemoryCallLogStore(), new HospitalToolExecutor(new InMemoryToolRegistry(),
                new DemoDoctorDirectory(), knowledge, safety, new InMemoryCallLogStore()));

        var respiratory = knowledge.search("咳嗽胸闷挂什么科");
        assertFalse(respiratory.isEmpty());
        assertTrue(respiratory.get(0).source().startsWith("https://"));
        assertTrue(respiratory.stream().anyMatch(item -> item.title().contains("呼吸") && item.source().startsWith("https://")));

        var triage = service.triage("test-rag", "咳嗽三天，没有胸痛和呼吸困难", "张三", java.util.List.of());
        assertNotNull(triage.doctor());
        assertTrue(triage.evidence().get(0).source().startsWith("https://"));
        assertTrue(triage.evidence().stream().anyMatch(item -> item.source().startsWith("https://")));
    }

    @Test
    void dizzinessGetsRelevantAnswerInsteadOfRespiratoryFallback() {
        NarrationModel narration = mock(NarrationModel.class);
        when(narration.explain(anyString(), anyString(), anyString(), anyString(), anyString(), anyList()))
            .thenAnswer(invocation -> new NarrationModel.Answer(invocation.getArgument(4), "FALLBACK", "configured-model"));
        RuleBasedTriageEngine service = engine(narration);
        var result = service.triage("dizziness-case", "我最近头晕想吐", "张三", java.util.List.of());
        assertTrue(result.department().equals("神经内科"));
        assertTrue(result.summary().contains("头晕") && result.summary().contains("突然"));
        assertTrue(result.evidence().stream().anyMatch(item -> item.title().contains("头晕")));
        assertTrue(result.modelStatus().equals("FALLBACK"));
    }

    @Test
    void symptomsAcrossDepartmentsRetainCandidatesInsteadOfArbitraryFirstMatch() {
        NarrationModel narration = mock(NarrationModel.class);
        when(narration.explain(anyString(), anyString(), anyString(), anyString(), anyString(), anyList()))
            .thenAnswer(invocation -> new NarrationModel.Answer(invocation.getArgument(4), "DEMO", ""));
        RuleBasedTriageEngine service = engine(narration);
        var result = service.triage("mixed-case", "头晕三天，同时腹痛并反酸", "张三", java.util.List.of());
        assertTrue(result.department().equals("全科医学科"));
        assertTrue(result.riskLevel().equals("多科室参考"));
        assertTrue(result.candidates().stream().anyMatch(candidate -> candidate.department().equals("神经内科")));
        assertTrue(result.candidates().stream().anyMatch(candidate -> candidate.department().equals("消化内科")));
    }
}
