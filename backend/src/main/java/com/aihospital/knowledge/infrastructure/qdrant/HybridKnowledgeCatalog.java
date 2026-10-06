package com.aihospital.knowledge.infrastructure.qdrant;

import com.aihospital.knowledge.domain.Bm25Retriever;
import com.aihospital.knowledge.domain.KnowledgeCatalog;
import com.aihospital.knowledge.domain.KnowledgeDocumentStore;
import com.aihospital.knowledge.domain.StoredKnowledgeDocument;
import com.aihospital.knowledge.infrastructure.demo.InMemoryKnowledgeCatalog;
import com.aihospital.shared.model.Models.Evidence;
import com.aihospital.shared.model.Models.KnowledgeDocument;
import jakarta.annotation.PostConstruct;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import java.util.*;

/** BM25 + Qdrant, one RRF implementation, followed by optional/required reranking. */
@Primary
@Component
public class HybridKnowledgeCatalog implements KnowledgeCatalog {
    private final InMemoryKnowledgeCatalog local;
    private final QdrantSemanticIndex semantic;
    private final BailianReranker reranker;
    private final KnowledgeDocumentStore documentStore;
    @Value("${ai.retrieval.min-score:0.28}") private double lexicalMinScore = 0.28;
    @Value("${ai.retrieval.semantic-min-score:0.45}") private double semanticMinScore = 0.45;
    @Value("${ai.retrieval.rrf-k:60}") private int rrfK = 60;
    @Value("${ai.retrieval.candidate-limit:20}") private int candidateLimit = 20;
    @Value("${ai.retrieval.require-semantic:false}") private boolean requireSemantic;
    @Value("${ai.retrieval.require-rerank:false}") private boolean requireRerank;
    private volatile String lastMode = "NOT_QUERIED";
    private volatile Bm25Retriever.Index lexicalIndex;
    private synchronized Bm25Retriever.Index lexicalIndex(List<Evidence> corpus) {
        if (lexicalIndex == null || !lexicalIndex.matches(corpus)) lexicalIndex = Bm25Retriever.index(corpus);
        return lexicalIndex;
    }
    private final java.util.Deque<RetrievalEvent> events = new java.util.ArrayDeque<>();
    public record RetrievalEvent(String id, java.time.Instant time, String mode, String semanticStatus,
                                 String rerankStatus, int candidates, int selected, long elapsedMs) {}
    @Override public synchronized List<RetrievalEvent> retrievalEvents() { return List.copyOf(events); }
    private synchronized void recordEvent(Report report) {
        events.addFirst(new RetrievalEvent(UUID.randomUUID().toString(), java.time.Instant.now(), report.mode(),
                report.semanticStatus(), report.rerankStatus(), report.candidates().size(),
                report.retrieval().evidence().size(), report.elapsedMs()));
        while (events.size() > 100) events.removeLast();
    }
    @Override public DocumentDetail documentDetails(String id) {
        var detail = local.documentDetails(id);
        boolean approved = "READY".equals(detail.document().status());
        int count = approved ? semantic.indexedCount(detail.segments()) : 0;
        String state = !approved ? "NOT_APPROVED" : !semantic.configured() ? "NOT_CONFIGURED"
                : count == detail.segments().size() ? "INDEXED_IN_PROCESS" : count == 0 ? "NOT_INDEXED" : "PARTIAL";
        return new DocumentDetail(detail.document(), detail.source(), detail.segments(), state, count,
                "索引计数是本进程已向 Qdrant 校验或成功写入的片段数，不是持续健康检查；资料和审批状态仍为内存演示。");
    }
    @Override public Map<String, String> syncIndex() {
        boolean success = semantic.ensureIndexed(local.approvedCorpus());
        return Map.of("status", semantic.status(), "success", String.valueOf(success));
    }

