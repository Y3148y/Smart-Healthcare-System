package com.aihospital.knowledge;

import java.util.ArrayList;
import java.util.List;

/**
 * Small, frozen engineering-regression gate derived from the measured 2026-10-03 Qwen rerank
 * development and held-out sets. These criteria are not clinical quality or safety thresholds.
 */
final class RagQualityGate {
    private static final double EPSILON = 1e-9;

    record Sample(String id, boolean answerable, double recallAt3, double reciprocalRank,
                  boolean forbiddenHit, boolean unanswerableFalsePositive) {}
    record Summary(int samples, int answerableSamples, double meanRecallAt3, double meanMrr,
                   int forbiddenHits, int unanswerableFalsePositives, List<String> failures) {}

    private RagQualityGate() {}

    static List<String> failures(List<Sample> samples) {
        List<String> failures = new ArrayList<>();
        if (samples == null || samples.isEmpty()) return List.of("dataset has no samples");
        long answerable = samples.stream().filter(Sample::answerable).count();
        if (answerable == 0) failures.add("dataset has no answerable samples");
        for (Sample sample : samples) {
            String id = sample.id() == null || sample.id().isBlank() ? "<missing-id>" : sample.id();
            if (sample.answerable()) {
                if (!Double.isFinite(sample.recallAt3()) || sample.recallAt3() < 1.0 - EPSILON)
                    failures.add(id + " did not retrieve all labeled relevant documents at K=3");
                if (!Double.isFinite(sample.reciprocalRank()) || sample.reciprocalRank() < 1.0 - EPSILON)
                    failures.add(id + " did not rank a relevant document first");
            }
            if (sample.forbiddenHit()) failures.add(id + " selected a labeled forbidden document");
            if (sample.unanswerableFalsePositive()) failures.add(id + " returned evidence for an unanswerable query");
        }
        return List.copyOf(failures);
    }

    static Summary summarize(List<Sample> samples) {
        List<String> failures = failures(samples);
        if (samples == null || samples.isEmpty()) return new Summary(0, 0, 0, 0, 0, 0, failures);
        List<Sample> answerable = samples.stream().filter(Sample::answerable).toList();
        double recall = answerable.isEmpty() ? 0 : answerable.stream().mapToDouble(Sample::recallAt3).average().orElse(0);
        double mrr = answerable.isEmpty() ? 0 : answerable.stream().mapToDouble(Sample::reciprocalRank).average().orElse(0);
        int forbidden = (int) samples.stream().filter(Sample::forbiddenHit).count();
        int unanswerableFalsePositives = (int) samples.stream().filter(Sample::unanswerableFalsePositive).count();
        return new Summary(samples.size(), answerable.size(), recall, mrr, forbidden,
                unanswerableFalsePositives, failures);
    }
}
