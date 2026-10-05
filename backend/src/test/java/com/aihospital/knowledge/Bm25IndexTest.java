package com.aihospital.knowledge;

import com.aihospital.knowledge.domain.Bm25Retriever;
import com.aihospital.shared.model.Models.Evidence;
import org.junit.jupiter.api.Test;
import java.util.ArrayList;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class Bm25IndexTest {
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
