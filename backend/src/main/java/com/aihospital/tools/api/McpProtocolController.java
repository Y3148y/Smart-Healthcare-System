package com.aihospital.tools.api;

import com.aihospital.shared.model.Models.Tool;
import com.aihospital.shared.security.RoleGuard;
import com.aihospital.tools.application.HospitalToolExecutor;
import com.aihospital.tools.domain.ToolRegistry;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Stateless Streamable HTTP subset for the 2026-07-28 MCP tools protocol. */
@RestController
public class McpProtocolController {
    private static final String VERSION = "2026-07-28";
    private final RoleGuard guard;
    private final ToolRegistry registry;
    private final HospitalToolExecutor executor;
    private final ObjectMapper json;

    public McpProtocolController(RoleGuard guard, ToolRegistry registry, HospitalToolExecutor executor, ObjectMapper json) {
        this.guard = guard; this.registry = registry; this.executor = executor; this.json = json;
    }

    @PostMapping(path="/mcp", produces="application/json")
    public ResponseEntity<Map<String, Object>> handle(@RequestBody JsonNode body,
            @RequestHeader(value="Authorization",required=false) String auth,
            @RequestHeader(value="Origin",required=false) String origin,
            @RequestHeader(value="MCP-Protocol-Version",required=false) String version,
            @RequestHeader(value="Mcp-Method",required=false) String methodHeader,
            @RequestHeader(value="Mcp-Name",required=false) String nameHeader) {
        if (origin != null && !origin.matches("https?://(localhost|127\\.0\\.0\\.1)(:[0-9]{1,5})?"))
            return error(HttpStatus.FORBIDDEN, body, -32000, "Origin is not allowed");
        guard.require(auth, "ADMIN");
        String method = body.path("method").asText("");
        JsonNode params = body.path("params");
        JsonNode meta = params.path("_meta");
        if (!"2.0".equals(body.path("jsonrpc").asText()) || body.path("id").isMissingNode()
                || !VERSION.equals(version) || !VERSION.equals(meta.path("io.modelcontextprotocol/protocolVersion").asText())
                || !method.equals(methodHeader) || meta.path("io.modelcontextprotocol/clientInfo").isMissingNode()
                || meta.path("io.modelcontextprotocol/clientCapabilities").isMissingNode())
            return error(HttpStatus.BAD_REQUEST, body, -32600, "Invalid MCP request metadata or headers");
        if ("tools/list".equals(method)) {
            List<Map<String,Object>> list = registry.tools().stream().filter(Tool::enabled)
                    .map(tool -> Map.<String,Object>of("name",tool.code(),"title",tool.name(),
                            "description",tool.description(),"inputSchema",schema(tool.code()))).toList();
            return success(body, Map.of("resultType","complete","tools",list));
        }
        if (!"tools/call".equals(method)) return error(HttpStatus.NOT_FOUND, body, -32601, "Method not found");
        String name = params.path("name").asText("");
        if (name.isBlank() || !name.equals(nameHeader))
            return error(HttpStatus.BAD_REQUEST, body, -32600, "Mcp-Name does not match request body");
        JsonNode arguments = params.path("arguments");
        Map<String,String> input = new LinkedHashMap<>();
        if (arguments.isObject()) arguments.fields().forEachRemaining(entry -> input.put(entry.getKey(), entry.getValue().asText()));
        HospitalToolExecutor.Execution execution = executor.execute(name, input);
        String serialized;
        try { serialized = json.writeValueAsString(execution.data()); }
        catch (JsonProcessingException ex) { serialized = "null"; }
        Map<String,Object> result = new LinkedHashMap<>();
        result.put("resultType", "complete");
        result.put("content", List.of(Map.of("type","text","text", execution.trace().success() ? serialized : execution.trace().error())));
        result.put("structuredContent", execution.data());
        result.put("isError", !execution.trace().success());
        return success(body, result);
    }

    private Map<String,Object> schema(String code) {
        String key = code.equals("doctor_schedule_search") || code.equals("department_search") ? "department" : "query";
        return Map.of("type","object","properties",Map.of(key,Map.of("type","string")),
                "required",List.of(key),"additionalProperties",false);
    }
    private ResponseEntity<Map<String,Object>> success(JsonNode request, Object result) {
        return ResponseEntity.ok(Map.of("jsonrpc","2.0","id",id(request),"result",result));
    }
    private ResponseEntity<Map<String,Object>> error(HttpStatus status, JsonNode request, int code, String message) {
        return ResponseEntity.status(status).body(Map.of("jsonrpc","2.0","id",id(request),
                "error",Map.of("code",code,"message",message)));
    }
    private Object id(JsonNode request) {
        JsonNode value = request == null ? null : request.path("id");
        if (value == null || value.isMissingNode() || value.isNull()) return "";
        return value.isNumber() ? value.numberValue() : value.asText();
    }
}
