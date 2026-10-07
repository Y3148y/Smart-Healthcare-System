package com.aihospital.catalog;

import com.aihospital.catalog.domain.DoctorDirectory;
import com.aihospital.catalog.infrastructure.demo.DemoDoctorDirectory;
import com.aihospital.observation.infrastructure.demo.InMemoryCallLogStore;
import com.aihospital.tools.application.HospitalToolExecutor;
import com.aihospital.tools.infrastructure.demo.InMemoryToolRegistry;
import org.junit.jupiter.api.Test;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class DepartmentToolFailureTest {
    @Test void dependencyFailureIsNotReportedAsMissingDepartment() {
        var directory = mock(DoctorDirectory.class);
        when(directory.departmentAvailability("test")).thenThrow(new IllegalStateException("unavailable"));
        var executor = new HospitalToolExecutor(new InMemoryToolRegistry(), directory, null, null, new InMemoryCallLogStore());
        var result = executor.execute("department_search", Map.of("department", "test"));
        assertFalse(result.trace().success());
        assertNull(result.data());
        assertEquals("工具执行失败", result.trace().outcome());
        assertTrue(result.trace().error().contains("IllegalStateException"));
        verify(directory, never()).doctors(any());
    }
    @Test void disabledToolDoesNotQueryDirectory() {
        var registry = new InMemoryToolRegistry();
        registry.toggle("department_search");
        var directory = mock(DoctorDirectory.class);
        var executor = new HospitalToolExecutor(registry, directory, null, null, new InMemoryCallLogStore());
        assertFalse(executor.execute("department_search", Map.of("department", "test")).trace().success());
        verifyNoInteractions(directory);
    }
    @Test void missingArgumentDoesNotQueryDirectory() {
        var directory = mock(DoctorDirectory.class);
        var executor = new HospitalToolExecutor(new InMemoryToolRegistry(), directory, null, null, new InMemoryCallLogStore());
        assertFalse(executor.execute("department_search", Map.of()).trace().success());
        verifyNoInteractions(directory);
    }
    @Test void demoAdapterSuppliesActualCatalogueStatus() {
        var directory = new DemoDoctorDirectory();
        assertTrue(directory.departmentAvailability("骨科").exists());
        assertFalse(directory.departmentAvailability("missing").exists());
    }
}
