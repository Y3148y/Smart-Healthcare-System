package com.aihospital.knowledge.infrastructure.demo;

import com.aihospital.knowledge.domain.KnowledgeCatalog;
import com.aihospital.shared.model.Models.Evidence;
import com.aihospital.shared.model.Models.KnowledgeDocument;
import org.springframework.core.io.Resource;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

@Component
public class InMemoryKnowledgeCatalog implements KnowledgeCatalog {
    private static final List<String> RETRIEVAL_TERMS = List.of(
            "急诊", "红旗症状", "胸痛", "胸闷", "大汗", "冷汗", "呼吸困难", "意识障碍", "昏迷", "晕厥",
            "脑卒中", "中风", "面瘫", "口角歪斜", "肢体无力", "言语障碍", "咳嗽", "咳痰", "喘息", "哮喘",
            "腹痛", "反酸", "恶心", "呕吐", "腹泻", "便秘", "消化不良", "消化道出血", "呼吸内科", "消化内科",
            "头晕", "眩晕", "头痛", "想吐", "神经内科", "耳鼻喉科", "骨折", "摔断", "摔伤",
            "嗓子疼", "咽痛", "喉咙痛", "痛经", "经期腹痛", "妇科", "骨科");
    private final Map<String, KnowledgeDocument> documents = new ConcurrentHashMap<>();
    private final Map<String, String> sources = new ConcurrentHashMap<>();
    private final AtomicInteger ids = new AtomicInteger(100);

    public InMemoryKnowledgeCatalog() {
        add("呼吸内科就诊指引", "咳嗽、咳痰、气喘等呼吸道症状可优先咨询呼吸内科。出现持续胸痛、呼吸困难、意识障碍应立即前往急诊。", "内置演示资料");
        add("胸痛急诊处置路径", "突发或持续胸痛、明显呼吸困难、晕厥属于红旗症状，系统不应以普通门诊建议延误紧急就医。", "内置演示资料");
        add("消化内科就诊指引", "反复腹痛、反酸、恶心、消化不良等常见症状可由消化内科评估。", "内置演示资料");
        loadBundledKnowledge();
    }

    @Override public List<KnowledgeDocument> documents() {
        return documents.values().stream().sorted(Comparator.comparing(KnowledgeDocument::updatedAt).reversed()).toList();
    }
    @Override public KnowledgeDocument addDocument(String title, String body) {
        return add(title, body, "管理员录入/本地上传");
    }
    private KnowledgeDocument add(String title, String body, String source) {
        String id = "kd" + ids.incrementAndGet();
        KnowledgeDocument document = new KnowledgeDocument(id, title, body, Math.max(1, body.length()/80),
                "READY", LocalDateTime.now());
        documents.put(id, document);
        sources.put(id, source);
        return document;
    }
    @Override public List<Evidence> search(String query) {
        String normalized = query == null ? "" : query.trim();
        return documents.values().stream().map(document -> new ScoredDocument(document, relevance(document, normalized)))
                .filter(item -> normalized.isBlank() || item.score() > 0)
                .sorted(Comparator.comparingDouble(ScoredDocument::score).reversed()
                        .thenComparing(item -> item.document().title()))
                .limit(5)
                .map(item -> new Evidence(item.document().title(),
                        sources.getOrDefault(item.document().id(), item.document().title()),
                        item.document().body(), item.score())).toList();
    }
    private double relevance(KnowledgeDocument document, String query) {
        if (query.isBlank()) return 0.70;
        String haystack = document.title() + " " + document.body();
        int hits = 0;
        for (String term : RETRIEVAL_TERMS) if (query.contains(term) && haystack.contains(term)) hits++;
        if (hits == 0 && query.length() <= 12 && haystack.contains(query)) hits = 1;
        double authorityBoost = sources.getOrDefault(document.id(), "").startsWith("https://") ? 0.12 : 0;
        return hits == 0 ? 0 : Math.min(0.995, 0.76 + hits * 0.055 + authorityBoost);
    }
    private void loadBundledKnowledge() {
        try {
            Resource[] resources = new PathMatchingResourcePatternResolver().getResources("classpath*:knowledge/*.md");
            Arrays.sort(resources, Comparator.comparing(resource -> Optional.ofNullable(resource.getFilename()).orElse("")));
            for (Resource resource : resources) {
                String markdown = new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
                String title = markdown.lines().filter(line -> line.startsWith("# "))
                        .map(line -> line.substring(2).trim()).findFirst()
                        .orElse(Optional.ofNullable(resource.getFilename()).orElse("医学知识资料"));
                String source = markdown.lines().filter(line -> line.startsWith("来源：") || line.startsWith("补充来源："))
                        .map(line -> line.substring(line.indexOf('：') + 1).trim())
                        .reduce((left, right) -> left + " | " + right).orElse("官方公开资料");
                String body = markdown.lines().filter(line -> !line.startsWith("# ") && !line.startsWith("来源：")
                        && !line.startsWith("补充来源：") && !line.startsWith("主题："))
                        .reduce("", (left, line) -> left + (left.isBlank() ? "" : "\n") + line).trim();
                add(title, body, source);
            }
        } catch (IOException ex) {
            throw new IllegalStateException("加载内置医学知识资料失败", ex);
        }
    }
    private record ScoredDocument(KnowledgeDocument document, double score) {}
}
