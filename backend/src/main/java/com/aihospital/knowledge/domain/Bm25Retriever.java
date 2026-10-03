package com.aihospital.knowledge.domain;

import com.aihospital.shared.model.Models.Evidence;
import java.util.*;

/** Corpus-based lexical retrieval; no medical dictionary or source-authority bonus. */
public final class Bm25Retriever {
    private Bm25Retriever() {}

    public static List<Evidence> search(String query, List<Evidence> corpus, int limit, double minimum) {
        Set<String> queryTerms = tokens(query).keySet();
        if (queryTerms.isEmpty() || corpus.isEmpty()) return List.of();
        List<Map<String, Integer>> documents = corpus.stream()
                .map(e -> tokens(e.title() + " " + e.excerpt())).toList();
        Map<String, Integer> frequencies = new HashMap<>();
        for (var document : documents) for (String term : document.keySet()) frequencies.merge(term, 1, Integer::sum);
        double average = documents.stream().mapToInt(Bm25Retriever::length).average().orElse(1);
        List<Evidence> hits = new ArrayList<>();
        for (int i = 0; i < documents.size(); i++) {
            Map<String, Integer> document = documents.get(i);
            double score = 0;
            for (String term : queryTerms) {
                int tf = document.getOrDefault(term, 0);
                if (tf == 0) continue;
                double idf = Math.log(1 + (corpus.size() - frequencies.get(term) + 0.5) / (frequencies.get(term) + 0.5));
                score += idf * tf * 2.2 / (tf + 1.2 * (0.25 + 0.75 * length(document) / average));
            }
            Evidence e = corpus.get(i);
            if (score > 0 && score >= minimum) hits.add(new Evidence(e.title(), e.source(), e.excerpt(), score));
        }
        return hits.stream().sorted(Comparator.comparingDouble(Evidence::score).reversed()
                .thenComparing(Bm25Retriever::key)).limit(Math.max(1, Math.min(limit, 100))).toList();
    }

    public static String key(Evidence e) { return e.source() + "\n" + e.title() + "\n" + e.excerpt(); }
    private static int length(Map<String, Integer> terms) { return terms.values().stream().mapToInt(Integer::intValue).sum(); }
    private static Map<String, Integer> tokens(String value) {
        Map<String, Integer> terms = new HashMap<>();
        if (value == null) return terms;
        for (String part : value.toLowerCase(Locale.ROOT).split("[^\\p{L}\\p{N}]+")) {
            if (part.matches("[a-z0-9]+")) { if (!part.isEmpty()) terms.merge(part, 1, Integer::sum); continue; }
            int[] points = part.codePoints().toArray();
            for (int n = 2; n <= 3; n++) for (int i = 0; i + n <= points.length; i++)
                terms.merge(new String(points, i, n), 1, Integer::sum);
        }
        return terms;
    }
}
