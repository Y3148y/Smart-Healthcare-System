package com.aihospital.config;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.Locale;

/** Creates the trace lookup index idempotently on both MySQL and the H2 test database. */
@Component
public class DatabaseIndexInitializer implements ApplicationRunner {
    private static final String TABLE = "agent_call_trace";
    private static final String INDEX = "idx_agent_call_trace_id";
    private final DataSource dataSource;

    public DatabaseIndexInitializer(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public void run(ApplicationArguments args) throws Exception {
        try (Connection connection = dataSource.getConnection()) {
            DatabaseMetaData metadata = connection.getMetaData();
            String table = identifier(metadata, TABLE);
            String index = identifier(metadata, INDEX);
            if (indexExists(connection, metadata, table, index)) return;
            try (Statement statement = connection.createStatement()) {
                statement.execute("CREATE INDEX " + INDEX + " ON " + TABLE + " (trace_id)");
            } catch (SQLException exception) {
                // Another application instance may have created it after the metadata check.
                if (!isDuplicateIndex(exception)) throw exception;
            }
        }
    }

    private boolean indexExists(Connection connection, DatabaseMetaData metadata, String table, String index)
            throws SQLException {
        try (ResultSet indexes = metadata.getIndexInfo(connection.getCatalog(), connection.getSchema(), table,
                false, false)) {
            while (indexes.next()) {
                if (index.equalsIgnoreCase(indexes.getString("INDEX_NAME"))) return true;
            }
        }
        return false;
    }

    private String identifier(DatabaseMetaData metadata, String value) throws SQLException {
        if (metadata.storesLowerCaseIdentifiers()) return value.toLowerCase(Locale.ROOT);
        if (metadata.storesUpperCaseIdentifiers()) return value.toUpperCase(Locale.ROOT);
        return value;
    }

    private boolean isDuplicateIndex(SQLException exception) {
        for (SQLException current = exception; current != null; current = current.getNextException()) {
            if (current.getErrorCode() == 1061 || "42S11".equals(current.getSQLState())) return true;
        }
        return false;
    }
}
