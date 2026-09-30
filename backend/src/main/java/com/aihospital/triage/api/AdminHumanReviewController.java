package com.aihospital.triage.api;

import com.aihospital.shared.security.RoleGuard;
import com.aihospital.triage.domain.TriageRecords.HumanReview;
import com.aihospital.triage.infrastructure.mybatis.TriageMapper;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import static com.aihospital.shared.infrastructure.mybatis.RowValues.*;

@RestController
@RequestMapping("/api/admin/human-reviews")
public class AdminHumanReviewController {
    private final RoleGuard guard;
    private final TriageMapper mapper;

    public AdminHumanReviewController(RoleGuard guard, TriageMapper mapper) {
        this.guard = guard;
        this.mapper = mapper;
    }

    @GetMapping public List<HumanReview> list(@RequestHeader(value = "Authorization", required = false) String auth) {
        guard.require(auth, "ADMIN");
        return mapper.humanReviews().stream().map(this::fromRow).toList();
    }

    @PatchMapping("/{id}") public HumanReview change(@PathVariable String id, @RequestBody StatusChange request,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        guard.require(auth, "ADMIN");
        if (request == null || !("ACCEPTED".equals(request.status()) || "CLOSED".equals(request.status())))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "状态只可设为 ACCEPTED 或 CLOSED");
        if (mapper.updateHumanReview(id, request.status()) != 1)
            throw new ResponseStatusException(HttpStatus.CONFLICT, "申请不存在或已处理");
        return mapper.humanReviews().stream().filter(row -> id.equals(string(row, "id")))
                .map(this::fromRow).findFirst().orElseThrow();
    }

    private HumanReview fromRow(Map<String, Object> row) {
        return new HumanReview(string(row, "id"), string(row, "session_id"), string(row, "patient_id"),
                string(row, "reason"), string(row, "status"), dateTime(row, "created_at"));
    }
    private record StatusChange(String status) {}
}
