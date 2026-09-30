package com.aihospital.tools.api;

import com.aihospital.shared.model.Models.*;
import com.aihospital.catalog.application.DoctorCatalogService;
import com.aihospital.knowledge.domain.KnowledgeCatalog;
import com.aihospital.tools.domain.ToolRegistry;
import com.aihospital.tools.application.HospitalToolExecutor;
import com.aihospital.shared.security.RoleGuard;
import org.springframework.web.bind.annotation.*;
import java.util.*;

/** Demo HTTP tool facade; this is not yet a standards-compliant MCP server. */
@RestController
@RequestMapping("/mcp")
public class DemoToolHttpController {
    private final ToolRegistry registry;
    private final KnowledgeCatalog knowledge;
    private final DoctorCatalogService catalog;
    private final HospitalToolExecutor executor;
    private final RoleGuard guard;
    public DemoToolHttpController(ToolRegistry registry, KnowledgeCatalog knowledge, DoctorCatalogService catalog,
                                  HospitalToolExecutor executor, RoleGuard guard) {
        this.registry=registry;this.knowledge=knowledge;this.catalog=catalog;this.executor=executor;this.guard=guard;
    }
    @GetMapping("/tools") public List<Tool> tools(@RequestHeader(value="Authorization",required=false) String auth){
        guard.require(auth,"ADMIN");return registry.tools();
    }
    @PostMapping("/tools/{code}") public HospitalToolExecutor.Execution invoke(@PathVariable String code,
            @RequestBody(required=false) Map<String,String> input,
            @RequestHeader(value="Authorization",required=false) String auth){
        guard.require(auth,"ADMIN");return executor.execute(code,input);
    }
}
