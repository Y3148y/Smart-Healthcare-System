package com.aihospital.knowledge.domain;

import com.aihospital.shared.model.Models.Evidence;
import java.util.List;
import java.util.Map;

/** Lexical engines have different score scales; callers must not compare their raw scores. */
public interface LexicalSearch {
    record Result(List<Evidence> evidence, String status,
                  Map<String, List<Bm25Retriever.TermContribution>> explanations) {
        public Result {
            evidence = List.copyOf(evidence);
            explanations = Map.copyOf(explanations);
        }
    }
    Result search(String query, List<Evidence> approvedCorpus, int limit, double memoryMinimum);
    /** Publication gate: must check all expected IDs and content, not just query success. */
    default boolean verify(List<Evidence> approvedCorpus) { return false; }
}
