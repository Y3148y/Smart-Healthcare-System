package com.aihospital.triage.infrastructure.llm;

import com.aihospital.shared.model.Models.DepartmentCandidate;
import com.aihospital.triage.domain.NarrationModel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class StructuredDecisionModelTest {
    private final Set<String> allowed = Set.of("神经内科", "消化内科", "全科医学科");
    private final StructuredDecisionModel model =
            new StructuredDecisionModel("openai-compatible", "test-key", "http://127.0.0.1:9", "test-model", 5);

    @Test
    void fencedJsonWithLeadingTextIsAcceptedWhenInsideWhitelist() {
        String response = "以下是分诊决策：\n```json\n{\"department\":\"消化内科\",\"basis\":\"以反酸和腹痛为主要表现\",\"confidence\":70}\n```";
        var proposal = model.parseAndGuard(response, allowed);
        assertEquals(StructuredDecisionModel.Proposal.ACCEPTED, proposal.status());
        assertTrue(proposal.decision().isPresent());
        assertEquals("消化内科", proposal.decision().get().department());
        assertEquals(70, proposal.decision().get().confidence());
    }

    @Test
    void departmentOutsideRuleWhitelistRejectsWholeProposal() {
        String response = "{\"department\":\"皮肤科\",\"basis\":\"患者提到皮疹\",\"confidence\":60}";
        var proposal = model.parseAndGuard(response, allowed);
        assertEquals(StructuredDecisionModel.Proposal.REJECTED, proposal.status());
        assertTrue(proposal.decision().isEmpty());
    }

    @Test
    void confidenceOutsideGuardedBoundsRejectsWholeProposal() {
        assertEquals(StructuredDecisionModel.Proposal.REJECTED,
                model.parseAndGuard("{\"department\":\"神经内科\",\"basis\":\"头晕为主\",\"confidence\":20}", allowed).status());
        assertEquals(StructuredDecisionModel.Proposal.REJECTED,
                model.parseAndGuard("{\"department\":\"神经内科\",\"basis\":\"头晕为主\",\"confidence\":95}", allowed).status());
        assertEquals(StructuredDecisionModel.Proposal.REJECTED,
                model.parseAndGuard("{\"department\":\"神经内科\",\"basis\":\"头晕为主\",\"confidence\":\"高\"}", allowed).status());
    }

    @Test
    void textualConfidenceIsParsedWhenNumeric() {
        var proposal = model.parseAndGuard(
                "{\"department\":\"全科医学科\",\"basis\":\"症状涉及多个方向\",\"confidence\":\"62\"}", allowed);
        assertEquals(StructuredDecisionModel.Proposal.ACCEPTED, proposal.status());
        assertEquals(62, proposal.decision().get().confidence());
    }

    @Test
    void malformedOrIncompletePayloadRejectsWholeProposal() {
        assertEquals(StructuredDecisionModel.Proposal.REJECTED,
                model.parseAndGuard("抱歉，我无法给出分诊结果。", allowed).status());
        assertEquals(StructuredDecisionModel.Proposal.REJECTED,
                model.parseAndGuard("{\"department\":\"神经内科\"}", allowed).status());
        assertEquals(StructuredDecisionModel.Proposal.REJECTED,
                model.parseAndGuard("{\"department\":\"神经内科\",\"basis\":\"头晕\",", allowed).status());
        assertEquals(StructuredDecisionModel.Proposal.REJECTED, model.parseAndGuard(null, allowed).status());
        assertEquals(StructuredDecisionModel.Proposal.REJECTED, model.parseAndGuard("  ", allowed).status());
    }

    @Test
    void diagnosticOrMedicationBasisRejectsWholeProposal() {
        String response = "{\"department\":\"消化内科\",\"basis\":\"确诊为胃炎，服用奥美拉唑每天两次\",\"confidence\":70}";
        var proposal = model.parseAndGuard(response, allowed);
        assertEquals(StructuredDecisionModel.Proposal.REJECTED, proposal.status());
    }

    @Test
    void emergencyMentionsInBasisAreRejectedBecauseRulesOwnRiskDecisions() {
        String response = "{\"department\":\"神经内科\",\"basis\":\"头晕反复，建议立即急诊评估\",\"confidence\":70}";
        var proposal = model.parseAndGuard(response, allowed);
        assertEquals(StructuredDecisionModel.Proposal.REJECTED, proposal.status());
    }

    @Test
    void whitelistAlwaysContainsRuleCandidatesAndDefaultDepartment() {
        var whitelist = model.whitelist(List.of(
                new DepartmentCandidate("神经内科", "头晕", null),
                new DepartmentCandidate("消化内科", "腹痛", null),
                new DepartmentCandidate("", "空白应被跳过", null)));
        assertTrue(whitelist.contains("神经内科"));
        assertTrue(whitelist.contains("消化内科"));
        assertTrue(whitelist.contains("全科医学科"));
        assertFalse(whitelist.contains(""));
        assertEquals(3, whitelist.size());
    }

    @Test
    void unconfiguredModelSkipsWithoutAnyNetworkCall() {
        var demo = new StructuredDecisionModel("demo", "", "", "", 5);
        assertFalse(demo.enabled());
        var proposal = demo.propose("头晕三天", List.of(new DepartmentCandidate("神经内科", "头晕", null)),
                List.of(new NarrationModel.Turn("USER", "头晕三天")));
        assertEquals(StructuredDecisionModel.Proposal.SKIPPED, proposal.status());
        assertTrue(proposal.decision().isEmpty());
    }

    @Test
    void unreachableProviderReturnsErrorInsteadOfThrowing() {
        var proposal = model.propose("头晕三天，同时腹痛并反酸",
                List.of(new DepartmentCandidate("神经内科", "头晕", null),
                        new DepartmentCandidate("消化内科", "腹痛", null)), List.of());
        assertEquals(StructuredDecisionModel.Proposal.ERROR, proposal.status());
        assertTrue(proposal.decision().isEmpty());
    }
}
