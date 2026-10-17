package com.aihospital.shared.diagnostics;

import com.aihospital.tools.api.*;
import com.aihospital.tools.application.HospitalToolExecutor;
import com.aihospital.tools.domain.ToolRegistry;
import com.aihospital.shared.model.Models.ToolTrace;
import com.aihospital.shared.security.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

class ToolEntryOriginTest {
    private String admin(){return "Bearer "+new JwtService().issue("admin","ADMIN");}
    private HospitalToolExecutor executor(String origin){
        var executor=mock(HospitalToolExecutor.class);
        when(executor.execute(anyString(),anyMap())).thenAnswer(call->{
            assertEquals(origin,InvocationContext.current());
            return new HospitalToolExecutor.Execution(Map.of("status","SYNTHETIC"),new ToolTrace("department_search","synthetic","","",1,true,""));
        });return executor;
    }
    @Test void adminTrialAndLegacyHttpFacadeAreNotMislabelledAsMcp(){
        var executor=executor("ADMIN");var registry=mock(ToolRegistry.class);var guard=new RoleGuard();
        new AdminToolController(registry,guard,executor).run("department_search",Map.of("department","synthetic","initiator","AGENT"),admin());
        assertEquals("UNKNOWN",InvocationContext.current());
        new DemoToolHttpController(registry,null,null,executor,guard).invoke("department_search",Map.of("department","synthetic"),admin());
        assertEquals("UNKNOWN",InvocationContext.current());
    }
    @Test void jsonRpcToolCallHasMcpOriginButKeepsExistingAdminAuthorization(){
        var json=new ObjectMapper();var executor=executor("MCP");
        var controller=new McpProtocolController(new RoleGuard(),mock(ToolRegistry.class),executor,json);
        var request=json.valueToTree(Map.of("jsonrpc","2.0","id",1,"method","tools/call","params",Map.of(
                "name","department_search","arguments",Map.of("department","synthetic"),"_meta",Map.of(
                "io.modelcontextprotocol/protocolVersion","2026-07-28","io.modelcontextprotocol/clientInfo",Map.of("name","synthetic"),
                "io.modelcontextprotocol/clientCapabilities",Map.of()))));
        var response=controller.handle(request,admin(),null,"2026-07-28","tools/call","department_search");
        assertEquals(200,response.getStatusCode().value());assertEquals("UNKNOWN",InvocationContext.current());
        assertThrows(org.springframework.web.server.ResponseStatusException.class,()->controller.handle(request,
                "Bearer "+new JwtService().issue("synthetic","PATIENT"),null,"2026-07-28","tools/call","department_search"));
        verify(executor,times(1)).execute(anyString(),anyMap());assertEquals("UNKNOWN",InvocationContext.current());
    }
    @Test void failedAdminExecutionStillRestoresScope(){
        var executor=mock(HospitalToolExecutor.class);when(executor.execute(anyString(),anyMap())).thenThrow(new IllegalStateException("Synthetic failure"));
        var controller=new AdminToolController(mock(ToolRegistry.class),new RoleGuard(),executor);
        assertThrows(IllegalStateException.class,()->controller.run("department_search",Map.of("department","synthetic"),admin()));
        assertEquals("UNKNOWN",InvocationContext.current());
    }
}
