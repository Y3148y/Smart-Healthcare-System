package com.aihospital.knowledge.infrastructure.demo;

import com.aihospital.knowledge.domain.KnowledgeCatalog;
import com.aihospital.shared.model.Models.Evidence;
import com.aihospital.shared.model.Models.KnowledgeDocument;
import com.aihospital.knowledge.domain.StoredKnowledgeDocument;
import com.aihospital.knowledge.domain.KnowledgeChunk;
import com.aihospital.knowledge.domain.MarkdownChunker;
import com.aihospital.knowledge.domain.KnowledgeMetadata;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * P0 local hybrid retriever: section-aware chunks + medical term matching +
 * character n-gram vector similarity.  The port is ready to be replaced by a
 * Qdrant embedding adapter; this implementation never calls itself semantic AI.
 */
@Component
public class InMemoryKnowledgeCatalog implements KnowledgeCatalog {
    private final MarkdownChunker chunker = new MarkdownChunker();
    private static final List<String> MEDICAL_TERMS = List.of(
            "急诊", "红旗症状", "胸痛", "胸闷", "大汗", "冷汗", "呼吸困难", "意识障碍", "昏迷", "晕厥",
            "脑卒中", "中风", "口角歪斜", "肢体无力", "言语障碍", "咳嗽", "咳痰", "喘息", "哮喘",
            "腹痛", "反酸", "恶心", "呕吐", "腹泻", "便秘", "消化不良", "消化道出血", "呼吸内科", "消化内科",
            "头晕", "眩晕", "头痛", "神经内科", "耳鼻喉科", "骨折", "摔断", "摔伤", "嗓子疼", "咽痛",
            "喉咙痛", "痛经", "经期腹痛", "妇科", "骨科", "吞咽", "抽搐", "出血", "高热",
            "流鼻涕", "鼻塞", "打喷嚏", "鼻部症状");
    private final Map<String, KnowledgeDocument> documents = new ConcurrentHashMap<>();
    private final Map<String, String> sources = new ConcurrentHashMap<>();
    private final Map<String, KnowledgeMetadata> metadata = new ConcurrentHashMap<>();
    private final Map<String, List<Chunk>> chunks = new ConcurrentHashMap<>();

