package com.aihospital.triage.domain;

import com.aihospital.shared.model.Models.SafetyAssessment;
import com.aihospital.shared.model.Models.SafetySignal;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** High-recall, auditable safety gate which model prose cannot override. */
@Component
public class TriageSafetyPolicy {
    public static final String POLICY_VERSION = "CN-ADULT-ONLINE-TRIAGE-2026.10-P8";
    private static final Pattern NEGATION = Pattern.compile("(没有|无|否认|未出现|并无|不伴|不存在|没出现|不觉得|不是)");
    private static final String NEGATION_TOKENS = "没有|否认|未出现|并无|不伴|不存在|没出现|不觉得|不是|不";
    private static final String CLAUSE_CHARS = "[^，,。；;！!？?]";

    /**
     * D11 裁定 3 的紧邻否定保护：只检查**命中起点之前**的否定短语，绝不在匹配词内部搜
     * 「无/不」。否则 `无法吞咽`、`单侧肢体无力` 这类本身含「无」的**肯定**急症会被误杀
     * ——`无法吞咽` 匹配起点就在「无」上，起点之前没有区域可搜，因此天然免疫。
     */
    private static final Pattern ADJACENT_NEGATION = Pattern.compile(
            "(?:没有|无|否认|不存在|不伴|并非|不是|未出现)(?:任何|明显(?:的)?)?\\s*$");

    /**
     * A2：临床映射（规则认哪些词、依据什么、已知缺口）已迁入 {@code safety-rules.json}。
     * 解析机制——否定词表、切句、紧邻否定保护、{@code isAsserted}——刻意留在本类，因为它们
     * 不是临床映射且承载安全关键语义。迁移的正确性由
     * {@code SafetyRuleCatalogMigrationTest} 逐条钉住渲染后的表达式。
     */
    private static final SafetyRuleCatalog CATALOG = SafetyRuleCatalog.load();
    private static final Map<String, Pattern> AUXILIARY = CATALOG.compileAuxiliaryPatterns(Set.of(
            "FOOD_REACTION", "GENERALIZED_RASH", "UNCLEAR_BLEEDING_RULE",
            "LIP_SWELLING", "RAPID_BREATHING"));

    // 第二步组合所用的 finding 全部来自同一份数据，不在 Java 里另抄一份。
    private static final Pattern FACIAL_SWELLING_FINDING = Pattern.compile(CATALOG.vocabulary("faceSwelling"));
    private static final Pattern AIRWAY_FINDING = Pattern.compile(CATALOG.vocabulary("airway"));
    private static final Pattern PREGNANCY_STATE_FINDING = Pattern.compile(CATALOG.vocabulary("pregnancyState"));
    private static final Pattern PREGNANCY_DANGER_FINDING = Pattern.compile(CATALOG.vocabulary("pregnancyDanger"));
    private static final Pattern POSTPARTUM_FINDING = Pattern.compile(CATALOG.vocabulary("postpartum"));

    /**
     * 未经临床审核: 「明显出血」是量级不明的描述，**不是**「大量出血」。按 D11 裁定它单独
     * 出现时既不进 ER-BLEEDING-001 也不判 URGENT，而是走确定性的「待补充信息」闸门。
     * 升急症的只有明确条件：大量出血、出血不止、剧烈疼痛。
     */
    private static final Pattern UNCLEAR_BLEEDING_FINDING = Pattern.compile(CATALOG.vocabulary("unclearBleeding"));

    /**
     * D10 第二步的组合规则，来自数据文件。`symmetric` 表示语序无关——`脸肿，张口受限`
     * 与 `张口受限，脸肿` 是同一临床画面。
     *
     * <p>未经临床审核: 这些组合**没有新增任何临床判断**。`ER-PREGNANCY-001` 与
     * `ER-FACE-SPREAD-001` 在第一步早已以同子句形式命中；这里只是把它们的判定范围
     * 扩到相邻小句，使逗号不再是隐形屏障。
     */
    private static final List<Combination> COMBINATIONS = buildCombinations();

