package com.aihospital.knowledge;

import com.aihospital.knowledge.domain.Bm25Retriever;
import com.aihospital.shared.model.Models.Evidence;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class Bm25IndexTest {
    @Test void sparseQueryVisitsOnlyMatchingPostings() {
        var corpus = new ArrayList<Evidence>();
        for (int i = 0; i < 1000; i++) corpus.add(new Evidence("entry", "s" + i, "common term" + i, 0));
        corpus.add(new Evidence("rare", "target", "uniqueword", 0));
        var index = Bm25Retriever.index(corpus);
        var result = index.searchDetailed("uniqueword", 10, 0);
        assertEquals(1001, result.corpusSize());
        assertEquals(1, result.candidateCount());
        assertEquals(1, result.postingEntriesVisited());
        assertEquals("target", result.hits().get(0).source());
        assertEquals(0, index.searchDetailed("missingword", 10, 0).candidateCount());
        assertEquals(0, index.searchDetailed("", 10, 0).postingEntriesVisited());
    }

    @Test void postingUnionPreservesScanScoresThresholdsAndTieOrder() {
        var corpus = List.of(new Evidence("alpha", "z", "beta beta", 0),
                new Evidence("alpha", "a", "beta beta", 0),
                new Evidence("gamma", "c", "gamma gamma gamma", 0));
        var index = Bm25Retriever.index(corpus);
        // Independent full-scan reference: lengths 3, 3, 4; alpha df=2 tf=1, beta df=2 tf=2.
        double idf = Math.log(1 + 1.5 / 2.5);
        double norm = 1.2 * (.25 + .75 * 3 / (10.0 / 3));
        double expected = idf * 2.2 / (1 + norm) + idf * 2 * 2.2 / (2 + norm);
        var result = index.searchDetailed("beta alpha", 10, 0);
        assertEquals(2, result.candidateCount());
        assertEquals(4, result.postingEntriesVisited());
        assertEquals(List.of("a", "z"), result.hits().stream().map(Evidence::source).toList());
        result.hits().forEach(hit -> assertEquals(expected, hit.score(), 1e-12));
        assertTrue(index.search("alpha beta", 10, expected + .0001).isEmpty());
        assertEquals(1, index.search("alpha beta", 1, 0).size());
    }

    private final Evidence chest = new Evidence("胸痛", "source-a", "胸痛需要评估", 0);
    private final Evidence headache = new Evidence("头痛", "source-b", "头痛持续两天", 0);

    @Test void scoreMatchesIndependentBm25Calculation() {
        var index = Bm25Retriever.index(List.of(new Evidence("alpha", "a", "alpha", 0),
                new Evidence("beta", "b", "beta", 0)));
        // N=2, df=1, tf=2, document length=average length=2, k1=1.2, b=.75.
        double expected = Math.log(2) * 2 * 2.2 / (2 + 1.2);
        assertEquals(expected, index.search("alpha", 3, 0).get(0).score(), 1e-12);
    }

    @Test void reusableIndexPreservesRankingAndScores() {
        var corpus = List.of(chest, headache);
        var index = Bm25Retriever.index(corpus);
        for (String query : List.of("胸痛", "头痛两天", "没有头痛", "", "unknown"))
            assertEquals(Bm25Retriever.search(query, corpus, 3, .28), index.search(query, 3, .28));
        assertEquals(chest.title(), index.search("胸痛", 3, .28).get(0).title());
    }

    @Test void snapshotDoesNotChangeWhenSourceListChanges() {
        var corpus = new ArrayList<>(List.of(chest));
        var index = Bm25Retriever.index(corpus);
        corpus.add(headache);
        assertFalse(index.matches(corpus));
        assertTrue(index.search("头痛", 3, 0).isEmpty());
        assertFalse(Bm25Retriever.index(corpus).search("头痛", 3, 0).isEmpty());
        assertTrue(index.matches(List.of(chest)));
    }

    @Test void revisionsAndWithdrawalInvalidateSnapshot() {
        var index = Bm25Retriever.index(List.of(chest, headache));
        assertFalse(index.matches(List.of(chest)));
        assertFalse(index.matches(List.of(chest, new Evidence("头痛", "source-b", "修订正文", 0))));
        assertTrue(Bm25Retriever.index(List.of()).search("胸痛", 3, 0).isEmpty());
    }
}
