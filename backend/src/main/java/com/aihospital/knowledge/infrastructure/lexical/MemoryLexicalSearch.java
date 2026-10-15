package com.aihospital.knowledge.infrastructure.lexical;

import com.aihospital.knowledge.domain.Bm25Retriever;
import com.aihospital.knowledge.domain.LexicalSearch;
import com.aihospital.shared.model.Models.Evidence;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import java.util.HashMap;
import java.util.List;

@Component
@ConditionalOnProperty(name = "ai.retrieval.lexical-backend", havingValue = "memory")
public class MemoryLexicalSearch implements LexicalSearch {
    private Bm25Retriever.Index index;

    @Override public synchronized Result search(String query, List<Evidence> corpus, int limit, double minimum) {
        if (index == null || !index.matches(corpus)) index = Bm25Retriever.index(corpus);
        var hits = index.search(query, limit, minimum);
        var explanations = new HashMap<String, List<Bm25Retriever.TermContribution>>();
        hits.forEach(hit -> explanations.put(Bm25Retriever.key(hit), index.explain(query, hit)));
        return new Result(hits, "MEMORY_BM25_READY", explanations);
    }
}