    /** acuity 由命中的规则码决定，而不是由匹配过程累积的布尔值累积而成。 */
    private static final Set<String> EMERGENCY_CODES = Set.of(
            "ER-AIRWAY-001", "ER-BREATHING-001", "ER-CIRCULATION-001", "ER-NEURO-001",
            "ER-BLEEDING-001", "ER-TRAUMA-001", "ER-POISON-001", "ER-PREGNANCY-001",
            "ER-FACE-SPREAD-001", "ER-ALLERGY-001");
    private static final Set<String> URGENT_CODES = Set.of(
            "UR-TRAUMA-001", "UR-PAIN-001", "UR-FEVER-001", "UR-FACE-SWELLING-001",
            "UR-PREGNANCY-001", "UR-BLEEDING-UNCLEAR-001");

    /**
     * 未经临床审核: 「明显出血」不带部位与量级，无法判断是否失血性急症，也不宜凭这四字
     * 判 URGENT——那会让「手指有明显出血，不多，已止住」也被升级。因此它走 URGENT 的
     * **待追问**分支：服务据此进入「待补充信息」，按 D5 该处置不可预约，并追问部位、量、
     * 持续情况与头晕/晕厥等伴随表现。已有明确危险信号时仍由 ER 规则优先接管。
     */
    private static final Pattern UNCLEAR_BLEEDING_RULE = AUXILIARY.get("UNCLEAR_BLEEDING_RULE");
    private static final Pattern FOOD_REACTION = AUXILIARY.get("FOOD_REACTION");
    private static final Pattern GENERALIZED_RASH = AUXILIARY.get("GENERALIZED_RASH");
    private static final Pattern LIP_SWELLING = AUXILIARY.get("LIP_SWELLING");
    private static final Pattern RAPID_BREATHING = AUXILIARY.get("RAPID_BREATHING");
    /** Only a narrow subject guard for this derived rule; full event attribution remains open. */
    private static final Pattern OTHER_PERSON = Pattern.compile(
            "我朋友|我的朋友|我的孩子|(?:^|[，,。；;！!？?\\s])(?:他|她|朋友|孩子|宝宝|父亲|母亲|丈夫|妻子)");
    private static final Pattern HISTORICAL = Pattern.compile("(以前|从前|去年|多年前|小时候|曾经|既往|已经好了|现已缓解|已缓解)");
    private static final Pattern CURRENT_RESET = Pattern.compile("(现在|目前|如今|今天|此刻|再次|又出现|又开始)");
    private static final List<Rule> RULES = buildRules();

    /** Builds the step-1 rules from {@code safety-rules.json}; acuity comes from the data. */
    private static List<Rule> buildRules() {
        List<Rule> built = new ArrayList<>();
        for (RuleSpec spec : CATALOG.rules())
            built.add(new Rule(spec.code(), spec.category(), Pattern.compile(spec.expression()),
                    spec.reason(), "EMERGENCY".equals(spec.acuity()) ? Acuity.EMERGENCY : Acuity.URGENT));
        return List.copyOf(built);
    }

    /** Builds the step-2 adjacent-clause combinations from {@code safety-rules.json}. */
    private static List<Combination> buildCombinations() {
        List<Combination> built = new ArrayList<>();
        for (CombinationSpec spec : CATALOG.combinations())
            built.add(new Combination(spec.code(), spec.category(), spec.reason(),
                    Pattern.compile(spec.left()), Pattern.compile(spec.right()), spec.symmetric()));
        return List.copyOf(built);
    }

    /** A step-1 rule as declared in the data file. */
    public record RuleSpec(String code, String category, String acuity, String reason, String expression) {}

    /** A step-2 combination as declared in the data file. */
    public record CombinationSpec(String code, String category, String reason,
                                  String left, String right, boolean symmetric) {}

    /** Citation status of one rule, for the migration guard in the test sources. */
    public record CitationStatus(String code, boolean cited, boolean citationGap) {}

    // 以下仅为测试可观测性的窄口，不扩大生产可见面。
    static String vocabularyForTesting(String name) { return CATALOG.vocabulary(name); }

