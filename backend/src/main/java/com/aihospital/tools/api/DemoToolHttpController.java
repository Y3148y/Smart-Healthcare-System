package com.aihospital.tools.api;

import com.aihospital.shared.model.Models.*;
import com.aihospital.catalog.application.DoctorCatalogService;
import com.aihospital.knowledge.domain.KnowledgeCatalog;
import com.aihospital.tools.domain.ToolRegistry;
import org.springframework.web.bind.annotation.*;
import java.util.*;

/** Demo HTTP tool facade; this is not yet a standards-compliant MCP server. */
@RestController
@RequestMapping("/mcp")
public class DemoToolHttpController {
    private final ToolRegistry registry;
    private final KnowledgeCatalog knowledge;
    private final DoctorCatalogService catalog;
    public DemoToolHttpController(ToolRegistry registry, KnowledgeCatalog knowledge, DoctorCatalogService catalog) {
        this.registry=registry;this.knowledge=knowledge;this.catalog=catalog;
    }
    @GetMapping("/tools") public List<Tool> tools(){return registry.tools();}
    @PostMapping("/tools/{code}") public Object invoke(@PathVariable String code,@RequestBody(required=false) Map<String,String> input){
        String q=input==null?"":input.getOrDefault("query","");
        return switch(code){case "doctor_schedule_search" -> catalog.doctors(input==null?null:input.get("department")); case "medical_knowledge_retrieve" -> knowledge.search(q); default -> Map.of("tool",code,"result","演示 MCP 工具执行成功","query",q);};
    }
}