    public HybridKnowledgeCatalog(InMemoryKnowledgeCatalog local, QdrantSemanticIndex semantic) {
        this(local, semantic, null, null);
    }
    public HybridKnowledgeCatalog(InMemoryKnowledgeCatalog local, QdrantSemanticIndex semantic, BailianReranker reranker) {
        this(local, semantic, reranker, null);
    }
    @Autowired
    public HybridKnowledgeCatalog(InMemoryKnowledgeCatalog local, QdrantSemanticIndex semantic,
                                  BailianReranker reranker, KnowledgeDocumentStore documentStore) {
        this.local = local; this.semantic = semantic; this.reranker = reranker; this.documentStore = documentStore;
    }
    @PostConstruct public void validateConfiguration() {
        if (documentStore != null) {
            List<StoredKnowledgeDocument> documents = documentStore.loadOrSeed(local.persistedDocuments());
            local.restorePersistedDocuments(documents);
            for (StoredKnowledgeDocument stored : documents) {
                int currentChunkCount = local.persistedDocument(stored.id()).chunkCount();
                if (currentChunkCount != stored.chunkCount()) documentStore.refreshChunkCount(stored.id(), currentChunkCount);
            }
        }
        if (requireSemantic && !semantic.configured()) throw new IllegalStateException("rag-live requires embedding configuration");
        if (requireRerank && (reranker == null || !reranker.configured()))
            throw new IllegalStateException("rag-live requires rerank configuration");
    }
    @Override public List<KnowledgeDocument> documents() { return local.documents(); }
    @Override public List<com.aihospital.knowledge.domain.KnowledgeChunk> documentChunks(String id) {
        return local.documentChunks(id);
    }
    @Override public synchronized KnowledgeDocument addDocument(String title, String body) {
        KnowledgeDocument document = local.addDocument(title, body);
        if (documentStore != null) {
            try { documentStore.insert(local.persistedDocument(document.id())); }
            catch (RuntimeException failure) { local.removeAfterPersistenceFailure(document.id()); throw failure; }
        }
        return document;
    }
    @Override public KnowledgeDocument approveDocument(String id) {
        if (documentStore != null) {
            var pending = local.documentDetails(id);
            documentStore.approve(id, pending.segments().size(), java.time.LocalDateTime.now());
        }
        var approved = local.approveDocument(id);
        semantic.ensureIndexed(local.approvedCorpus());
        return approved;
    }
    @Override public String retrievalMode() { return lastMode; }
    @Override public Map<String, String> runtimeDetails() {
        return Map.of("mode", lastMode, "embeddingConfigured", String.valueOf(semantic.configured()),
                "semanticStatus", semantic.status(), "rerankConfigured", String.valueOf(reranker != null && reranker.configured()),
                "requireSemantic", String.valueOf(requireSemantic), "requireRerank", String.valueOf(requireRerank),
                "embeddingModel", semantic.modelName(), "rerankModel", reranker == null ? "" : reranker.modelName(),
                "note", "mode is the last completed retrieval; it is not a live health probe");
    }
    public record FusionRecord(String title, String source, String excerpt, int lexicalRank, Double lexicalScore,
                               int semanticRank, Double semanticScore, double fusedScore, Double rerankScore, boolean kept, String reason) {}
    public record Report(Retrieval retrieval, String mode, String semanticStatus, String rerankStatus,
                         List<FusionRecord> candidates, long elapsedMs) {}
    @Override public List<Evidence> search(String query) { return retrieve(query, 5, lexicalMinScore).evidence(); }
    @Override public Report retrievalDetails(String query) { return inspect(query, 3, lexicalMinScore); }
    @Override public Retrieval retrieve(String query, int maxResults, double minimumScore) {
        return inspect(query, maxResults, minimumScore).retrieval();
    }