    static List<RuleSpec> ruleSpecsForTesting() { return CATALOG.rules(); }

    static List<CombinationSpec> combinationSpecsForTesting() { return CATALOG.combinations(); }

    static List<CitationStatus> catalogCitationsForTesting() { return CATALOG.citations(); }

    static Set<String> auxiliaryNamesForTesting() { return CATALOG.auxiliaryNames(); }

    /**
     * Builds a site-qualified symptom expression such as 舌头…肿.
     *
     * <p>The filler between the site word and the symptom is <em>tempered</em>: it may
     * consume any non-clause character except one that would start a negation.  This is
     * required because {@link #isAsserted} only inspects the 14 characters before the
     * match <em>start</em>.  With a plain {@code .{0,N}} filler a denial sitting between
     * the site and the symptom ends up inside the matched span and becomes invisible, so
     * 舌头没有肿 asserted as an airway emergency.
     *
     * <p>Bare 不 is safe in the guarded set because it only ever applies to filler
     * characters.  无法吞咽 and 吞咽不了 are separate alternatives carrying no filler, so
     * adding 不 cannot disable them, unlike a naive in-span negation scan which would also
     * disable 无法吞咽 and 单侧肢体无力.
     */
    static String siteSymptom(String siteWords, int maxGap, String symptom) {
        return "(?:" + siteWords + ")(?:(?!" + NEGATION_TOKENS + ")" + CLAUSE_CHARS + "){0," + maxGap
                + "}(?:" + symptom + ")";
    }

    /**
     * A negation-guarded filler that may cross a comma, but never a sentence terminator.
     *
     * <p>{@link #siteSymptom} deliberately stops at a comma because a site and its symptom
     * separated by a comma are usually two separate complaints. Facial swelling escalation is
     * the exception: 脸肿，张口受限 is one clinical picture, so the filler must span the comma
     * or the emergency combination silently never fires. The negation guard is still required,
     * otherwise 脸肿，没有吞咽困难 escalates.
     */
    static String gap() {
        return "(?:(?!" + NEGATION_TOKENS + ")[^。；;！!？?]){0,10}";
    }

    /**
     * D10 第一步：逐小句的肯定性判定。这一步的切句与否定判定逻辑**一行未改**，
     * D8 建立的保证（`舌头没有肿`、`全身没有红点`、`怀孕没有剧烈腹痛` 等不被误判）
     * 在这里原样成立。
     *
     * <p>切句仍按 {@code [，,。；;！!？?]|但是|但|然而} 消耗式分割，因此单条规则的
     * 部位词与症状词必须落在同一小句内才能命中——这正是跨小句组合需要第二步的原因。
     */
    private void assessWithinClauses(String[] clauses, Map<String, SafetySignal> signals) {
        for (String clause : clauses) {
            for (Rule rule : RULES) {
                Matcher matcher = rule.pattern().matcher(clause);
                while (matcher.find()) {
                    if (!assertedInClause(clause, matcher.start())) continue;
                    signals.putIfAbsent(rule.code(), new SafetySignal(rule.code(), rule.category(), matcher.group(), rule.reason()));
                }
            }
        }
    }

    /**
     * D10 第二步：相邻小句的组合判定。只在**两个相邻小句各自都肯定地**表达了某个
     * finding 时才升级——部位与症状分处不同小句并不足以判急症，但分处**不同句子**
     * 也不行，所以只比较相邻小句，不做全文滑动窗口。
     *
     * <p>每个小句的肯定性判定都复用第一步的 {@link #isAsserted}，并额外要求命中不被
     * 紧邻否定词覆盖（见 {@link #ADJACENT_NEGATION}）。因此 `脸肿，没有吞咽困难`、
     * `脸肿，无明显张口受限` 都不会升级；而 `脸肿，张口受限` 会。
     */
    private void assessAdjacentCombinations(String[] clauses, Map<String, SafetySignal> signals) {
        for (int i = 0; i + 1 < clauses.length; i++) {
            for (Combination combination : COMBINATIONS) {
                if (combinationHolds(clauses[i], clauses[i + 1], combination)) {
                    signals.putIfAbsent(combination.code(), new SafetySignal(combination.code(), combination.category(),
                            clauses[i] + " / " + clauses[i + 1], combination.reason()));
                }
            }
        }
    }

