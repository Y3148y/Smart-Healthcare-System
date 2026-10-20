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

/** Stateless Streamable HTTP tools server, with compatibility for the former private metadata contract. */
@RestController
public class McpProtocolController {
    private static final String VERSION = "2026-07-28";
    private static final String STANDARD_VERSION = "2025-11-25";
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
        try(var invocation=com.aihospital.shared.diagnostics.InvocationContext.open(com.aihospital.shared.diagnostics.InvocationContext.Origin.MCP)){
            if (!VERSION.equals(version)) return standard(body, version);
            return dispatch(body,version,methodHeader,nameHeader);
        }
    }

    @RequestMapping(path="/mcp", method={RequestMethod.GET, RequestMethod.DELETE})
    public ResponseEntity<Void> noServerStream(
            @RequestHeader(value="Authorization",required=false) String auth,
            @RequestHeader(value="Origin",required=false) String origin) {
        if (!allowedOrigin(origin)) return ResponseEntity.status(HttpStatus.FORBIDDEN).build();
        guard.require(auth, "ADMIN");
        // Stateless server: no unsolicited SSE stream or server-side sessions to delete.
        return ResponseEntity.status(HttpStatus.METHOD_NOT_ALLOWED).allow(org.springframework.http.HttpMethod.POST).build();
    }

    private boolean allowedOrigin(String origin) {
        return origin == null || origin.matches("https?://(localhost|127\\.0\\.0\\.1)(:[0-9]{1,5})?");
    }

    private ResponseEntity<Map<String,Object>> standard(JsonNode body, String version) {
        if (body == null || !body.isObject() || !body.path("jsonrpc").isTextual() || !"2.0".equals(body.path("jsonrpc").asText())
                || !body.path("method").isTextual())
            return rpcError(HttpStatus.BAD_REQUEST, body, -32600, "Invalid JSON-RPC request");
        if (version != null && !STANDARD_VERSION.equals(version))
            return rpcError(HttpStatus.BAD_REQUEST, body, -32600, "Unsupported MCP protocol version");
        String method = body.path("method").asText();
        JsonNode params = body.path("params");
        boolean notification = !body.has("id");
        if (!notification && !(body.path("id").isTextual() || body.path("id").isIntegralNumber()))
            return rpcError(HttpStatus.BAD_REQUEST, body, -32600, "Invalid request id");
        if ("initialize".equals(method)) {
            if (notification || !params.isObject() || !params.path("protocolVersion").isTextual()
                    || !params.path("capabilities").isObject() || !params.path("clientInfo").isObject()
                    || !params.path("clientInfo").path("name").isTextual()
                    || !params.path("clientInfo").path("version").isTextual())
                return rpcError(HttpStatus.OK, body, -32602, "Invalid initialization parameters");
            return success(body, Map.of("protocolVersion", STANDARD_VERSION,
                    "capabilities", Map.of("tools", Map.of("listChanged", false)),
                    "serverInfo", Map.of("name", "healthcare-readonly-tools", "version", "1.0.0")));
        }
        if (!STANDARD_VERSION.equals(version))
            return rpcError(HttpStatus.BAD_REQUEST, body, -32600, "Unsupported MCP protocol version");
        if (notification) {
            if (method.startsWith("notifications/")) return ResponseEntity.accepted().build();
            return rpcError(HttpStatus.BAD_REQUEST, body, -32600, "Request id required");
        }
        if ("ping".equals(method)) return success(body, Map.of());
        if ("tools/list".equals(method)) {
            if (!params.isMissingNode() && !params.isNull()
                    && (!params.isObject() || params.has("cursor")))
                return rpcError(HttpStatus.OK, body, -32602, "Pagination is not supported");
            var tools = registry.tools().stream().filter(Tool::enabled).map(tool -> Map.<String,Object>of(
                    "name", tool.code(), "title", tool.name(), "description", tool.description(),
                    "inputSchema", schema(tool.code()), "annotations", Map.of("readOnlyHint", true, "destructiveHint", false)))
                    .toList();
            return success(body, Map.of("tools", tools));
        }
        if (!"tools/call".equals(method)) return rpcError(HttpStatus.OK, body, -32601, "Method not found");
        String name = params.path("name").asText("");
        if (!params.isObject() || !params.path("name").isTextual()
                || registry.tools().stream().noneMatch(tool -> tool.enabled() && tool.code().equals(name)))
            return rpcError(HttpStatus.OK, body, -32602, "Unknown or disabled tool");
        String key = name.equals("department_search") || name.equals("doctor_schedule_search") ? "department" : "query";
        JsonNode arguments = params.path("arguments");
        if (!arguments.isObject() || arguments.size() != 1 || !arguments.path(key).isTextual()
                || arguments.path(key).asText().isBlank() || arguments.path(key).asText().length() > 2200)
            return rpcError(HttpStatus.OK, body, -32602, "Tool arguments do not match inputSchema");
        var execution = executor.execute(name, Map.of(key, arguments.path(key).asText()));
        if (!execution.trace().success()) return success(body, Map.of("isError", true,
                "content", List.of(Map.of("type", "text", "text", "Tool execution failed"))));
        try {
            return success(body, Map.of("isError", false,
                    "content", List.of(Map.of("type", "text", "text", json.writeValueAsString(execution.data())))));
        } catch (JsonProcessingException invalid) {
            return success(body, Map.of("isError", true,
                    "content", List.of(Map.of("type", "text", "text", "Tool result serialization failed"))));
        }
    }

    private ResponseEntity<Map<String,Object>> rpcError(HttpStatus status, JsonNode body, int code, String message) {
        Map<String,Object> response = new LinkedHashMap<>();
        response.put("jsonrpc", "2.0");
        JsonNode id = body == null ? null : body.get("id");
        response.put("id", id == null || !(id.isTextual() || id.isIntegralNumber()) ? null
                : id.isTextual() ? id.asText() : id.numberValue());
        response.put("error", Map.of("code", code, "message", message));
        return ResponseEntity.status(status).body(response);
    }
    private ResponseEntity<Map<String,Object>> dispatch(JsonNode body,String version,String methodHeader,String nameHeader){
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
        result.put("content", List.of(Map.of("type","text","text", execution.trace().success() ? serialized : "Tool execution failed")));
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
