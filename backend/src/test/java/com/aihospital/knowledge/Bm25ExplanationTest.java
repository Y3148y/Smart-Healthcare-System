package com.aihospital.knowledge;

import com.aihospital.knowledge.domain.Bm25Retriever;
import com.aihospital.shared.model.Models.Evidence;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class Bm25ExplanationTest {
    @Test void warningMentionExplainsMatchWithoutClaimingTopicCoverage() {
        var warning = new Evidence("头晕资料", "local", "若出现剧烈头痛应急诊。", 0);
        var index = Bm25Retriever.index(List.of(warning));
        var hit = index.search("头痛", 3, 0).get(0);
        var terms = index.explain("头痛", hit);
        assertEquals(List.of("头痛"), terms.stream().map(Bm25Retriever.TermContribution::term).toList());
        assertEquals(hit.score(), terms.stream().mapToDouble(Bm25Retriever.TermContribution::score).sum(), 1e-12);
        assertEquals(1, terms.get(0).documentFrequency());
        assertEquals(1, terms.get(0).termFrequency());
    }

    @Test void explanationIsStableAndUnknownEvidenceHasNoInventedScore() {
        var evidence = new Evidence("alpha beta", "local", "alpha beta beta", 0);
        var index = Bm25Retriever.index(List.of(evidence));
        var first = index.explain("beta alpha alpha", evidence);
        assertEquals(first, index.explain("alpha beta", evidence));
        assertEquals(List.of("alpha", "beta"), first.stream().map(Bm25Retriever.TermContribution::term).toList());
        assertTrue(index.explain("alpha", new Evidence("other", "local", "alpha", 0)).isEmpty());
        assertTrue(index.explain("unmatched", evidence).isEmpty());
    }
}
