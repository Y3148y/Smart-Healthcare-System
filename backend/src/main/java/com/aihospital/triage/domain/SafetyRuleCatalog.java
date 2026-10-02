package com.aihospital.triage.domain;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Loads the clinical mappings for {@link TriageSafetyPolicy} from
 * {@code classpath:safety-rules.json}.
 *
 * <p>为什么数据化而解析机制不数据化：临床上可审的部分（每条规则认哪些词、依据什么、
 * 已知缺口）放进数据，临床人员不必读正则就能审；否定词表、切句、紧邻否定保护与
 * {@code isAsserted} 留在代码里，因为它们不是临床映射，且承载安全关键语义，需要以
 * 逻辑形式复核。
 *
 * <p>模板只有三种标记，渲染后必须与搬迁前的表达式逐字一致——
 * {@code SafetyRuleCatalogMigrationTest} 逐条钉住这一点。
 */
public final class SafetyRuleCatalog {

    /**
     * The three operands must stop at the closing brace, and quoted ones must stop at their
     * own closing quote. Two bugs met here once already:
     *
     * <ol>
     *   <li>A greedy {@code \S+} for the third operand swallowed {@code "}|产后大出血} and every
     *       alternative after it — the template has no whitespace after the brace, so nothing
     *       failed loudly and {@code ER-PREGNANCY-001} silently lost its whole tail.
     *   <li>Matching only to {@code }} left the trailing quote in the operand, so the expansion
     *       embedded a stray {@code "} and the pattern stopped matching.
     * </ol>
     *
     * <p>So: quoted operands end at their closing quote, unquoted ({@code @name}) ones end at
     * the brace, and neither may cross it.
     */
    private static final Pattern SITE = Pattern.compile(
            "\\{site ((?:\"[^\"]*\")|@\\w+) (\\d+) ((?:\"[^\"]*\")|@\\w+)\\}");
    private static final Pattern GAP = Pattern.compile("\\{gap\\}");
    private static final Pattern REF = Pattern.compile("\\{ref:([A-Za-z0-9_]+)\\}");
    private static final String RESOURCE = "safety-rules.json";

    private final Map<String, String> vocabulary;
    private final List<TriageSafetyPolicy.RuleSpec> rules;
    private final List<TriageSafetyPolicy.CombinationSpec> combinations;
    private final Map<String, String> auxiliary;
    private final List<TriageSafetyPolicy.CitationStatus> citations;

    private SafetyRuleCatalog(Map<String, String> vocabulary,
                              List<TriageSafetyPolicy.RuleSpec> rules,
                              List<TriageSafetyPolicy.CombinationSpec> combinations,
                              Map<String, String> auxiliary,
                              List<TriageSafetyPolicy.CitationStatus> citations) {
        this.vocabulary = vocabulary;
        this.rules = rules;
        this.combinations = combinations;
        this.auxiliary = auxiliary;
        this.citations = citations;
    }

    static SafetyRuleCatalog load() {
        try (InputStream in = SafetyRuleCatalog.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (in == null) throw new IllegalStateException("缺少安全规则数据文件: " + RESOURCE);
            return parse(new ObjectMapper().readTree(in));
        } catch (IOException e) {
            throw new IllegalStateException("安全规则数据文件无法解析: " + RESOURCE, e);
        }
    }

    static SafetyRuleCatalog parse(JsonNode root) {
        // vocabulary 采用不动点解析，因此不依赖 JSON 的键顺序：airwayGrouped 引用 airway、
        // faceSwelling 引用 faceSite 这类嵌套都能解开；无法解开的（循环引用或拼错）直接失败。
        Map<String, JsonNode> rawVocabulary = new LinkedHashMap<>();
        root.path("vocabulary").fields().forEachRemaining(entry -> rawVocabulary.put(entry.getKey(), entry.getValue()));
        Map<String, String> vocabulary = new LinkedHashMap<>();
        for (int pass = 0; pass <= rawVocabulary.size(); pass++) {
            boolean progressed = false;
            for (Map.Entry<String, JsonNode> entry : rawVocabulary.entrySet()) {
                if (vocabulary.containsKey(entry.getKey())) continue;
                String rendered = expand(entry.getValue().asText(), vocabulary);
                if (REF.matcher(rendered).find()) continue;
                vocabulary.put(entry.getKey(), rendered);
                progressed = true;
            }
            if (!progressed) break;
        }
        if (vocabulary.size() != rawVocabulary.size()) {
            List<String> unresolved = new ArrayList<>(rawVocabulary.keySet());
            unresolved.removeAll(vocabulary.keySet());
            throw new IllegalStateException("词表存在无法解析的循环或未知引用: " + unresolved);
        }

        List<TriageSafetyPolicy.RuleSpec> rules = new ArrayList<>();
        for (JsonNode rule : root.path("rules"))
            rules.add(new TriageSafetyPolicy.RuleSpec(
                    rule.path("code").asText(),
                    rule.path("category").asText(),
                    rule.path("acuity").asText(),
                    rule.path("reason").asText(),
                    expand(rule.path("expression").asText(), vocabulary)));

        List<TriageSafetyPolicy.CombinationSpec> combinations = new ArrayList<>();
        for (JsonNode combination : root.path("combinations"))
            combinations.add(new TriageSafetyPolicy.CombinationSpec(
                    combination.path("code").asText(),
                    combination.path("category").asText(),
                    combination.path("reason").asText(),
                    expand(combination.path("left").asText(), vocabulary),
                    expand(combination.path("right").asText(), vocabulary),
                    combination.path("symmetric").asBoolean(true)));

        Map<String, String> auxiliary = new LinkedHashMap<>();
        for (JsonNode pattern : root.path("auxiliaryPatterns")) {
            String name = pattern.path("name").asText();
            if (name.isBlank() || auxiliary.containsKey(name))
                throw new IllegalStateException("辅助模式名称为空或重复: " + name);
            auxiliary.put(name, expand(pattern.path("expression").asText(), vocabulary));
        }

        List<TriageSafetyPolicy.CitationStatus> citations = new ArrayList<>();
        for (JsonNode rule : rules(root))
            citations.add(citation(rule.path("code").asText(), rule));
        for (JsonNode rule : root.path("derivedRules"))
            citations.add(citation(rule.path("code").asText(), rule));

        return new SafetyRuleCatalog(vocabulary, rules, combinations, auxiliary, citations);
    }

