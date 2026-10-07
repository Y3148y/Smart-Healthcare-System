package com.aihospital.triage.api;

import com.aihospital.shared.security.RoleGuard;
import com.aihospital.triage.domain.TriageRecords.HumanReview;
import com.aihospital.review.application.HumanReviewService;
import com.aihospital.review.domain.ReviewRecords.Summary;
import com.aihospital.review.application.ReviewAccessPolicy;
import com.aihospital.review.domain.ReviewAccessAudit;
import java.time.LocalDateTime;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;


@RestController
@RequestMapping("/api/admin/human-reviews")
public class AdminHumanReviewController {
    private final RoleGuard guard;
    private final HumanReviewService service;
    private final ReviewAccessPolicy access;
    private final ReviewAccessAudit audit;

    public AdminHumanReviewController(RoleGuard guard, HumanReviewService service, ReviewAccessPolicy access, ReviewAccessAudit audit) {
        this.guard = guard;
        this.service = service;
        this.access = access;
        this.audit = audit;
    }

    public record QueueItem(String id, String status, LocalDateTime createdAt, boolean summaryAccessible) {}
    private QueueItem item(HumanReview request, String subject) {
        return new QueueItem(request.id(), request.status(), request.createdAt(), access.mayRead(subject));
    }
    @GetMapping public List<QueueItem> list(@RequestHeader(value = "Authorization", required = false) String auth) {
        var claims = guard.require(auth, "ADMIN");
        return service.all().stream().map(r -> item(r, claims.subject())).toList();
    }

    @PatchMapping("/{id}") public QueueItem change(@PathVariable String id, @RequestBody StatusChange request,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        var claims = guard.require(auth, "ADMIN");
        return item(service.change(id,request==null?null:request.status()), claims.subject());
    }

    @GetMapping("/{id}/summary") public Summary summary(@PathVariable String id,
            @RequestHeader(value="Authorization",required=false)String auth){
        var claims = guard.require(auth,"ADMIN");
        if (!access.mayRead(claims.subject())) {
            audit.record(claims.subject(), boundedId(id), "DENIED");
            throw new ResponseStatusException(org.springframework.http.HttpStatus.FORBIDDEN, "当前账号未获得患者摘要读取授权");
        }
        var result = service.summary(id);
        // Persist before serializing text; an audit failure must not expose the summary.
        audit.record(claims.subject(), boundedId(id), "READ");
        return result;
    }
    private static String boundedId(String id) { return id.substring(0, Math.min(64, id.length())); }
    @ExceptionHandler(ResponseStatusException.class) public org.springframework.http.ResponseEntity<Map<String,String>> rejected(ResponseStatusException ex){return org.springframework.http.ResponseEntity.status(ex.getStatusCode()).body(Map.of("message",ex.getReason()==null?"请求被拒绝":ex.getReason()));}

    private record StatusChange(String status) {}
}