    private boolean combinationHolds(String left, String right, Combination combination) {
        boolean forward = assertsFinding(left, combination.left()) && assertsFinding(right, combination.right());
        boolean backward = combination.symmetric() && assertsFinding(left, combination.right()) && assertsFinding(right, combination.left());
        return forward || backward;
    }

    /**
     * D11 裁定 3 的核心约束：紧邻否定保护只检查**命中起点之前**的否定短语，绝不在匹配词
     * 内部搜「无/不」。否则 `无法吞咽`、`单侧肢体无力` 这类本身含「无」的**肯定**急症会
     * 被误杀——`无法吞咽` 匹配起点就在「无」上，起点之前没有区域可搜，因此天然免疫。
     *
     * <p>现已同时用于第一步与第二步（此前只有第二步有）。
     */
    private boolean assertedInClause(String clause, int start) {
        return isAsserted(clause, start) && !isAdjacentlyNegated(clause, start);
    }
    private boolean assertsFinding(String clause, Pattern finding) {
        Matcher matcher = finding.matcher(clause);
        while (matcher.find()) {
            if (!assertedInClause(clause, matcher.start())) continue;
            return true;
        }
        return false;
    }

    private boolean isAdjacentlyNegated(String clause, int start) {
        // region() 限定搜索范围为「命中起点前的 12 字」，且会重置 matcher。
        // 不能用 JDK 20 的 matcher(CharSequence, int, int) 重载：本仓库基准是 JDK 17。
        return ADJACENT_NEGATION.matcher(clause).region(Math.max(0, start - 12), start).find();
    }

    public SafetyAssessment assess(String text) {
        String source = text == null ? "" : text.trim();
        Map<String, SafetySignal> signals = new LinkedHashMap<>();
        String[] clauses = source.split("[，,。；;！!？?]|但是|但|然而");
        assessWithinClauses(clauses, signals);
        assessAdjacentCombinations(clauses, signals);

        boolean emergency = signals.values().stream().anyMatch(signal -> EMERGENCY_CODES.contains(signal.ruleCode()));
        boolean urgent = signals.values().stream().anyMatch(signal -> URGENT_CODES.contains(signal.ruleCode()));
        boolean foodReaction = hasAsserted(source, FOOD_REACTION);
        boolean generalizedRash = hasAsserted(source, GENERALIZED_RASH);
        boolean foodRelatedAirway = foodReaction && !OTHER_PERSON.matcher(source).find()
                && hasAsserted(source, LIP_SWELLING) && hasAsserted(source, RAPID_BREATHING);
        if ((foodReaction && generalizedRash) || foodRelatedAirway) {
            signals.putIfAbsent("ER-ALLERGY-001", new SafetySignal("ER-ALLERGY-001", "疑似严重过敏",
                    foodRelatedAirway ? "食物接触后唇部肿胀伴呼吸变急" : "食物相关不适伴全身性皮疹或红肿",
                    "可能出现严重全身性过敏反应，不能等待普通门诊预约"));
            emergency = true;
        }
        String acuity = emergency ? "EMERGENCY" : urgent ? "URGENT" : "ROUTINE";
        List<String> actions = emergency
                ? List.of("立即联系当地急救服务（中国大陆可拨打 120）或前往最近急诊", "不要自行驾车，不要等待普通门诊号源", "如身边有人，请告知对方并避免独处")
                : urgent ? List.of("建议今天尽快前往线下医疗机构评估", "症状加重或出现意识、呼吸、胸痛等异常时立即急诊")
                : List.of("可继续预问诊并根据完整信息选择就医方向");
        return new SafetyAssessment(POLICY_VERSION, acuity, emergency, emergency || urgent,
                List.copyOf(signals.values()), actions);
    }

