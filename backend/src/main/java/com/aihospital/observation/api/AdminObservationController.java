package com.aihospital.observation.api;

import com.aihospital.observation.application.AdminOverviewService;
import com.aihospital.observation.domain.CallLogStore;
import com.aihospital.shared.model.Models.CallLog;
import com.aihospital.shared.model.Models.Dashboard;
import com.aihospital.shared.security.RoleGuard;
import com.aihospital.triage.domain.NarrationModel;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/admin")
public class AdminObservationController {
    private final AdminOverviewService overview;
    private final CallLogStore calls;
    private final RoleGuard guard;
    private final NarrationModel narration;
    public AdminObservationController(AdminOverviewService overview, CallLogStore calls, RoleGuard guard, NarrationModel narration) {
        this.overview = overview; this.calls = calls; this.guard = guard; this.narration = narration;
    }
    @GetMapping("/dashboard") public Dashboard dashboard(@RequestHeader(value = "Authorization", required = false) String auth) {
        guard.require(auth, "ADMIN"); return overview.dashboard();
    }
    public List<TechnicalCallView> calls(String auth) { return calls(auth, null); }
    @GetMapping("/calls") public List<TechnicalCallView> calls(@RequestHeader(value = "Authorization", required = false) String auth,
            @RequestParam(required = false) String traceId) {
        guard.require(auth, "ADMIN");
        if (traceId == null) return calls.calls().stream().map(TechnicalCallView::from).toList();
        try {
            if (traceId.length() != 36) throw new IllegalArgumentException();
            traceId = java.util.UUID.fromString(traceId).toString();
        } catch (IllegalArgumentException invalid) {
            throw new org.springframework.web.server.ResponseStatusException(org.springframework.http.HttpStatus.BAD_REQUEST, "轮次标识格式无效");
        }
        return calls.calls(traceId).stream().map(TechnicalCallView::from).toList();
    }
    @GetMapping("/ai-runtime") public NarrationModel.RuntimeStatus aiRuntime(@RequestHeader(value = "Authorization", required = false) String auth) {
        guard.require(auth, "ADMIN"); return narration.runtimeStatus();
    }
}
