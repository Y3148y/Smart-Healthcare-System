package com.aihospital.catalog;

import com.aihospital.catalog.application.CatalogManagementService;
import com.aihospital.catalog.domain.CatalogRecords.*;
import com.aihospital.catalog.domain.DepartmentAvailability.Status;
import com.aihospital.catalog.infrastructure.mybatis.CatalogMapper;
import com.aihospital.catalog.infrastructure.mybatis.SlotMapper;
import com.aihospital.tools.application.HospitalToolExecutor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class DepartmentAvailabilityTest {
    @Autowired CatalogManagementService service;
    @Autowired HospitalToolExecutor tools;
    @Autowired CatalogMapper mapper;
    @Autowired SlotMapper slots;

    private Department department() {
        return service.saveDepartment(null, new DepartmentEdit("availability-" + UUID.randomUUID(), true));
    }
    private DoctorEdit edit(String departmentId, boolean enabled, String date, int total) {
        return new DoctorEdit("Test doctor", "医师", departmentId, enabled, date, "上午", total, 0);
    }
    private Map<?, ?> lookup(String department) {
        var execution = tools.execute("department_search", Map.of("department", department));
        assertTrue(execution.trace().success(), execution.trace().error());
        assertInstanceOf(Map.class, execution.data());
        var data = (Map<?, ?>) execution.data();
        assertEquals(data.get("message"), execution.trace().outcome());
        return data;
    }
    @Test void missingDepartmentIsAValidEmptyLookupNotAnExecutionFailure() {
        var data = lookup("missing-" + UUID.randomUUID());
        assertEquals(false, data.get("exists"));
        assertEquals(false, data.get("enabled"));
        assertEquals(Status.DEPARTMENT_NOT_CONFIGURED.name(), data.get("status"));
    }
    @Test void departmentWithoutDoctorsStillExists() {
        var department = department();
        var data = lookup(department.name());
        assertEquals(true, data.get("exists"));
        assertEquals(true, data.get("enabled"));
        assertEquals(Status.NO_ACTIVE_DOCTOR.name(), data.get("status"));
        assertEquals(0, data.get("activeDoctors"));
    }
    @Test void disabledDepartmentIsNotMissingEvenWhenDoctorExists() {
        var department = department();
        service.saveDoctor(null, edit(department.id(), true, LocalDate.now().plusDays(1).toString(), 2));
        service.saveDepartment(department.id(), new DepartmentEdit(department.name(), false));
        var data = lookup(department.name());
        assertEquals(true, data.get("exists"));
        assertEquals(false, data.get("enabled"));
        assertEquals(Status.DEPARTMENT_DISABLED.name(), data.get("status"));
        assertTrue(((java.util.List<?>) tools.execute("doctor_schedule_search", Map.of("department", department.name())).data()).isEmpty());
    }
    @Test void disabledDoctorsDoNotCountAsActive() {
        var department = department();
        service.saveDoctor(null, edit(department.id(), false, LocalDate.now().plusDays(1).toString(), 2));
        assertEquals(Status.NO_ACTIVE_DOCTOR.name(), lookup(department.name()).get("status"));
    }
    @Test void expiredScheduleDoesNotEraseDepartmentOrActiveDoctor() {
        var department = department();
        var doctor = service.saveDoctor(null, edit(department.id(), true, LocalDate.now().plusDays(1).toString(), 2));
        // Model an existing schedule aging past its date, not a new past-date admin request.
        mapper.updateDoctor(doctor.id(), edit(department.id(), true, LocalDate.now().minusDays(1).toString(), 2));
        var data = lookup(department.name());
        assertEquals(true, data.get("exists"));
        assertEquals(Status.NO_MATCHING_SCHEDULE.name(), data.get("status"));
        assertEquals(1, data.get("activeDoctors"));
        assertEquals(0, data.get("scheduledDoctors"));
    }
    @Test void depletedSlotsDoNotEraseDoctorOrSchedule() {
        var department = department();
        var doctor = service.saveDoctor(null, edit(department.id(), true, LocalDate.now().toString(), 1));
        assertEquals(Status.AVAILABLE.name(), lookup(department.name()).get("status"));
        assertEquals(1, slots.decrementAvailableSlot(doctor.id(), doctor.date()));
        var data = lookup(department.name());
        assertEquals(Status.NO_SLOTS.name(), data.get("status"));
        assertEquals(1, data.get("scheduledDoctors"));
        assertEquals(0, data.get("bookableDoctors"));
    }
    @Test void zeroCapacityAndMixedDoctorsAreHandledWithoutInferringMissingDepartment() {
        var department = department();
        service.saveDoctor(null, edit(department.id(), true, LocalDate.now().toString(), 0));
        assertEquals(Status.NO_SLOTS.name(), lookup(department.name()).get("status"));
        service.saveDoctor(null, edit(department.id(), true, LocalDate.now().plusDays(2).toString(), 3));
        var data = lookup(department.name());
        assertEquals(Status.AVAILABLE.name(), data.get("status"));
        assertEquals(2, data.get("activeDoctors"));
        assertEquals(1, data.get("bookableDoctors"));
    }
    @Test void renamedDepartmentUsesCurrentCatalogueNotHistoricalDoctorName() {
        var department = department();
        String renamed = "renamed-" + UUID.randomUUID();
        service.saveDepartment(department.id(), new DepartmentEdit(renamed, true));
        assertEquals(Status.DEPARTMENT_NOT_CONFIGURED.name(), lookup(department.name()).get("status"));
        assertEquals(Status.NO_ACTIVE_DOCTOR.name(), lookup(renamed).get("status"));
    }
}
