package com.aihospital.knowledge.api;

import com.aihospital.knowledge.domain.KnowledgeCatalog;
import com.aihospital.shared.model.Models.Evidence;
import com.aihospital.shared.model.Models.KnowledgeDocument;
import com.aihospital.shared.security.RoleGuard;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@RestController
@RequestMapping("/api/admin/knowledge")
public class AdminKnowledgeController {
    private final KnowledgeCatalog knowledge;
    private final RoleGuard guard;
    public AdminKnowledgeController(KnowledgeCatalog knowledge, RoleGuard guard) {
        this.knowledge = knowledge; this.guard = guard;
    }
    @GetMapping public List<KnowledgeDocument> documents(@RequestHeader(value = "Authorization", required = false) String auth) {
        guard.require(auth, "ADMIN"); return knowledge.documents();
    }
    @GetMapping("/runtime") public Map<String,String> runtime(@RequestHeader(value = "Authorization", required = false) String auth) {
        guard.require(auth, "ADMIN"); return knowledge.runtimeDetails();
    }
    @GetMapping("/{id}/details") public KnowledgeCatalog.DocumentDetail details(@PathVariable String id,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        guard.require(auth, "ADMIN"); return knowledge.documentDetails(id);
    }
    @PostMapping("/index/sync") public Map<String, String> sync(@RequestHeader(value = "Authorization", required = false) String auth) {
        guard.require(auth, "ADMIN"); return knowledge.syncIndex();
    }
    @GetMapping("/{id}/chunks") public List<com.aihospital.knowledge.domain.KnowledgeChunk> chunks(@PathVariable String id,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        guard.require(auth, "ADMIN"); return knowledge.documentChunks(id);
    }
    @GetMapping("/retrieval-events") public List<?> events(@RequestHeader(value = "Authorization", required = false) String auth) {
        guard.require(auth, "ADMIN"); return knowledge.retrievalEvents();
    }
    @PostMapping public KnowledgeDocument add(@RequestBody Map<String, String> body,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        guard.require(auth, "ADMIN");
        return knowledge.addDocument(body.getOrDefault("title", "未命名知识资料"), body.getOrDefault("body", ""));
    }
    @PostMapping("/upload") public KnowledgeDocument upload(@RequestParam("file") MultipartFile file,
            @RequestHeader(value = "Authorization", required = false) String auth) throws IOException {
        guard.require(auth, "ADMIN");
        String filename = Optional.ofNullable(file.getOriginalFilename()).orElse("");
        String normalized = filename.toLowerCase(java.util.Locale.ROOT);
        if (!normalized.endsWith(".txt") && !normalized.endsWith(".md"))
            throw new IllegalArgumentException("只允许上传 TXT 或 Markdown 文件");
        if (file.isEmpty() || file.getSize() > 100_000)
            throw new IllegalArgumentException("知识资料必须为 1 至 100000 字节");
        String body;
        try {
            body = StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(java.nio.charset.CodingErrorAction.REPORT)
                    .onUnmappableCharacter(java.nio.charset.CodingErrorAction.REPORT)
                    .decode(java.nio.ByteBuffer.wrap(file.getBytes())).toString();
        } catch (java.nio.charset.CharacterCodingException ex) {
            throw new IllegalArgumentException("知识资料必须采用 UTF-8 编码", ex);
        }
        return knowledge.addDocument(filename, body);
    }
    @GetMapping("/search") public List<Evidence> search(@RequestParam(defaultValue = "") String q,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        guard.require(auth, "ADMIN"); return knowledge.search(q);
    }
    @GetMapping("/search/details") public Object searchDetails(@RequestParam(defaultValue = "") String q,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        guard.require(auth, "ADMIN");
        return knowledge.retrievalDetails(q);
    }
    @PostMapping("/{id}/approve") public KnowledgeDocument approve(@PathVariable String id,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        guard.require(auth, "ADMIN"); return knowledge.approveDocument(id);
    }
}
