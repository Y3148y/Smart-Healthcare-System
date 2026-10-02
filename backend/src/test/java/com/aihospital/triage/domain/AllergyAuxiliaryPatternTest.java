package com.aihospital.triage.domain;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AllergyAuxiliaryPatternTest {
    private final TriageSafetyPolicy policy = new TriageSafetyPolicy();

    private boolean allergy(String input) {
        return policy.assess(input).signals().stream()
                .anyMatch(signal -> "ER-ALLERGY-001".equals(signal.ruleCode()));
    }

    @Test
    void currentFoodExposureAndGeneralizedRedSpotsReachTheConservativeGate() {
        assertTrue(allergy("吃了海鲜，全身起了很多红点"));
        assertTrue(allergy("吃了海鲜全身起了很多红点"));
    }

    @Test
    void negatedHistoricalAndLocalRashesDoNotReachTheAllergyGate() {
        assertFalse(allergy("吃了海鲜，全身没有红点"));
        assertFalse(allergy("以前吃了海鲜，全身起了很多红点"));
        assertFalse(allergy("吃了海鲜，胳膊上一小块红点"));
    }

    @Test
    void airwaySignalDoesNotRequireRashOrFoodExposure() {
        var assessment = policy.assess("嘴唇肿了，无法吞咽");
        assertEquals("EMERGENCY", assessment.acuity());
        assertTrue(assessment.signals().stream()
                .anyMatch(signal -> "ER-AIRWAY-001".equals(signal.ruleCode())));
        assertFalse(allergy("嘴唇肿了，无法吞咽"));
    }
}
