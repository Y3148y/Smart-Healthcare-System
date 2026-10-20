package com.aihospital.knowledge.api;

import com.aihospital.knowledge.application.KnowledgePublicationService;
import com.aihospital.shared.security.RoleGuard;
import org.springframework.web.bind.annotation.*;
import java.util.Map;
import java.util.List;

@RestController
public class KnowledgePublicationController {
    private final KnowledgePublicationService publications;
    private final RoleGuard guard;
    public KnowledgePublicationController(KnowledgePublicationService publications,RoleGuard guard) {this.publications=publications;this.guard=guard;}
    public record Version(Long expectedVersion) {
        public Version {if(expectedVersion==null||expectedVersion<0)throw new IllegalArgumentException("明确的发布版本必填");}
    }
    @GetMapping("/api/admin/knowledge/releases")
    public Map<String,Object> list(@RequestHeader(value="Authorization",required=false)String auth) {
        guard.require(auth,"ADMIN");return Map.of("state",publications.state(),"releases",publications.list());
    }
    @GetMapping("/api/admin/knowledge/{id}/history")
    public List<Map<String,Object>> history(@PathVariable String id,@RequestHeader(value="Authorization",required=false)String auth) {
        guard.require(auth,"ADMIN");return publications.history(id);
    }
    @PostMapping("/api/admin/knowledge/releases")
    public Map<String,Object> publish(@RequestBody Version version,@RequestHeader(value="Authorization",required=false)String auth) {
        guard.require(auth,"ADMIN");return publications.buildAndPublish(version.expectedVersion());
    }
    @PostMapping("/api/admin/knowledge/releases/{id}/activate")
    public Map<String,Object> rollback(@PathVariable String id,@RequestBody Version version,@RequestHeader(value="Authorization",required=false)String auth) {
        guard.require(auth,"ADMIN");return publications.rollback(id,version.expectedVersion());
    }
    @DeleteMapping("/api/admin/knowledge/releases/{id}")
    public Map<String,Object> delete(@PathVariable String id,@RequestBody Version version,@RequestHeader(value="Authorization",required=false)String auth) {
        guard.require(auth,"ADMIN");publications.deleteExpired(id,version.expectedVersion());return Map.of("status","DELETED");
    }
}
