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
    public static final String POLICY_VERSION = "CN-ADULT-ONLINE-TRIAGE-2026.10-P3";
    private static final Pattern NEGATION = Pattern.compile("(没有|无|否认|未出现|并无|不伴|不存在|没出现|不觉得|不是)");
    private static final String NEGATION_TOKENS = "没有|否认|未出现|并无|不伴|不存在|没出现|不觉得|不是|不";
    private static final String CLAUSE_CHARS = "[^，,。；;！!？?]";

    /**
     * 未经临床审核: 下面的词表同时服务第一步（逐小句规则表达式）与第二步（相邻小句组合），
     * 抽成常量是为了两者不会各自漂移。A2 若把这些词表迁入数据文件，须保持单一来源。
     */
    private static final String FACE_SITE = "脸|面|脸颊|面部|牙龈|智齿";
    private static final String AIRWAY = "无法吞咽|吞咽不了|呼吸困难|喘不上气|喘不过气|说不出话|张口受限|张不开嘴";
    private static final String FACE_SWELLING = siteSymptom(FACE_SITE, 6, "肿");
    private static final String AIRWAY_GROUPED = "(?:" + AIRWAY + ")";

    /**
     * D10 第一步的否定判定（{@link #isAsserted}）要求否定词与命中之间只隔着并列连接词，
     * 因此 `无明显张口受限` 会被判为肯定——因为 `明显` 不是连接词。第二步的组合结论
     * 代价更高（一个组合错误会升级或漏掉一条急诊规则），所以额外加一层「紧邻否定」保护：
     * 否定词只允许隔着 `明显|任何` 和空白，且必须紧贴命中起点。
     *
     * <p>本保护目前**只用于第二步组合**。第一步的 11 条规则尚未加这层保护，属已登记缺口
     * （给全部规则加会改变既有行为，须单独裁定并全量回归）。
     */
    private static final Pattern ADJACENT_NEGATION = Pattern.compile(
            "(?:没有|无|否认|不存在|不伴|并非|不是|未出现)(?:任何|明显(?:的)?)?\\s*$");

    private static final Pattern FACIAL_SWELLING_FINDING = Pattern.compile(FACE_SWELLING);
    private static final Pattern AIRWAY_FINDING = Pattern.compile(AIRWAY);
    private static final Pattern PREGNANCY_STATE_FINDING = Pattern.compile("怀孕|孕期");
    private static final Pattern PREGNANCY_DANGER_FINDING = Pattern.compile("大量出血|剧烈腹痛");
    private static final Pattern POSTPARTUM_FINDING = Pattern.compile("产后大出血");

    /**
     * D10 第二步的组合规则。`symmetric` 表示语序无关——`脸肿，张口受限` 与
     * `张口受限，脸肿` 是同一临床画面。
     *
     * <p>未经临床审核: 这三条**没有新增任何临床判断**。`ER-PREGNANCY-001` 与
     * `ER-FACE-SPREAD-001` 在第一步早已以同子句形式命中；这里只是把它们的判定范围
     * 扩到相邻小句，使逗号不再是隐形屏障。第三条（产后 + 危险症状）同样是把已有词表
     * 跨小句组合，不引入新症状词。
     */
    private static final List<Combination> COMBINATIONS = List.of(
            new Combination("ER-PREGNANCY-001", "孕产", "可能存在孕产期紧急风险",
                    PREGNANCY_STATE_FINDING, PREGNANCY_DANGER_FINDING, true),
            new Combination("ER-FACE-SPREAD-001", "口腔颌面部",
                    "面部肿胀同时出现吞咽、呼吸或张口受限表现，可能存在口面间隙感染扩散",
                    FACIAL_SWELLING_FINDING, AIRWAY_FINDING, true),
            new Combination("ER-PREGNANCY-001", "孕产", "可能存在孕产期紧急风险",
                    POSTPARTUM_FINDING, PREGNANCY_DANGER_FINDING, true));

    /** acuity 由命中的规则码决定，而不是由匹配过程累积的布尔值累积而成。 */
    private static final Set<String> EMERGENCY_CODES = Set.of(
            "ER-AIRWAY-001", "ER-BREATHING-001", "ER-CIRCULATION-001", "ER-NEURO-001",
            "ER-BLEEDING-001", "ER-TRAUMA-001", "ER-POISON-001", "ER-PREGNANCY-001",
            "ER-FACE-SPREAD-001", "ER-ALLERGY-001");
    private static final Set<String> URGENT_CODES = Set.of(
            "UR-TRAUMA-001", "UR-PAIN-001", "UR-FEVER-001", "UR-FACE-SWELLING-001");
    private static final Pattern FOOD_REACTION = Pattern.compile(
            "食物过敏|" + siteSymptom("吃(了|完)", 16, "过敏|起疹|红疹|红肿|风团"));
    private static final Pattern GENERALIZED_RASH = Pattern.compile(
            siteSymptom("全身|大面积|大片|遍身", 16, "红肿|红疹|红点|皮疹|风团|荨麻疹|起疹"));
    private static final Pattern HISTORICAL = Pattern.compile("(以前|从前|去年|多年前|小时候|曾经|既往|已经好了|现已缓解|已缓解)");
    private static final Pattern CURRENT_RESET = Pattern.compile("(现在|目前|如今|今天|此刻|再次|又出现|又开始)");
    private static final List<Rule> RULES = List.of(
            emergency("ER-AIRWAY-001", "气道",
                    siteSymptom("舌头|舌体|咽喉|喉头", 5, "肿|水肿") + "|无法吞咽|吞咽不了|窒息|说不出话",
                    "可能存在气道受影响的信号"),
            emergency("ER-FACE-SPREAD-001", "口面间隙与气道",
                    // A swollen face alone is not an emergency. Escalation needs a spreading or
                    // airway feature in the same clause. Note assess() splits on commas first, so
                    // 脸肿，张口受限 cannot be matched by one span: the clause split is the D9
                    // structural cause, not something a rule should paper over. Both orders are
                    // written out so either phrasing inside one clause still escalates.
                    // 未经临床审核: see SDCEP Dental Abscess and NHS dental abscess guidance.
                    FACE_SWELLING
                            + gap() + AIRWAY_GROUPED
                            + "|" + AIRWAY_GROUPED
                            + gap() + FACE_SWELLING
                            + "|口底肿|口底三角区",
                    "面部肿胀同时出现吞咽、呼吸或张口受限表现，可能存在口面间隙感染扩散"),            emergency("ER-BREATHING-001", "呼吸", "严重呼吸困难|呼吸困难|喘不上气|喘不过气|不能平卧|口唇发紫|嘴唇发紫|咯血", "可能存在严重呼吸异常"),
            emergency("ER-CIRCULATION-001", "循环", "急性胸痛|持续胸痛|剧烈胸痛|胸痛|胸口痛|胸口疼|心口痛|心口疼", "当前胸痛在信息不足时不能在线排除心肺急症"),
            emergency("ER-NEURO-001", "神经", "意识不清|意识障碍|昏迷|晕厥|口角歪斜|单侧肢体无力|说话不清|言语不清|突发剧烈头痛|全身抽搐|抽搐|惊厥", "可能存在急性神经系统异常"),
            emergency("ER-BLEEDING-001", "出血", "伤口大量出血|出血不止|呕血|大量咯血|便血不止|黑便伴头晕", "可能存在严重出血"),
            emergency("ER-TRAUMA-001", "严重创伤", "骨头外露|骨头穿出皮肤|开放性骨折|肢体断裂", "可能存在开放性骨折或严重创伤"),
            emergency("ER-POISON-001", "中毒与自伤", "自杀|自残|不想活|服药过量|药物过量|中毒|误服农药", "可能存在自伤、中毒或药物过量风险"),
            emergency("ER-PREGNANCY-001", "孕产",
                    siteSymptom("怀孕|孕期", 8, "大量出血|剧烈腹痛") + "|产后大出血",
                    "可能存在孕产期紧急风险"),
            urgent("UR-TRAUMA-001", "创伤", "疑似骨折|骨折|摔断|骨头断|明显变形|不能活动", "外伤可能需要尽快影像检查和固定处理"),
            // 未经临床审核: single facial swelling is not an emergency on its own. It is routed to
            // 尽快就医 so the patient is advised to be assessed offline the same day. Emergency
            // escalation is handled by ER-FACE-SPREAD-001 when a spreading or airway feature appears.
            urgent("UR-FACE-SWELLING-001", "口面部肿胀",
                    FACE_SWELLING
                            + "|肿" + gap() + "(?:脸|面部|脸颊)"
                            + "|(?:脸|面部|脸颊)" + gap() + "肿",
                    "面部肿胀需线下尽快评估是否存在感染扩散；单独出现不等于急症"),
            urgent("UR-FEVER-001", "感染", "持续高热|高烧不退|" + siteSymptom("体温", 3, "39|40"), "持续高热需要尽快线下评估"),
            urgent("UR-PAIN-001", "疼痛", "剧烈腹痛|腹痛难忍|疼痛难忍", "剧烈疼痛需要尽快线下评估")
    );

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
    private static String siteSymptom(String siteWords, int maxGap, String symptom) {
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
    private static String gap() {
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
                    if (!isAsserted(clause, matcher.start())) continue;
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

    /** True when this clause affirmatively expresses the finding, with no adjacent negation. */
    private boolean assertsFinding(String clause, Pattern finding) {
        Matcher matcher = finding.matcher(clause);
        while (matcher.find()) {
            if (!isAsserted(clause, matcher.start())) continue;
            if (isAdjacentlyNegated(clause, matcher.start())) continue;
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
        if (hasAsserted(source, FOOD_REACTION) && hasAsserted(source, GENERALIZED_RASH)) {
            signals.putIfAbsent("ER-ALLERGY-001", new SafetySignal("ER-ALLERGY-001", "疑似严重过敏",
                    "食物相关不适伴全身性皮疹或红肿", "可能出现严重全身性过敏反应，不能等待普通门诊预约"));
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
            while (matcher.find()) if (isAsserted(clause, matcher.start())) return true;
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
