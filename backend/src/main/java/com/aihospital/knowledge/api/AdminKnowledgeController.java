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
    @PostMapping public KnowledgeDocument add(@RequestBody Map<String, String> body,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        guard.require(auth, "ADMIN");
        return knowledge.addDocument(body.getOrDefault("title", "未命名知识资料"), body.getOrDefault("body", ""));
    }
    @PostMapping("/upload") public KnowledgeDocument upload(@RequestParam("file") MultipartFile file,
            @RequestHeader(value = "Authorization", required = false) String auth) throws IOException {
        guard.require(auth, "ADMIN");
        return knowledge.addDocument(Optional.ofNullable(file.getOriginalFilename()).orElse("上传资料"),
                new String(file.getBytes(), StandardCharsets.UTF_8));
    }
    @GetMapping("/search") public List<Evidence> search(@RequestParam(defaultValue = "") String q,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        guard.require(auth, "ADMIN"); return knowledge.search(q);
    }
}
