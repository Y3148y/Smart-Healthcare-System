package com.aihospital.triage;

import com.aihospital.triage.domain.TriageSafetyPolicy;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * D11 ruling 3. Extends the adjacent-negation guard from step 2 to all of step 1.
 *
 * <p>Ruling 3 requires per-rule regression before enabling it, positives for every rule and
 * negatives for 没有/无明显/否认 + symptom. It also requires locking the two affirmed
 * emergencies whose own wording contains 无, because a guard that searched inside the matched
 * token instead of before its start would kill them.
 */
class Step1NegationGuardTest {

    /** Every static rule code, with an affirmed phrasing that must still fire it. */
    private static final Map<String, String> POSITIVES = Map.ofEntries(
            Map.entry("ER-AIRWAY-001", "咽喉肿得厉害，吞咽不了"),
            Map.entry("ER-FACE-SPREAD-001", "脸肿并且张口受限"),
            Map.entry("ER-BREATHING-001", "我喘不上气"),
            Map.entry("ER-CIRCULATION-001", "我持续胸痛"),
            Map.entry("ER-NEURO-001", "我单侧肢体无力"),
            Map.entry("ER-BLEEDING-001", "呕血"),
            Map.entry("ER-TRAUMA-001", "骨头外露"),
            Map.entry("ER-POISON-001", "我不想活了"),
            Map.entry("ER-PREGNANCY-001", "我怀孕八周突然剧烈腹痛"),
            Map.entry("UR-TRAUMA-001", "我摔断了"),
            Map.entry("UR-PAIN-001", "我腹痛难忍"),
            Map.entry("UR-FEVER-001", "我持续高热"),
            Map.entry("UR-FACE-SWELLING-001", "我脸有点肿"),
            Map.entry("UR-PREGNANCY-001", "我可能怀孕"));

    @Test
    void everyRuleStillFiresOnItsAffirmedPhrasing() {
        var policy = new TriageSafetyPolicy();
        POSITIVES.forEach((code, phrasing) -> {
            var assessment = policy.assess(phrasing);
            assertTrue(assessment.signals().stream().anyMatch(s -> code.equals(s.ruleCode())),
                    code + " 必须仍然命中，措辞: " + phrasing
                            + " 实际: " + assessment.signals().stream().map(s -> s.ruleCode()).toList());
        });
    }

    /**
     * The two affirmed emergencies whose wording itself contains 无. 无法吞咽 matches with its
     * start on the 无, so there is no preceding region for the guard to search; 单侧肢体无力
     * starts on 单. If either ever regresses, the guard is searching inside the matched token.
     */
    @Test
    void affirmedEmergenciesContainingWuAreNotKilled() {
        var policy = new TriageSafetyPolicy();
        for (String phrasing : List.of("我无法吞咽", "咽口水困难，无法吞咽",
                "我单侧肢体无力", "左边单侧肢体无力", "说话含糊，说话不清")) {
            var assessment = policy.assess(phrasing);
            assertTrue(assessment.stopRoutineFlow(),
                    phrasing + " 是肯定急症，不得被紧邻否定保护误杀，实际: " + assessment.acuity()
                            + " " + assessment.signals().stream().map(s -> s.ruleCode()).toList());
        }
    }

    @Test
    void adjacentNegationSuppressesEachRuleInStepOne() {
        var policy = new TriageSafetyPolicy();
        for (String phrasing : List.of(
                "我没有胸痛", "无明显胸痛", "否认胸痛",
                "我没有意识不清", "无明显意识障碍", "否认昏迷",
                "我没有呼吸困难", "无明显喘不上气",
                "没有骨头外露", "否认呕血",
                "我没有脸肿", "无明显面部肿胀",
                "我没有持续高热", "否认体温39度",
                "我没有腹痛难忍", "否认剧烈腹痛")) {
            var assessment = policy.assess(phrasing);
            assertFalse(assessment.stopRoutineFlow(),
                    phrasing + " 被否定，不应触发任何急症，实际: " + assessment.acuity()
                            + " " + assessment.signals().stream().map(s -> s.ruleCode()).toList());
        }
    }

    /** Negation must not suppress the affirmed part of a mixed sentence. */
    @Test
    void negationOfOneFindingDoesNotSuppressAnother() {
        var policy = new TriageSafetyPolicy();
        var assessment = policy.assess("我没有胸痛，但是单侧肢体无力");
        assertTrue(assessment.signals().stream().anyMatch(s -> "ER-NEURO-001".equals(s.ruleCode())),
                "否定胸痛不得连带压制神经急症，实际: "
                        + assessment.signals().stream().map(s -> s.ruleCode()).toList());
    }
}