    public boolean requiresImmediateCare(String text) { return assess(text).stopRoutineFlow(); }

    /**
     * 未经临床审核: 「明显出血」需要追问而不是分诊成某个科室或给出号源。返回 true 会让
     * 服务进入「待补充信息」，而该处置按 D5 不可预约——这正是裁定要求的「暂停普通预约
     * 并追问」。与 {@link #requiresImmediateCare} 不同，本方法**不**升级急症。
     */
    public boolean requiresBleedingClarification(String text) {
        if (text == null || text.isBlank()) return false;
        if (requiresImmediateCare(text)) return false;
        return UNCLEAR_BLEEDING_RULE.matcher(text).find();
    }

    /** Removes negated and historical safety phrases before routing and retrieval. */
    public String removeNegatedRedFlags(String text) {
        if (text == null || text.isBlank()) return "";
        List<String> retained = new ArrayList<>();
        for (String clause : text.split("(?<=[，,。；;！!？?])|但是|但|然而")) {
            boolean hasAssertedFlag = false;
            boolean hasAnyFlag = false;
            for (Rule rule : RULES) {
                Matcher matcher = rule.pattern().matcher(clause);
                while (matcher.find()) {
                    hasAnyFlag = true;
                    if (isAsserted(clause, matcher.start())) hasAssertedFlag = true;
                }
            }
            if (!hasAnyFlag || hasAssertedFlag) retained.add(clause);
        }
        return String.join("", retained);
    }

    public String emergencyAdvice(SafetyAssessment assessment) {
        if (assessment == null || assessment.signals().isEmpty())
            return "检测到可能的紧急症状，请立即联系当地急救服务或前往最近急诊。";
        String categories = assessment.signals().stream().map(SafetySignal::category).distinct()
                .reduce("", (a, b) -> a.isBlank() ? b : a + "、" + b);
        return "安全规则检测到“" + categories + "”相关紧急信号。请立即联系当地急救服务（中国大陆可拨打 120）或前往最近急诊；不要自行驾车，也不要等待线上分诊或普通门诊预约。";
    }

    public String emergencyAdvice(String text) { return emergencyAdvice(assess(text)); }

    private boolean hasAsserted(String text, Pattern pattern) {
        for (String clause : text.split("[，,。；;！!？?]|但是|但|然而")) {
            Matcher matcher = pattern.matcher(clause);
            while (matcher.find()) if (assertedInClause(clause, matcher.start())) return true;
        }
        return false;
    }

    private boolean isAsserted(String clause, int start) {
        String prefix = clause.substring(Math.max(0, start - 14), start);
        Matcher negation = NEGATION.matcher(prefix);
        while (negation.find()) {
            String tail = prefix.substring(negation.end());
            if (tail.isBlank() || tail.matches("[和及或、与且也]*")) return false;
            boolean earlierFlag = RULES.stream().anyMatch(rule -> rule.pattern().matcher(tail).find());
            if (tail.matches(".*(?:和|及|或|、|与|且|也)")
                    && (earlierFlag || negation.group().length() > 1)) return false;
        }
        Matcher historical = HISTORICAL.matcher(prefix);
        int lastHistoricalEnd = -1;
        while (historical.find()) lastHistoricalEnd = historical.end();
        if (lastHistoricalEnd >= 0 && !CURRENT_RESET.matcher(prefix.substring(lastHistoricalEnd)).find()) return false;
        return true;
    }

    private static Rule emergency(String code, String category, String expression, String reason) {
        return new Rule(code, category, Pattern.compile(expression), reason, Acuity.EMERGENCY);
    }
    private static Rule urgent(String code, String category, String expression, String reason) {
        return new Rule(code, category, Pattern.compile(expression), reason, Acuity.URGENT);
    }
    private enum Acuity { EMERGENCY, URGENT }
    private record Rule(String code, String category, Pattern pattern, String reason, Acuity acuity) {}
    private record Combination(String code, String category, String reason, Pattern left, Pattern right, boolean symmetric) {}
}
