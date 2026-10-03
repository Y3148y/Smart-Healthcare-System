package com.aihospital.catalog;

import com.aihospital.catalog.application.CatalogManagementService;
import com.aihospital.catalog.application.DoctorCatalogService;
import com.aihospital.catalog.domain.CatalogRecords.*;
import com.aihospital.catalog.infrastructure.mybatis.SlotMapper;
import com.aihospital.shared.security.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest @AutoConfigureMockMvc
class CatalogManagementTest {
    @Autowired CatalogManagementService management;
    @Autowired DoctorCatalogService catalog;
    @Autowired SlotMapper slots;
    @Autowired MockMvc mvc;
    private Department department(){return management.saveDepartment(null,new DepartmentEdit("test-"+UUID.randomUUID(),true));}
    private DoctorEdit edit(String dep,int total){return new DoctorEdit("Test doctor","医师",dep,true,LocalDate.now().plusDays(2).toString(),"上午",total,20);}
    @Test void permissionsRequireAdmin() throws Exception {
        mvc.perform(get("/api/admin/catalog/doctors")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/admin/catalog/departments").header("Authorization","Bearer "+new JwtService().issue("test","PATIENT"))).andExpect(status().isForbidden());
        mvc.perform(get("/api/admin/catalog/doctors").header("Authorization","Bearer "+new JwtService().issue("admin","ADMIN"))).andExpect(status().isOk());
    }
    @Test void directoryIsPersistedAndDisablingDepartmentHidesDoctors() {
        var dep=department();var doc=management.saveDoctor(null,edit(dep.id(),5));
        assertEquals(doc.id(),catalog.find(doc.id()).id());
        var current=edit(dep.id(),5);
        assertThrows(ResponseStatusException.class,()->management.saveDoctor(doc.id(),new DoctorEdit(current.name(),current.title(),dep.id(),true,current.date(),"下午",5,20)));
        management.initialize();
        assertEquals(doc.id(),catalog.find(doc.id()).id());
        management.saveDepartment(dep.id(),new DepartmentEdit(dep.name(),false));
        assertNull(catalog.find(doc.id()));
        assertEquals(0,slots.decrementAvailableSlot(doc.id(),doc.date()));
        assertTrue(management.doctors().stream().anyMatch(d->d.id().equals(doc.id())));
    }
    @Test void resizingPreservesBookedCountAndRejectsTooSmallOrDateChange() {
        var dep=department();var doc=management.saveDoctor(null,edit(dep.id(),5));
        assertEquals(1,slots.decrementAvailableSlot(doc.id(),doc.date()));
        assertEquals(1,slots.decrementAvailableSlot(doc.id(),doc.date()));
        var resized=management.saveDoctor(doc.id(),edit(dep.id(),3));
        assertEquals(1,resized.remaining());
        assertThrows(ResponseStatusException.class,()->management.saveDoctor(doc.id(),edit(dep.id(),1)));
        var changed=edit(dep.id(),3);
        assertThrows(ResponseStatusException.class,()->management.saveDoctor(doc.id(),new DoctorEdit(changed.name(),changed.title(),dep.id(),true,LocalDate.now().plusDays(3).toString(),changed.period(),3,20)));
        assertEquals(1,catalog.find(doc.id()).remaining());
    }
    @Test void invalidDataDoesNotCreatePartialDoctorOrSlot() {
        var dep=department();
        assertThrows(ResponseStatusException.class,()->management.saveDoctor(null,edit("missing-department",5)));
        assertThrows(ResponseStatusException.class,()->management.saveDoctor(null,edit(dep.id(),-1)));
        assertThrows(ResponseStatusException.class,()->management.saveDepartment(null,new DepartmentEdit(dep.name(),true)));
    }
    @Test void capacityAdjustmentAndBookingRemainConsistentUnderConcurrency() throws Exception {
        var dep=department();var doc=management.saveDoctor(null,edit(dep.id(),12));
        var executor=Executors.newFixedThreadPool(4);
        try {
            var futures=new java.util.ArrayList<Future<Integer>>();
            for(int i=0;i<12;i++)futures.add(executor.submit(()->slots.decrementAvailableSlot(doc.id(),doc.date())));
            var resized=executor.submit(()->management.saveDoctor(doc.id(),edit(dep.id(),20)));
            int booked=0;for(var f:futures)booked+=f.get(15,TimeUnit.SECONDS);
            resized.get(15,TimeUnit.SECONDS);
            var finalDoc=management.doctors().stream().filter(d->d.id().equals(doc.id())).findFirst().orElseThrow();
            assertEquals(20,finalDoc.total());assertEquals(20-booked,finalDoc.remaining());assertEquals(12,booked);
        } finally {executor.shutdownNow();}
    }
}
