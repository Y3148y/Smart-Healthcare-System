package com.aihospital.observation.api;

import com.aihospital.observation.application.AdminOverviewService;
import com.aihospital.observation.domain.CallLogStore;
import com.aihospital.shared.model.Models.CallLog;
import com.aihospital.shared.model.Models.Dashboard;
import com.aihospital.shared.security.RoleGuard;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/admin")
public class AdminObservationController {
    private final AdminOverviewService overview;
    private final CallLogStore calls;
    private final RoleGuard guard;
    public AdminObservationController(AdminOverviewService overview, CallLogStore calls, RoleGuard guard) {
        this.overview = overview; this.calls = calls; this.guard = guard;
    }
    @GetMapping("/dashboard") public Dashboard dashboard(@RequestHeader(value = "Authorization", required = false) String auth) {
        guard.require(auth, "ADMIN"); return overview.dashboard();
    }
    @GetMapping("/calls") public List<CallLog> calls(@RequestHeader(value = "Authorization", required = false) String auth) {
        guard.require(auth, "ADMIN"); return calls.calls();
    }
}
