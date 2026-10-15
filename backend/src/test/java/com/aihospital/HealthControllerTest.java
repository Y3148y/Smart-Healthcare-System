package com.aihospital;

import com.aihospital.shared.api.HealthController;
import org.junit.jupiter.api.Test;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class HealthControllerTest {
    @Test void livenessDoesNotDependOnDatabaseOrExternalModels() {
        var dataSource = mock(DataSource.class);
        assertEquals(Map.of("status", "UP"), new HealthController(dataSource).live());
        verifyNoInteractions(dataSource);
    }
    @Test void readinessChecksConnectionAndReleasesIt() throws Exception {
        var dataSource = mock(DataSource.class);
        var connection = mock(Connection.class);
        when(dataSource.getConnection()).thenReturn(connection);
        when(connection.isValid(2)).thenReturn(true);
        var response = new HealthController(dataSource).ready();
        assertEquals(200, response.getStatusCode().value());
        verify(connection).close();
    }
    @Test void readinessFailureHasNoConnectionOrExceptionDetails() throws Exception {
        var dataSource = mock(DataSource.class);
        when(dataSource.getConnection()).thenThrow(new SQLException("synthetic sensitive connection details"));
        var response = new HealthController(dataSource).ready();
        assertEquals(503, response.getStatusCode().value());
        assertEquals(Map.of("status", "DOWN"), response.getBody());
    }
}
