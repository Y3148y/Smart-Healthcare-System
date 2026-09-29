package com.aihospital.triage.domain;

import java.util.List;
import org.springframework.stereotype.Component;

/** Safety screening is deterministic and cannot be overridden by model prose. */
@Component
public class TriageSafetyPolicy {
    private static final List<String> RED_FLAG_TERMS = List.of(
            "急性胸痛", "持续胸痛", "剧烈胸痛", "胸痛", "胸口痛", "胸口疼", "心口痛", "心口疼",
            "呼吸困难", "喘不上气", "喘不过气", "气短", "意识不清", "意识障碍", "晕厥", "昏迷",
            "口角歪斜", "单侧肢体无力", "说话不清", "突发剧烈头痛", "骨头外露", "骨头穿出皮肤",
            "伤口大量出血", "无法吞咽", "吞咽不了", "咯血", "呕血", "黑便", "抽搐", "惊厥",
            "全身抽搐", "脸肿", "舌头肿", "喉头水肿", "自杀", "服药过量", "中毒");
    private static final List<String> NEGATION_TERMS = List.of("没有", "无", "否认", "未出现", "并无", "不伴", "不存在");

    public boolean requiresImmediateCare(String text) {
        if (text == null || text.isBlank()) return false;
        String[] clauses = text.split("[，,。；;！!？?]|但是|但|然而");
        for (String clause : clauses) {
            for (String term : RED_FLAG_TERMS) {
                int from = 0;
                while (true) {
                    int index = clause.indexOf(term, from);
                    if (index < 0) break;
                    String prefix = clause.substring(Math.max(0, index - 10), index);
                    if (NEGATION_TERMS.stream().noneMatch(prefix::contains)) return true;
                    from = index + term.length();
                }
            }
        }
        return false;
    }

    public String removeNegatedRedFlags(String text) {
        if (text == null || text.isBlank()) return "";
        StringBuilder normalized = new StringBuilder(text);
        for (String term : RED_FLAG_TERMS) {
            int from = 0;
            while (true) {
                int index = normalized.indexOf(term, from);
                if (index < 0) break;
                String prefix = normalized.substring(Math.max(0, index - 10), index);
                if (NEGATION_TERMS.stream().anyMatch(prefix::contains))
                    for (int i = index; i < index + term.length(); i++) normalized.setCharAt(i, ' ');
                from = index + term.length();
            }
        }
        return normalized.toString();
    }

    /** Gives the patient a concrete action while avoiding a diagnosis. */
    public String emergencyAdvice(String text) {
        String safe = text == null ? "" : text;
        if (safe.matches("(?s).*(自杀|自残|不想活|服药过量|中毒).*"))
            return "检测到可能的自伤、中毒或药物过量风险。请立即联系当地急救服务或前往急诊；如身边有人，请不要独处并请对方协助。";
        if (safe.matches("(?s).*(脸肿|舌头肿|喉头水肿|无法吞咽|吞咽不了).*"))
            return "检测到可能影响气道的症状。请立即前往急诊或拨打当地急救电话，不要自行等待普通门诊号源。";
        if (safe.matches("(?s).*(口角歪斜|单侧肢体无力|说话不清|突发剧烈头痛|意识不清|意识障碍|晕厥|昏迷|抽搐|惊厥).*"))
            return "检测到可能的神经系统紧急信号。请立即拨打当地急救电话或前往急诊，记录症状开始时间，不要等待线上分诊。";
        if (safe.matches("(?s).*(急性胸痛|持续胸痛|剧烈胸痛|胸痛|胸口痛|胸口疼|心口痛|心口疼|呼吸困难|喘不上气|喘不过气|气短|咯血).*"))
            return "检测到可能的心肺紧急信号。请立即拨打当地急救电话或前往急诊，不要自行驾车或等待普通门诊预约。";
        if (safe.matches("(?s).*(骨头外露|骨头穿出皮肤|伤口大量出血|呕血|黑便).*"))
            return "检测到可能需要紧急处置的出血或严重创伤信号。请立即前往急诊或拨打当地急救电话，不要等待线上分诊。";
        return "检测到可能的紧急症状，请立即前往急诊或拨打当地急救电话；不要等待线上分诊。";
    }
}