    public InMemoryKnowledgeCatalog() {
        this(true);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public InMemoryKnowledgeCatalog(@Value("${ai.knowledge.bundled-approved:true}") boolean bundledApproved) {
        loadBundledKnowledge(bundledApproved);
    }

    @Override public List<KnowledgeDocument> documents() {
        return documents.values().stream().sorted(Comparator.comparing(KnowledgeDocument::updatedAt).reversed()).toList();
    }
    public List<Evidence> approvedCorpus() {
        return chunks.values().stream().flatMap(List::stream)
                .map(chunk -> new Evidence(chunk.title(), chunk.source(), chunk.text(), 1.0)).toList();
    }
    public synchronized List<StoredKnowledgeDocument> persistedDocuments() {
        return documents.values().stream().map(document -> new StoredKnowledgeDocument(document.id(), document.title(),
                document.body(), sources.get(document.id()), document.status(), document.chunks(), document.updatedAt(),
                metadata.get(document.id()))).toList();
    }

    public synchronized void restorePersistedDocuments(List<StoredKnowledgeDocument> storedDocuments) {
        if (storedDocuments == null || storedDocuments.isEmpty())
            throw new IllegalArgumentException("Persistent knowledge catalog must not be empty");
        if (storedDocuments.stream().anyMatch(stored -> "READY".equals(stored.status())
                && stored.metadata() != null && !stored.metadata().mayPublish()))
            throw new IllegalStateException("Published knowledge has unresolved usage permission");
        documents.clear();
        sources.clear();
        metadata.clear();
        chunks.clear();
        for (StoredKnowledgeDocument stored : storedDocuments) {
            if (stored.id() == null || stored.id().isBlank() || stored.title() == null || stored.title().isBlank()
                    || stored.body() == null || stored.body().isBlank() || stored.source() == null || stored.source().isBlank()
                    || stored.chunkCount() < 0 || stored.updatedAt() == null
                    || !Set.of("READY", "PENDING_REVIEW").contains(stored.status()))
                throw new IllegalStateException("Persistent knowledge document is incomplete");
            KnowledgeDocument document = new KnowledgeDocument(stored.id(), stored.title(), stored.body(),
                    stored.chunkCount(), stored.status(), stored.updatedAt());
            documents.put(stored.id(), document);
            sources.put(stored.id(), stored.source());
            if (stored.metadata() != null) metadata.put(stored.id(), stored.metadata());
            if ("READY".equals(stored.status())) {
                List<Chunk> indexed = chunk(stored.id(), stored.title(), stored.body(), stored.source());
                documents.put(stored.id(), new KnowledgeDocument(stored.id(), stored.title(), stored.body(),
                        indexed.size(), stored.status(), stored.updatedAt()));
                chunks.put(stored.id(), indexed);
            }
        }
    }

    public synchronized StoredKnowledgeDocument persistedDocument(String id) {
        KnowledgeDocument document = documents.get(id);
        if (document == null) throw new IllegalArgumentException("知识资料不存在");
        return new StoredKnowledgeDocument(document.id(), document.title(), document.body(), sources.get(id),
                document.status(), document.chunks(), document.updatedAt(), metadata.get(id));
    }

    public synchronized void removeAfterPersistenceFailure(String id) {
        documents.remove(id);
        sources.remove(id);
        metadata.remove(id);
        chunks.remove(id);
    }
    public String chunkingProfile() {
        return MarkdownChunker.VERSION + ";maxCodePoints=" + MarkdownChunker.MAX_LENGTH
                + ";overlapCodePoints=" + MarkdownChunker.OVERLAP
                + ";split=paragraph-and-heading;offset=source-body;normalize=CR-to-space-and-trim";
    }
    @Override public synchronized List<KnowledgeChunk> documentChunks(String id) {
        KnowledgeDocument document = documents.get(id);
        if (document == null) throw new IllegalArgumentException("知识资料不存在");
        return chunker.split(id, document.title(), sources.get(id), document.body());
    }
    @Override public synchronized DocumentDetail documentDetails(String id) {
        KnowledgeDocument document = documents.get(id);
        if (document == null) throw new IllegalArgumentException("知识资料不存在");
        String source = sources.get(id);
        var segments = chunk(id, document.title(), document.body(), source).stream()
                .map(c -> new Evidence(c.title(), c.source(), c.text(), 0)).toList();
        return new DocumentDetail(document, source, segments, "READY".equals(document.status())
                ? "NOT_CHECKED" : "NOT_APPROVED", 0, "片段预览不等于已进入向量索引；待审核资料不参与患者检索。");
    }
    @Override public KnowledgeDocument addDocument(String title, String body) {
        return add(title, body, "管理员录入/本地上传", false);
    }
    @Override public synchronized KnowledgeDocument addDocument(String title, String body, KnowledgeMetadata supplied) {
        if (supplied == null) throw new IllegalArgumentException("Structured knowledge metadata is required");
        KnowledgeDocument document = add(title, body, supplied.sourceLabel(), false);
        metadata.put(document.id(), supplied);
        return document;
    }

    @Override public synchronized KnowledgeDocument updatePendingMetadata(String id, KnowledgeMetadata supplied) {
        if (supplied == null) throw new IllegalArgumentException("Knowledge metadata is required");
        KnowledgeDocument current = documentDetails(id).document();
        if (!"PENDING_REVIEW".equals(current.status()))
            throw new IllegalArgumentException("Only pending knowledge metadata may be corrected");
        sources.put(id, supplied.sourceLabel());
        metadata.put(id, supplied);
        KnowledgeDocument updated = new KnowledgeDocument(id, current.title(), current.body(), current.chunks(),
                current.status(), LocalDateTime.now());
        documents.put(id, updated);
        return updated;
    }
    @Override public synchronized KnowledgeMetadata documentMetadata(String id) {
        if (!documents.containsKey(id)) throw new IllegalArgumentException("知识资料不存在");
        return metadata.get(id);
    }

    @Override public synchronized KnowledgeDocument approveDocument(String id) {
        KnowledgeDocument existing = documents.get(id);
        if (existing == null) throw new IllegalArgumentException("知识资料不存在");
        if (metadata.containsKey(id) && !metadata.get(id).mayPublish())
            throw new IllegalArgumentException("Knowledge usage permission must be resolved before approval");
        if ("READY".equals(existing.status())) return existing;
        String source = sources.get(id);
        List<Chunk> indexed = chunk(id, existing.title(), existing.body(), source);
        KnowledgeDocument approved = new KnowledgeDocument(id, existing.title(), existing.body(), indexed.size(),
                "READY", LocalDateTime.now());
        chunks.put(id, indexed);
        documents.put(id, approved);
        return approved;
    }

    @Override public synchronized KnowledgeDocument withdrawDocument(String id) {
        KnowledgeDocument current = documentDetails(id).document();
        if ("PENDING_REVIEW".equals(current.status())) return current;
        KnowledgeDocument withdrawn = new KnowledgeDocument(id, current.title(), current.body(), current.chunks(),
                "PENDING_REVIEW", LocalDateTime.now());
        chunks.remove(id);
        documents.put(id, withdrawn);
        return withdrawn;
    }

    private KnowledgeDocument add(String title, String body, String source, boolean bundledSeed) {
        if (title == null || title.isBlank() || body == null || body.isBlank() || title.length() > 160 || body.length() > 100_000)
            throw new IllegalArgumentException("知识资料标题或正文无效");
        String id = "kd" + java.util.UUID.randomUUID();
        List<Chunk> indexed = chunk(id, title, body, source);
        if (indexed.isEmpty()) throw new IllegalArgumentException("知识正文没有可检索内容，不能只提交标题");
        boolean approvedSource = bundledSeed;
        KnowledgeDocument document = new KnowledgeDocument(id, title, body, indexed.size(),
                approvedSource ? "READY" : "PENDING_REVIEW", LocalDateTime.now());
        documents.put(id, document);
        sources.put(id, source);
        if (approvedSource) chunks.put(id, indexed);
        return document;
    }

    /**
     * Department names and routing words carry no clinical meaning but would dominate the query:
     * {@code termScore} divides by {@code queryTerms.size()}, so a generic word such as
     * 急诊 sitting in every document's 主题 field adds to the denominator for all eleven
     * documents and dilutes the weight of the actual symptom. Callers append the candidate
     * department to the query by design, so 急诊科 reached the index on every lookup.
     *
     * <p><b>默认关闭，不是遗忘而是不可单独启用。</b> 清洗会移除噪声，同时暴露一个问题：
     * 全库最匹配的文档在「流鼻涕」这类真实查询下只能拿到 0.125，而检索阈值是 0.28。
     * 换言之今天的召回部分依赖噪声把分数抬过线。清洗一旦强制启用，召回会从「勉强能命中」
     * 变为「基本永不命中」。
     *
     * <p>标定问题与去噪一并待裁定——阈值与权重不能靠 11 份回归语料的经验拟合确定，
     * 那只是在拟合当前语料的分数分布。清洗代码已就绪，等阈值确定后由配置开启。
     */
    private static final Set<String> ROUTING_NOISE = Set.of(
            "急诊", "急诊科", "全科", "全科医学科", "红旗症状", "红旗", "预问诊", "分诊",
            "模拟号源", "人工导诊", "就医", "线下", "门诊", "挂号");

    @Value("${ai.retrieval.strip-routing-noise:false}")
    private boolean stripRoutingNoise = false;

    private String sanitizeQuery(String query) {
        String cleaned = query;
        for (String noise : ROUTING_NOISE) cleaned = cleaned.replace(noise, " ");
        return normalize(cleaned.replaceAll("\\s+", " ")).trim();
    }

    /** Whether routing-noise removal is active. Exposed so tests can pin both settings. */
    public boolean stripRoutingNoiseEnabled() { return stripRoutingNoise; }

    /**
     * Query handed to the semantic route. Sanitised whenever sanitising is enabled, so the
     * embedding never sees routing words as if they were symptoms; otherwise passed through so
     * the current behaviour is preserved while the calibration question is open.
     */
    public String sanitizeForSemantic(String query) {
        return stripRoutingNoise ? sanitizeQuery(query) : normalize(query).trim();
    }

    @Override public Retrieval retrieve(String query, int maxResults, double minimumScore) {
        String normalized = stripRoutingNoise ? sanitizeQuery(query) : normalize(query);
        if (normalized.isBlank()) return new Retrieval(List.of(), false, "检索问题为空，已拒绝生成无依据回答");
        Set<String> queryTerms = medicalTerms(normalized);
        Map<String, Double> queryVector = ngrams(normalized);
        List<Evidence> evidence = chunks.values().stream().flatMap(List::stream)
                .map(chunk -> new ScoredChunk(chunk, score(chunk, queryTerms, queryVector)))
                .filter(item -> item.score() >= minimumScore)
                .sorted(Comparator.comparingDouble(ScoredChunk::score).reversed())
                .limit(Math.max(1, Math.min(maxResults, 8)))
                .map(item -> new Evidence(item.chunk().title(), item.chunk().source(), item.chunk().text(), round(item.score())))
                .toList();
        boolean grounded = !evidence.isEmpty();
        return new Retrieval(evidence, grounded, grounded
                ? "已命中 " + evidence.size() + " 个达到阈值的知识片段"
                : "没有命中达到相关度阈值的知识片段，系统不得据此生成医学结论");
    }

    private double score(Chunk chunk, Set<String> queryTerms, Map<String, Double> queryVector) {
        Set<String> chunkTerms = medicalTerms(chunk.title() + " " + chunk.text());
        long hits = queryTerms.stream().filter(chunkTerms::contains).count();
        double termScore = queryTerms.isEmpty() ? 0 : (double) hits / queryTerms.size();
        double denseLexicalScore = cosine(queryVector, chunk.vector());
        double authority = chunk.source().startsWith("https://") ? 0.08 : 0.02;
        return Math.min(0.99, termScore * 0.62 + denseLexicalScore * 0.30 + authority);
    }

    private List<Chunk> chunk(String documentId, String title, String body, String source) {
        return chunker.split(documentId, title, source, body).stream()
                .map(item -> new Chunk(item.chunkId(), title, source, item.text(), ngrams(title + item.text())))
                .toList();
    }

    private Set<String> medicalTerms(String text) {
        Set<String> terms = new HashSet<>();
        for (String term : MEDICAL_TERMS) if (text.contains(term)) terms.add(term);
        return terms;
    }

    private Map<String, Double> ngrams(String text) {
        String compact = normalize(text).replaceAll("[\\p{P}\\p{S}\\s]+", "");
        Map<String, Double> vector = new HashMap<>();
        for (int i = 0; i + 1 < compact.length(); i++) vector.merge(compact.substring(i, i + 2), 1.0, Double::sum);
        return vector;
    }

    private double cosine(Map<String, Double> left, Map<String, Double> right) {
        if (left.isEmpty() || right.isEmpty()) return 0;
        double dot = 0, a = 0, b = 0;
        for (Map.Entry<String, Double> entry : left.entrySet()) {
            dot += entry.getValue() * right.getOrDefault(entry.getKey(), 0.0);
            a += entry.getValue() * entry.getValue();
        }
        for (double value : right.values()) b += value * value;
        return a == 0 || b == 0 ? 0 : dot / Math.sqrt(a * b);
    }

    private String normalize(String value) { return value == null ? "" : value.replace('\r', ' ').trim(); }
    private double round(double value) { return Math.round(value * 1000.0) / 1000.0; }

    private void loadBundledKnowledge(boolean bundledApproved) {
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver().getResources("classpath*:knowledge/*.md");
            Arrays.sort(resources, Comparator.comparing(resource -> Optional.ofNullable(resource.getFilename()).orElse("")));
            for (Resource resource : resources) {
                String markdown = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                String title = markdown.lines().filter(line -> line.startsWith("# ")).map(line -> line.substring(2).trim()).findFirst()
                        .orElse(Optional.ofNullable(resource.getFilename()).orElse("医学知识资料"));
                String source = markdown.lines().filter(line -> line.startsWith("来源：") || line.startsWith("补充来源："))
                        .map(line -> line.substring(line.indexOf('：') + 1).trim()).reduce((a, b) -> a + " | " + b).orElse("官方公开资料");
                String topics = markdown.lines().filter(line -> line.startsWith("主题："))
                        .map(line -> line.substring(line.indexOf('：') + 1).trim()).findFirst().orElse("");
                String body = markdown.lines().filter(line -> !line.startsWith("# ") && !line.startsWith("来源：")
                        && !line.startsWith("补充来源：") && !line.startsWith("主题："))
                        .reduce("", (left, line) -> left + (left.isBlank() ? "" : "\n") + line).trim();
                add(title, topics.isBlank() ? body : "主题：" + topics + "\n" + body, source, bundledApproved);
            }
        } catch (IOException ex) { throw new IllegalStateException("加载内置医学知识资料失败", ex); }
    }

    private record Chunk(String id, String title, String source, String text, Map<String, Double> vector) {}
    private record ScoredChunk(Chunk chunk, double score) {}
}
