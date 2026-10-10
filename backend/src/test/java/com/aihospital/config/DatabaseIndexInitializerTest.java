package com.aihospital.config;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.ResultSet;

import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class DatabaseIndexInitializerTest {
    @Autowired DataSource dataSource;

    @Test
    void traceIndexIsCreatedDuringApplicationStartup() throws Exception {
        try (Connection connection = dataSource.getConnection();
             ResultSet indexes = connection.getMetaData().getIndexInfo(connection.getCatalog(), connection.getSchema(),
                     "agent_call_trace", false, false)) {
            boolean found = false;
            while (indexes.next()) {
                if ("idx_agent_call_trace_id".equalsIgnoreCase(indexes.getString("INDEX_NAME"))) found = true;
            }
            assertTrue(found, "trace lookup index should exist");
        }
    }
}
