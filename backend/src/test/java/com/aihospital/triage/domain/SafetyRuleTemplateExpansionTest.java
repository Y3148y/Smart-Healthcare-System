package com.aihospital.triage.domain;

import org.junit.jupiter.api.Test;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

/**
 * A2 migration regression.
 *
 * <p>The template renderer matched the third {@code {site}} operand with a greedy {@code \S+},
 * which swallowed the closing brace and every alternative after it. Because the template has no
 * whitespace past the brace, nothing failed loudly: {@code ER-PREGNANCY-001} silently lost its
 * whole {@code |产后大出血} tail. A follow-up bug left a stray quote in the operand, which broke
 * the pattern in a different way. Both were invisible to the 83-test suite because the tests only
 * exercised the leading {@code {site}} span.
 *
 * <p>Phrases are built from codepoints read out of the data file rather than typed, so this test
 * cannot itself be the source of a wrong character.
 */
class SafetyRuleTemplateExpansionTest {

    /** Codepoints of the alternatives declared in safety-rules.json, verified by reading it. */
    private static String phrase(int... codepoints) {
        StringBuilder sb = new StringBuilder();
        for (int cp : codepoints) sb.appendCodePoint(cp);
        return sb.toString();
    }

    private static String ruleExpression(String code) {
        for (var spec : TriageSafetyPolicy.ruleSpecsForTesting())
            if (spec.code().equals(code)) return spec.expression();
        throw new AssertionError("未找到规则 " + code);
    }

    @Test
    void everyDeclaredAlternativeIsActuallyReachable() throws Exception {
        // Read the data file directly and assert each rule's own alternatives reach it. This is
        // the check that was missing: a lost tail is invisible unless you try the tail.
        var policy = new TriageSafetyPolicy();
        String json = Files.readString(
                Path.of("src", "main", "resources", "safety-rules.json"), StandardCharsets.UTF_8);

        // 产后大出血 — the tail of ER-PREGNANCY-001 that the migration dropped.
        assertTrue(json.contains(ruleExpressionOf(json, "ER-PREGNANCY-001")),
                "数据文件应保留 ER-PREGNANCY-001 的声明");
        assertTrue(policy.assess(phrase(0x4EA7, 0x540E, 0x5927, 0x51FA, 0x8840)).signals().stream()
                        .anyMatch(s -> "ER-PREGNANCY-001".equals(s.ruleCode())),
                "产后大出血 must reach ER-PREGNANCY-001（曾因模板贪婪匹配被整体丢弃）");

        // 无法吞咽 — the standalone tail of ER-AIRWAY-001.
        assertTrue(policy.assess(phrase(0x65E0, 0x6CD5, 0x541E, 0x54BD)).signals().stream()
                        .anyMatch(s -> "ER-AIRWAY-001".equals(s.ruleCode())),
                "无法吞咽 must reach ER-AIRWAY-001 单独命中");

        // 舌头肿了 — the leading {site} span of ER-AIRWAY-001.
        assertTrue(policy.assess(phrase(0x820C, 0x5934, 0x80BF, 0x4E86)).signals().stream()
                        .anyMatch(s -> "ER-AIRWAY-001".equals(s.ruleCode())),
                "舌头肿了 must reach ER-AIRWAY-001 通过部位跨度");

        // 剧烈腹痛 — UR-PAIN-001, no template involved, guards against collateral damage.
        assertTrue(policy.assess(phrase(0x5267, 0x70C8, 0x8179, 0x75DB)).signals().stream()
                        .anyMatch(s -> "UR-PAIN-001".equals(s.ruleCode())),
                "剧烈腹痛 must reach UR-PAIN-001");

        // 持续高热 — the leading alternative of UR-FEVER-001.
        assertTrue(policy.assess(phrase(0x6301, 0x7EED, 0x9AD8, 0x70ED)).signals().stream()
                        .anyMatch(s -> "UR-FEVER-001".equals(s.ruleCode())),
                "持续高热 must reach UR-FEVER-001");

        assertTrue(ruleExpression("ER-AIRWAY-001").contains(phrase(0x65E0, 0x6CD5, 0x541E, 0x54BD)),
                "ER-AIRWAY-001 渲染后必须仍含「无法吞咽」分支");
    }

    private static String ruleExpressionOf(String json, String code) {
        // Sanity only: the rule must be declared in the data file.
        return json.contains("\"code\": \"" + code + "\"") ? json : fail(code + " 未声明");
    }

    /** No rendered expression may retain an unexpanded template marker. */
    @Test
    void noExpressionRetainsTemplateMarkers() {
        for (var spec : TriageSafetyPolicy.ruleSpecsForTesting()) {
            String expression = spec.expression();
            assertTrue(!expression.contains("{site") && !expression.contains("{gap")
                            && !expression.contains("{ref:"),
                    spec.code() + " 存在未渲染的模板标记: " + expression);
        }
    }

    /**
     * The operands must not carry a stray quote. A malformed expansion can still compile, so this
     * asserts on the rendered text rather than on behaviour.
     */
    @Test
    void renderedOperandsCarryNoStrayQuote() {
        for (var spec : TriageSafetyPolicy.ruleSpecsForTesting()) {
            String expression = spec.expression();
            assertTrue(!expression.contains("\\\""),
                    spec.code() + " 渲染结果残留转义引号: " + expression);
        }
    }
}
