package com.aihospital.triage.domain;

import java.util.List;
import org.springframework.stereotype.Component;

/** Safety screening is deterministic and cannot be overridden by model prose. */
@Component
public class TriageSafetyPolicy {
    private static final List<String> RED_FLAG_TERMS = List.of(
            "急性胸痛", "持续胸痛", "剧烈胸痛", "呼吸困难", "喘不上气", "意识不清", "意识障碍", "晕厥", "昏迷",
            "口角歪斜", "单侧肢体无力", "说话不清", "突发剧烈头痛", "骨头外露", "骨头穿出皮肤",
            "伤口大量出血", "无法吞咽", "吞咽不了");
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
}