    /** Request-local diagnostics: never store patient evidence in a shared lastFusion field. */
    public Report inspect(String query, int maxResults, double minimumScore) {
        if (query == null || query.isBlank() || query.length() > 12000)
            return new Report(new Retrieval(List.of(), false, "检索问题为空或过长"), "INVALID_QUERY", semantic.status(), "SKIPPED", List.of(), 0);
        long start = System.nanoTime();
        int limit = Math.max(1, Math.min(maxResults, 8)), depth = Math.max(limit, Math.min(100, candidateLimit));
        var corpus = local.approvedCorpus();
        List<Evidence> lexical = lexicalIndex(corpus).search(query, depth, minimumScore);
        boolean indexed = semantic.ensureIndexed(corpus);
        var semanticReport = indexed ? semantic.searchDetailed(query, depth, semanticMinScore)
                : new QdrantSemanticIndex.SearchResult(List.of(), semantic.status());
        List<Evidence> dense = semanticReport.evidence();
        boolean denseOk = "READY".equals(semanticReport.status());
        Set<String> active = new HashSet<>(); corpus.forEach(e -> active.add(Bm25Retriever.key(e)));
        dense = dense.stream().filter(e -> active.contains(Bm25Retriever.key(e))).toList();
        Map<String, Candidate> candidates = new TreeMap<>();
        for (int i = 0; i < lexical.size(); i++) {
            Evidence hit = lexical.get(i);
            Candidate c = candidates.computeIfAbsent(Bm25Retriever.key(hit), k -> new Candidate(hit));
            c.lexicalRank = i + 1; c.lexicalScore = hit.score();
        }
        for (int i = 0; i < dense.size(); i++) {
            Evidence hit = dense.get(i);
            Candidate c = candidates.computeIfAbsent(Bm25Retriever.key(hit), k -> new Candidate(hit));
            c.semanticRank = i + 1; c.semanticScore = hit.score();
        }
        int k = Math.max(1, rrfK);
        for (Candidate c : candidates.values()) c.fused = (c.lexicalRank == 0 ? 0 : 1.0 / (k + c.lexicalRank))
                + (c.semanticRank == 0 ? 0 : 1.0 / (k + c.semanticRank));
        var ranked = candidates.values().stream().sorted(Comparator.comparingDouble((Candidate c) -> c.fused)
                .reversed().thenComparing(c -> Bm25Retriever.key(c.evidence))).toList();
        List<Evidence> pool = ranked.stream().limit(depth).map(c -> new Evidence(c.evidence.title(), c.evidence.source(),
                c.evidence.excerpt(), c.fused)).toList();
        var reranked = reranker == null ? new BailianReranker.Result(List.of(), "NOT_CONFIGURED") : reranker.rank(query, pool, limit);
        boolean blocked = requireSemantic && !denseOk || requireRerank && !Set.of("OK", "EMPTY").contains(reranked.status());
        List<Evidence> selected = blocked ? List.of() : "OK".equals(reranked.status()) ? reranked.evidence()
                : pool.stream().limit(limit).toList();
        String mode = blocked ? "DEPENDENCY_BLOCKED" : denseOk ? "HYBRID_QDRANT" : "LOCAL_BM25";
        mode += "OK".equals(reranked.status()) ? "_RERANKED" : "EMPTY".equals(reranked.status()) ? "_NO_CANDIDATES" : "_UNRERANKED";
        lastMode = mode;
        Set<String> kept = new HashSet<>(); selected.forEach(e -> kept.add(Bm25Retriever.key(e)));
        Map<String, BailianReranker.Ranked> rerankRecords = new HashMap<>();
        reranked.ranking().forEach(r -> rerankRecords.put(Bm25Retriever.key(r.evidence()), r));
        List<FusionRecord> records = ranked.stream().map(c -> new FusionRecord(c.evidence.title(), c.evidence.source(),
                c.evidence.excerpt(), c.lexicalRank, c.lexicalScore, c.semanticRank, c.semanticScore, c.fused,
                rerankRecords.containsKey(Bm25Retriever.key(c.evidence)) ? rerankRecords.get(Bm25Retriever.key(c.evidence)).evidence().score() : null,
                kept.contains(Bm25Retriever.key(c.evidence)), blocked ? "required_dependency_unavailable"
                : rerankRecords.containsKey(Bm25Retriever.key(c.evidence)) ? rerankRecords.get(Bm25Retriever.key(c.evidence)).reason()
                : kept.contains(Bm25Retriever.key(c.evidence)) ? "selected_without_rerank" : "beyond_candidate_or_final_top_k")).toList();
        var report = new Report(new Retrieval(selected, !selected.isEmpty(), mode + "; candidates=" + ranked.size()
                + "; selected=" + selected.size() + "; retrieval relevance does not prove answer support"), mode,
                semanticReport.status(), reranked.status(), records, (System.nanoTime() - start) / 1_000_000);
        recordEvent(report);
        return report;
    }
    private static final class Candidate {
        final Evidence evidence;
        int lexicalRank, semanticRank;
        Double lexicalScore, semanticScore;
        double fused;
        Candidate(Evidence evidence) { this.evidence = evidence; }
    }
}
