package com.aihospital;

import com.aihospital.booking.application.PendingBookingService;
import com.aihospital.booking.application.SimulationBookingService;
import com.aihospital.catalog.application.CatalogManagementService;
import com.aihospital.catalog.domain.CatalogRecords.*;
import com.aihospital.shared.model.Models.*;
import com.aihospital.triage.application.TriageConversationService;
import com.aihospital.triage.domain.*;
import com.aihospital.triage.domain.TriageRecords.Eligibility;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import java.time.*;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** Real conversation persistence and booking services; the engine response alone is synthetic. */
@SpringBootTest
class SourceExcerptBookingTest {
    @Autowired TriageConversationService conversations;
    @Autowired CatalogManagementService catalog;
    @Autowired SimulationBookingService booking;
    @Autowired PendingBookingService actions;
    @Autowired JdbcTemplate jdbc;
    @Autowired TriageStore store;
    @MockBean TriageEngine engine;

    @Test void excerptCannotRegainDoctorsOrBookThroughEitherServerEntry() {
        String patient=UUID.randomUUID().toString();
        var department=catalog.saveDepartment(null,new DepartmentEdit("Excerpt fixture "+patient,true));
        var saved=catalog.saveDoctor(null,new DoctorEdit("Fixture doctor","Fixture",department.id(),true,
                LocalDate.now().plusDays(2).toString(),"上午",3,0));
        var doctor=new Doctor(saved.id(),saved.name(),saved.title(),saved.department(),saved.period(),saved.date(),3,3,0);
        var session=conversations.create(patient,new Eligibility(true,true,true)).session().id();
        when(engine.assessSafety(anyString())).thenReturn(new TriageSafetyPolicy().assess(""));
        when(engine.triage(eq(session),anyString(),eq(patient),anyList())).thenReturn(new TriageResult(
                session,"普通",70,doctor.department(),null,"Synthetic whole source quotation","",List.of(),
                List.of(new ToolTrace("doctor_schedule_search","fixture","","found",1,true,"")),
                List.of(new DepartmentCandidate(doctor.department(),"fixture",null)),"SOURCE_EXCERPT","fixture",
                LocalDateTime.now(),null,true,"fixture"));
        var response=conversations.send(session,patient,"Synthetic request for an appointment");
        assertEquals("SOURCE_EXCERPT",response.assessments().get(0).result().modelStatus());
        var persisted=conversations.latestResult(session,patient);
        assertEquals("普通",persisted.riskLevel());assertTrue(persisted.grounded());
        assertNull(persisted.doctor());assertTrue(persisted.candidates().stream().allMatch(c->c.doctor()==null));
        assertEquals(409,assertThrows(ResponseStatusException.class,()->actions.prepare(patient,doctor.id(),session,UUID.randomUUID().toString())).getStatusCode().value());
        assertEquals(409,assertThrows(ResponseStatusException.class,()->booking.book(doctor.id(),session,patient,UUID.randomUUID().toString())).getStatusCode().value());
        assertEquals(3,jdbc.queryForObject("SELECT remaining FROM sim_slot WHERE doctor_id=? AND slot_date=?",Integer.class,doctor.id(),doctor.date()));
        // A tampered/legacy stored excerpt containing a doctor still cannot bypass the booking gate.
        var withDoctor=new TriageResult(session,"普通",70,doctor.department(),doctor,"fixture","",List.of(),List.of(),List.of(),
                "SOURCE_EXCERPT","fixture",LocalDateTime.now(),null,true,"fixture");
        store.saveAssessmentAndAnswer(session,2,withDoctor);
        assertEquals(409,assertThrows(ResponseStatusException.class,()->booking.validateEligibility(doctor.id(),session,patient)).getStatusCode().value());
        assertEquals(3,jdbc.queryForObject("SELECT remaining FROM sim_slot WHERE doctor_id=? AND slot_date=?",Integer.class,doctor.id(),doctor.date()));
        // Successful source selection does not override risk/grounding. Nor does it remove an existing valid workflow recommendation.
        for(int i=0;i<3;i++) {
            boolean grounded=i!=1;String risk=i==0?"尽快就医":"普通";
            store.saveAssessmentAndAnswer(session,3+i,new TriageResult(session,risk,70,doctor.department(),doctor,"fixture","",
                    List.of(),List.of(),List.of(),"SOURCE_QUOTED","fixture",LocalDateTime.now(),null,grounded,"fixture"));
            if(i<2)assertEquals(409,assertThrows(ResponseStatusException.class,()->booking.validateEligibility(doctor.id(),session,patient)).getStatusCode().value());
            else assertDoesNotThrow(()->booking.validateEligibility(doctor.id(),session,patient));
        }
        assertEquals(3,jdbc.queryForObject("SELECT remaining FROM sim_slot WHERE doctor_id=? AND slot_date=?",Integer.class,doctor.id(),doctor.date()));
        var limited=new TriageResult(session,"普通",70,doctor.department(),doctor,"No applicable source selected","",List.of(),
                List.of(new ToolTrace("doctor_schedule_search","fixture","","found",1,true,"")),
                List.of(new DepartmentCandidate(doctor.department(),"fixture",doctor)),"SOURCE_LIMITED","fixture",LocalDateTime.now(),null,true,"fixture");
        when(engine.triage(eq(session),anyString(),eq(patient),anyList())).thenReturn(limited);
        conversations.send(session,patient,"Synthetic follow-up with no applicable source selected");
        var restricted=conversations.latestResult(session,patient);
        assertEquals("SOURCE_LIMITED",restricted.modelStatus());assertNull(restricted.doctor());
        assertTrue(restricted.candidates().stream().allMatch(c->c.doctor()==null));
        assertEquals(409,assertThrows(ResponseStatusException.class,()->actions.prepare(patient,doctor.id(),session,UUID.randomUUID().toString())).getStatusCode().value());
        assertEquals(409,assertThrows(ResponseStatusException.class,()->booking.book(doctor.id(),session,patient,UUID.randomUUID().toString())).getStatusCode().value());
        assertEquals(3,jdbc.queryForObject("SELECT remaining FROM sim_slot WHERE doctor_id=? AND slot_date=?",Integer.class,doctor.id(),doctor.date()));
    }
}