    private static List<JsonNode> rules(JsonNode root) {
        List<JsonNode> nodes = new ArrayList<>();
        root.path("rules").forEach(nodes::add);
        return nodes;
    }

    private static TriageSafetyPolicy.CitationStatus citation(String code, JsonNode rule) {
        boolean cited = rule.path("citations").size() > 0;
        return new TriageSafetyPolicy.CitationStatus(code, cited, rule.path("citationGap").asBoolean(false));
    }

    /**
     * Renders one expression. {@code {site ...}} and {@code {gap}} delegate to the very same
     * helpers the hard-coded rules used, so a migrated expression is byte-identical by
     * construction rather than by careful copying.
     *
     * <p>A {@code {site}} operand is either a quoted literal or {@code @name} referring to the
     * vocabulary; a {@code {ref:name}} is a whole-token reference. Both exist so the vocabulary
     * stays single-sourced — {@code faceSite} and {@code airway} are declared once and reused by
     * rules, combinations and findings alike.
     */
    static String expand(String expression, Map<String, String> vocabulary) {
        String rendered = replaceAll(SITE, expression, matcher -> TriageSafetyPolicy.siteSymptom(
                operand(matcher.group(1), vocabulary),
                Integer.parseInt(matcher.group(2)),
                operand(matcher.group(3), vocabulary)));
        rendered = GAP.matcher(rendered).replaceAll(Matcher.quoteReplacement(TriageSafetyPolicy.gap()));
        if (REF.matcher(rendered).find()) {
            StringBuilder out = new StringBuilder();
            Matcher matcher = REF.matcher(rendered);
            while (matcher.find()) {
                String replacement = vocabulary.get(matcher.group(1));
                if (replacement == null)
                    throw new IllegalStateException("未知词表引用: {ref:" + matcher.group(1) + "}");
                matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
            }
            matcher.appendTail(out);
            rendered = out.toString();
        }
        return rendered;
    }

    private static String operand(String raw, Map<String, String> vocabulary) {
        if (raw.startsWith("@")) {
            String name = raw.substring(1);
            String value = vocabulary.get(name);
            if (value == null) throw new IllegalStateException("未知词表引用: @" + name);
            return value;
        }
        if (raw.length() > 1 && raw.startsWith("\"") && raw.endsWith("\"")) {
            return raw.substring(1, raw.length() - 1);
        }
        return raw;
    }

    private static String replaceAll(Pattern pattern, String input, java.util.function.Function<Matcher, String> render) {
        StringBuilder out = new StringBuilder();
        Matcher matcher = pattern.matcher(input);
        while (matcher.find()) matcher.appendReplacement(out, Matcher.quoteReplacement(render.apply(matcher)));
        matcher.appendTail(out);
        return out.toString();
    }

    List<TriageSafetyPolicy.RuleSpec> rules() { return rules; }

    List<TriageSafetyPolicy.CombinationSpec> combinations() { return combinations; }

    Map<String, Pattern> compileAuxiliaryPatterns(Set<String> usedNames) {
        if (!auxiliary.keySet().equals(usedNames))
            throw new IllegalStateException("辅助模式声明与使用不一致: declared=" + auxiliary.keySet()
                    + ", used=" + usedNames);
        Map<String, Pattern> compiled = new LinkedHashMap<>();
        auxiliary.forEach((name, expression) -> compiled.put(name, Pattern.compile(expression)));
        return Map.copyOf(compiled);
    }

    Set<String> auxiliaryNames() { return Set.copyOf(auxiliary.keySet()); }

    List<TriageSafetyPolicy.CitationStatus> citations() { return citations; }

    Map<String, String> vocabulary() { return vocabulary; }

    String vocabulary(String name) { return vocabulary.get(name); }
}
