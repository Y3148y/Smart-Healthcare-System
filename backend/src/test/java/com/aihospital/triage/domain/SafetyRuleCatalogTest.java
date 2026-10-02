package com.aihospital.triage.domain;


import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A2 migration guard.
 *
 * <p>This does not re-verify that the rules are clinically correct — nothing here can. It pins
 * three things that would otherwise rot silently: the vocabulary that enumerates symptoms, the
 * integrity of the citations, and the rule-code naming convention that already caused one real
 * incident (a regex that assumed two segments silently skipped the two three-segment codes, so a
 * rule count came out 11 instead of 13).
 *
 * <p>Behavioural coverage lives in {@code Step1NegationGuardTest} and the D10/D11 matrices; this
 * test deliberately does not duplicate it.
 */
class SafetyRuleCatalogTest {

    /** The enumeration under review. Any change to these shows up here as a diff. */
    private static final Map<String, String> PINNED_VOCABULARY = Map.of(
            "faceSite", "脸|面|脸颊|面部|牙龈|智齿",
            "airway", "无法吞咽|吞咽不了|呼吸困难|喘不上气|喘不过气|说不出话|张口受限|张不开嘴",
            "faceFeatures", "(?:脸|面部|脸颊)",
            "pregnancyState", "怀孕|孕期|(?<!不)可能怀孕",
            "pregnancyDanger", "大量出血|剧烈腹痛",
            "postpartum", "产后大出血",
            "unclearBleeding", "明显出血");

    @Test
    void vocabularyIsPinnedToTheReviewedValues() {
        var policy = new TriageSafetyPolicy();
        // The short literal entries are pinned exactly.
        for (Map.Entry<String, String> entry : PINNED_VOCABULARY.entrySet())
            assertTrue(contains(policy, entry.getKey(), entry.getValue()),
                    entry.getKey() + " 词表已变更，原值: " + entry.getValue());
        // The two composed entries are pinned by constituent, not by their expanded regex text:
        // the expansion embeds the negation token list and clause character class, which is
        // parser mechanism rather than clinical vocabulary.
        assertTrue(policy.vocabularyForTesting("faceSwelling").contains("智齿") && contains(policy, "faceSwelling", "肿"),
                "faceSwelling 必须由 faceSite 与「肿」构成");
        assertTrue(policy.vocabularyForTesting("airwayGrouped").startsWith("(?:")
                        && policy.vocabularyForTesting("airwayGrouped").endsWith(")"),
                "airwayGrouped 必须是 airway 的分组形式");
    }

    private boolean contains(TriageSafetyPolicy policy, String name, String expected) {
        String actual = policy.vocabularyForTesting(name);
        return actual != null && (actual.equals(expected) || actual.contains(expected));
    }

    @Test
    void everyRuleCompilesAndMatchesItsOwnVocabulary() {
        var policy = new TriageSafetyPolicy();
        assertEquals(14, policy.ruleSpecsForTesting().size(), "规则条数变化必须同步文档与本断言");
        for (var spec : policy.ruleSpecsForTesting()) {
            Pattern compiled = Pattern.compile(spec.expression());
            assertFalse(spec.reason().isBlank(), spec.code() + " 缺少 reason");
            assertFalse(spec.category().isBlank(), spec.code() + " 缺少 category");
            assertTrue("EMERGENCY".equals(spec.acuity()) || "URGENT".equals(spec.acuity()),
                    spec.code() + " acuity 非法: " + spec.acuity());
            assertFalse(compiled.pattern().contains("{site") || compiled.pattern().contains("{gap"),
                    spec.code() + " 存在未渲染的模板标记");
        }
    }

    /**
     * Rules must be either cited or explicitly marked as a citation gap. A rule with neither is
     * the failure mode this whole migration exists to surface: a rule that looks covered but has
     * no evidence behind it.
     */
    @Test
    void everyRuleIsEitherCitedOrMarkedAsCitationGap() {
        for (var entry : TriageSafetyPolicy.catalogCitationsForTesting())
            assertTrue(entry.cited() || entry.citationGap(),
                    entry.code() + " 既无出处也无 citationGap 标记——这正是本次迁移要暴露的问题");
    }

    @Test
    void citationGapIsReportedNotSilentlyAbsent() {
        long gaps = TriageSafetyPolicy.catalogCitationsForTesting().stream()
                .filter(entry -> entry.citationGap()).count();
        assertTrue(gaps >= 1, "登记在案的出处缺口应当仍然可见，而不是被悄悄抹平");
    }

    /**
     * Two-segment convention is ER-XXX-001. Two codes predate the convention and are listed here
     * explicitly rather than left to fail: when they are renamed, this test says so.
     */
    @Test
    void ruleCodesFollowTheTwoSegmentConventionWithTwoKnownExceptions() {
        Pattern twoSegment = Pattern.compile("^[ERU]{2}-[A-Z]+-\\d{3}$");
        Set<String> knownExceptions = Set.of("ER-FACE-SPREAD-001", "UR-FACE-SWELLING-001");
        List<String> offending = TriageSafetyPolicy.ruleSpecsForTesting().stream()
                .map(spec -> spec.code())
                .filter(code -> !twoSegment.matcher(code).matches())
                .filter(code -> !knownExceptions.contains(code))
                .toList();
        assertTrue(offending.isEmpty(),
                "新增了不符合两段式命名的规则码: " + offending
                        + "。三段式曾导致按两段式正则统计时静默漏数（得出 11 而非 13）。"
                        + "若本次是修正既有的 ER-FACE-SPREAD-001 / UR-FACE-SWELLING-001，请同步删除白名单。");
    }

    /**
     * The citation picture is pinned by computation, not by a hand-written number in the data
     * file. A hand-written count silently rots the moment a citation is added or removed, which
     * is exactly the failure this migration is meant to prevent. Adding a citation must show up
     * here as a failure, and the fix must be a deliberate count update.
     */
    @Test
    void citationsArePinned() {
        var citations = TriageSafetyPolicy.catalogCitationsForTesting();
        long cited = citations.stream().filter(TriageSafetyPolicy.CitationStatus::cited).count();
        long gaps = citations.stream().filter(TriageSafetyPolicy.CitationStatus::citationGap).count();

        assertEquals(15, citations.size(), "规则条目数变化（14 条静态 + 1 条派生）必须同步文档与本断言");
        assertEquals(10, cited, "有出处的条目数变化，请同步更新本断言并复核该出处是否真的支撑该规则");
        assertEquals(7, gaps, "citationGap 条目数变化。这些是登记在案的缺口——减少是改进，增加需要说明原因。");
    }

    @Test
    void combinationsOnlyReferenceDeclaredRuleCodes() {
        var policy = new TriageSafetyPolicy();
        Set<String> declared = policy.ruleSpecsForTesting().stream()
                .map(spec -> spec.code())
                .collect(java.util.stream.Collectors.toSet());
        declared.add("ER-ALLERGY-001");
        declared.add("UR-BLEEDING-UNCLEAR-001");
        for (var combination : policy.combinationSpecsForTesting())
            assertTrue(declared.contains(combination.code()),
                    "组合引用了未声明的规则码: " + combination.code());
        assertEquals(4, policy.combinationSpecsForTesting().size(), "组合条数变化必须同步文档");
    }
}
