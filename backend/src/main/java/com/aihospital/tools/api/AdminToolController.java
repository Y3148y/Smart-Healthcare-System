package com.aihospital.tools.api;

import com.aihospital.shared.model.Models.Tool;
import com.aihospital.shared.security.RoleGuard;
import com.aihospital.tools.domain.ToolRegistry;
import com.aihospital.tools.application.HospitalToolExecutor;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/admin/tools")
public class AdminToolController {
    private final ToolRegistry tools;
    private final RoleGuard guard;
    private final HospitalToolExecutor executor;
    public AdminToolController(ToolRegistry tools, RoleGuard guard, HospitalToolExecutor executor) {
        this.tools = tools; this.guard = guard; this.executor = executor;
    }
    @GetMapping public List<Tool> tools(@RequestHeader(value = "Authorization", required = false) String auth) {
        guard.require(auth, "ADMIN"); return tools.tools();
    }
    @PatchMapping("/{code}/toggle") public Tool toggle(@PathVariable String code,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        guard.require(auth, "ADMIN"); return tools.toggle(code);
    }
    @PostMapping("/{code}/run") public HospitalToolExecutor.Execution run(@PathVariable String code,
            @RequestBody(required = false) Map<String, String> input,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        guard.require(auth, "ADMIN");
        return executor.execute(code, input);
    }
}
