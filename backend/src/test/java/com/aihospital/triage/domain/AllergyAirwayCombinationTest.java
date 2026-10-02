package com.aihospital.triage.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AllergyAirwayCombinationTest {
    private final TriageSafetyPolicy policy = new TriageSafetyPolicy();

    @Test
    void standaloneSwallowingFailureAlreadyTriggersTheAirwayRuleAcrossClauses() {
        var result = policy.assess("嘴唇肿了，无法吞咽");
        assertEquals("EMERGENCY", result.acuity());
        assertTrue(result.signals().stream().anyMatch(s -> "ER-AIRWAY-001".equals(s.ruleCode())));
    }

    @Test
    void foodLipSwellingAndBreathingChangeReachTheConservativeGateWithoutRash() {
        var result = policy.assess("吃了海鲜，嘴唇肿了，呼吸有点急");
        assertEquals("EMERGENCY", result.acuity());
        assertTrue(result.signals().stream().anyMatch(s -> "ER-ALLERGY-001".equals(s.ruleCode())));
    }

    @Test
    void negatedHistoricalAndThirdPersonCombinationsDoNotReachTheDerivedGate() {
        for (String input : new String[]{
                "吃了海鲜，嘴唇没有肿，呼吸有点急",
                "吃了海鲜，嘴唇肿了，没有呼吸有点急",
                "以前吃了海鲜，嘴唇肿了，呼吸有点急",
                "我朋友吃了海鲜，嘴唇肿了，呼吸有点急"}) {
            assertFalse(policy.assess(input).signals().stream()
                    .anyMatch(s -> "ER-ALLERGY-001".equals(s.ruleCode())), input);
        }
        assertEquals("ROUTINE", policy.assess("呼吸有点急").acuity());
    }
}
