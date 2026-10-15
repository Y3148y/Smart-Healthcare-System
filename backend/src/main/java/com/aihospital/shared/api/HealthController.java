package com.aihospital.shared.api;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import javax.sql.DataSource;
import java.util.Map;

/** Minimal public status: no model call, patient data, connection details or exception text. */
@RestController
public class HealthController {
    private final DataSource dataSource;
    public HealthController(DataSource dataSource) { this.dataSource = dataSource; }

    @GetMapping("/api/health/live") public Map<String, String> live() { return Map.of("status", "UP"); }

    @GetMapping("/api/health/ready") public ResponseEntity<Map<String, String>> ready() {
        try (var connection = dataSource.getConnection()) {
            if (connection.isValid(2)) return ResponseEntity.ok(Map.of("status", "UP"));
        } catch (Exception unavailable) { /* Never disclose JDBC errors or credentials. */ }
        return ResponseEntity.status(503).body(Map.of("status", "DOWN"));
    }
}
