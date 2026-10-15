package com.aihospital.triage.domain;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Observed engineering limitations, NOT desired clinical labels or safety acceptance.
 * When event/intent coverage improves, update this baseline and the public boundary table together.
 */
class SafetyCoverageBoundaryTest {
    private final TriageSafetyPolicy policy = new TriageSafetyPolicy();

    @Test void metaphorWithoutListedSymptomTermsIsCurrentlyUnrecognised() {
        var observed = policy.assess("胸口像被大象踩了一脚，还冒冷汗");
        assertTrue(observed.signals().isEmpty(), "Coverage changed: update the limitation record, not the rule to preserve this gap");
    }

    @Test void genericChestRuleCurrentlyDoesNotSeparateThirdPerson() {
        assertTrue(policy.assess("我朋友胸痛").signals().stream()
                .anyMatch(signal -> "ER-CIRCULATION-001".equals(signal.ruleCode())));
    }

    @Test void genericChestRuleCurrentlyDoesNotSeparateHypotheticalQuestion() {
        assertTrue(policy.assess("如果出现胸痛怎么办").signals().stream()
                .anyMatch(signal -> "ER-CIRCULATION-001".equals(signal.ruleCode())));
    }

    @Test void historicalPrefixWorksWithinClauseButDoesNotCarryAcrossClauseBoundary() {
        assertTrue(policy.assess("去年胸痛").signals().isEmpty());
        assertTrue(policy.assess("去年发生过这些症状，胸痛").signals().stream()
                .anyMatch(signal -> "ER-CIRCULATION-001".equals(signal.ruleCode())));
    }
}
