package com.aihospital.triage.api;

import com.aihospital.shared.security.RoleGuard;
import com.aihospital.triage.domain.TriageRecords.HumanReview;
import com.aihospital.review.application.HumanReviewService;
import com.aihospital.review.domain.ReviewRecords.Summary;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;


@RestController
@RequestMapping("/api/admin/human-reviews")
public class AdminHumanReviewController {
    private final RoleGuard guard;
    private final HumanReviewService service;

    public AdminHumanReviewController(RoleGuard guard, HumanReviewService service) {
        this.guard = guard;
        this.service = service;
    }

    @GetMapping public List<HumanReview> list(@RequestHeader(value = "Authorization", required = false) String auth) {
        guard.require(auth, "ADMIN");
        return service.all();
    }

    @PatchMapping("/{id}") public HumanReview change(@PathVariable String id, @RequestBody StatusChange request,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        guard.require(auth, "ADMIN");
        return service.change(id,request==null?null:request.status());
    }

    @GetMapping("/{id}/summary") public Summary summary(@PathVariable String id,
            @RequestHeader(value="Authorization",required=false)String auth){guard.require(auth,"ADMIN");return service.summary(id);}
    @ExceptionHandler(ResponseStatusException.class) public org.springframework.http.ResponseEntity<Map<String,String>> rejected(ResponseStatusException ex){return org.springframework.http.ResponseEntity.status(ex.getStatusCode()).body(Map.of("message",ex.getReason()==null?"请求被拒绝":ex.getReason()));}

    private record StatusChange(String status) {}
}
