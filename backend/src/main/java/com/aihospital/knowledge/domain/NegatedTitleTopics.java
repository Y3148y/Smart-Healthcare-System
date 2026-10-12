package com.aihospital.knowledge.domain;

import com.aihospital.shared.model.Models.Evidence;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * A narrow retrieval guard: an explicitly denied, exact document title topic
 * must not be selected as evidence solely because its words occur in the query.
 * This does not interpret symptoms or replace the raw-text safety assessment.
 */
public final class NegatedTitleTopics {
    private NegatedTitleTopics() {}

    public static Set<String> excludedTitles(String query, List<Evidence> corpus) {
        if (query == null || query.isBlank() || corpus == null || corpus.isEmpty()) return Set.of();
        Set<String> excluded = new HashSet<>();
        for (Evidence evidence : corpus) {
            String title = evidence.title();
            if (title == null || title.isBlank()) continue;
            String topic = title.split("[：:]", 2)[0].strip();
            if (topic.codePointCount(0, topic.length()) < 2 || topic.codePointCount(0, topic.length()) > 16)
                continue;
            Pattern denial = Pattern.compile("(?:没有|并无|不伴|未出现|否认)" + Pattern.quote(topic));
            String remainder = denial.matcher(query).replaceAll("");
            // A later (or earlier) unnegated mention may be a new assertion.
            // With mixed evidence, do not suppress the topic on a lexical guess.
            if (!remainder.equals(query) && !remainder.contains(topic)) excluded.add(title);
        }
        return Set.copyOf(excluded);
    }
}
