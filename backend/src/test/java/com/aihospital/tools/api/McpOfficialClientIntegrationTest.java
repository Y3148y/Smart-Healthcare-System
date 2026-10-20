package com.aihospital.tools.api;

import com.aihospital.shared.security.JwtService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import java.nio.file.Path;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** Official SDK integration, random local port and isolated database; no model requests. */
@EnabledIfSystemProperty(named="ai.test.mcp-client", matches="true")
@SpringBootTest(webEnvironment=SpringBootTest.WebEnvironment.RANDOM_PORT, properties={
        "server.address=127.0.0.1",
        "spring.datasource.url=jdbc:h2:mem:mcp_official_client_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"})
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
class McpOfficialClientIntegrationTest {
    @LocalServerPort int port;

    @Test void officialClientNegotiatesDiscoversAndCallsWithNegativeSecurityCases() throws Exception {
        var directory = Path.of(System.getProperty("user.dir")).toAbsolutePath().getParent().resolve("tools/mcp-client");
        assertTrue(directory.resolve("node_modules/@modelcontextprotocol/sdk/package.json").toFile().isFile(),
                "Run npm ci in tools/mcp-client before this explicit integration group");
        var process = new ProcessBuilder("node", "interop.mjs").directory(directory.toFile()).redirectErrorStream(true);
        process.environment().put("MCP_TEST_BASE_URL", "http://127.0.0.1:" + port + "/mcp");
        process.environment().put("MCP_TEST_TOKEN", new JwtService().issue("synthetic-mcp-admin", "ADMIN"));
        process.environment().put("MCP_TEST_PATIENT_TOKEN", new JwtService().issue("synthetic-mcp-patient", "PATIENT"));
        var running = process.start();
        try {
            assertTrue(running.waitFor(30, TimeUnit.SECONDS), "Official MCP client did not terminate");
            String output = new String(running.getInputStream().readNBytes(8000), java.nio.charset.StandardCharsets.UTF_8);
            assertEquals(0, running.exitValue(), output);
            assertTrue(output.contains("\"passed\":true"), "Missing client completion proof");
        } finally {
            if (running.isAlive()) running.destroyForcibly();
        }
    }
}
