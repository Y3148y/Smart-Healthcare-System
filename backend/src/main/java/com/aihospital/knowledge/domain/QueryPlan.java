package com.aihospital.knowledge.domain;

import java.util.List;

/** Shadow planning only: no rewrite, symptom inference, or automatic history concatenation. */
public record QueryPlan(String currentQuestion, List<Context> patientContext, String version) {
    public record Context(String messageId, String text) {}
    public QueryPlan {
        if (currentQuestion == null || currentQuestion.isBlank() || currentQuestion.length() > 12000)
            throw new IllegalArgumentException("Invalid current retrieval question");
        patientContext = List.copyOf(patientContext);
    }
    public static QueryPlan shadow(String currentQuestion, List<Context> patientStatements) {
        var window = new java.util.ArrayList<Context>();
        int remaining = 4000;
        for (int i = patientStatements.size() - 1; i >= 0 && window.size() < 5; i--) {
            Context item = patientStatements.get(i);
            if (item.messageId() == null || item.text() == null || item.text().isBlank()) continue;
            // Never truncate a statement: clipping can remove a negation or its subject.
            if (item.text().length() > remaining) break;
            window.add(0, item);
            remaining -= item.text().length();
        }
        return new QueryPlan(currentQuestion, window, "SHADOW_ORIGINAL_V1");
    }
}
