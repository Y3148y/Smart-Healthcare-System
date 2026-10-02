package com.aihospital.triage.domain;

import com.aihospital.shared.model.Models.SafetyAssessment;
import com.aihospital.shared.model.Models.SafetySignal;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** High-recall, auditable safety gate which model prose cannot override. */
@Component
public class TriageSafetyPolicy {
    public static final String POLICY_VERSION = "CN-ADULT-ONLINE-TRIAGE-2026.10-P2";
    private static final Pattern NEGATION = Pattern.compile("(没有|无|否认|未出现|并无|不伴|不存在|没出现|不觉得|不是)");
    private static final String NEGATION_TOKENS = "没有|否认|未出现|并无|不伴|不存在|没出现|不觉得|不是|不";
    private static final String CLAUSE_CHARS = "[^，,。；;！!？?]";
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
                    siteSymptom("脸|面|脸颊|面部|牙龈|智齿", 6, "肿")
                            + gap() + "(?:无法吞咽|吞咽不了|呼吸困难|喘不上气|喘不过气|说不出话|张口受限|张不开嘴)"
                            + "|(?:无法吞咽|吞咽不了|呼吸困难|喘不上气|喘不过气|说不出话|张口受限|张不开嘴)"
                            + gap() + siteSymptom("脸|面|脸颊|面部|牙龈|智齿", 6, "肿")
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
                    siteSymptom("脸|面|脸颊|面部|牙龈|智齿", 6, "肿")
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

    public SafetyAssessment assess(String text) {
        String source = text == null ? "" : text.trim();
        Map<String, SafetySignal> signals = new LinkedHashMap<>();
        boolean emergency = false;
        boolean urgent = false;
        for (String clause : source.split("[，,。；;！!？?]|但是|但|然而")) {
            for (Rule rule : RULES) {
                Matcher matcher = rule.pattern().matcher(clause);
                while (matcher.find()) {
                    if (!isAsserted(clause, matcher.start())) continue;
                    signals.putIfAbsent(rule.code(), new SafetySignal(rule.code(), rule.category(), matcher.group(), rule.reason()));
                    emergency |= rule.acuity() == Acuity.EMERGENCY;
                    urgent |= rule.acuity() == Acuity.URGENT;
                }
            }
        }
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
}
