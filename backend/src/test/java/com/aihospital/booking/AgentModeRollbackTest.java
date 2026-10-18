package com.aihospital.booking;

import com.aihospital.HospitalApplication;
import com.aihospital.booking.application.PendingBookingService;
import com.aihospital.booking.application.SimulationBookingService;
import com.aihospital.catalog.application.CatalogManagementService;
import com.aihospital.catalog.domain.CatalogRecords.DepartmentEdit;
import com.aihospital.catalog.domain.CatalogRecords.DoctorEdit;
import com.aihospital.shared.model.Models.*;
import com.aihospital.triage.application.*;
import com.aihospital.triage.domain.*;
import com.aihospital.triage.domain.TriageRecords.Conversation;
import com.aihospital.triage.domain.TriageRecords.ResponseProvenance;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** Two application lifecycles, one isolated database; no web service or provider request. */
class AgentModeRollbackTest {
    @Test void enabledToOffRetainsObservationsAndEnforcesExistingActionState() {
        String database="rollback_"+id().replace("-", ""), patient=id();
        String url="jdbc:h2:mem:"+database+";MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1";
        String valid, stale, cancelled, observationSession, doctorId;
        try (var context = start(url, "ENABLED")) {
            assertEquals(AgentWorkflowSettings.Mode.ENABLED, context.getBean(AgentWorkflowSettings.class).mode());
            var catalog=context.getBean(CatalogManagementService.class);
            var department=catalog.saveDepartment(null,new DepartmentEdit("Synthetic rollback "+id(),true));
            var doctor=catalog.saveDoctor(null,new DoctorEdit("Synthetic rollback doctor","Synthetic",department.id(),true,
                    LocalDate.now().plusDays(2).toString(),"上午",3,0));
            doctorId=doctor.id();
            var store=context.getBean(TriageStore.class);
            var actions=context.getBean(PendingBookingService.class);
            var d=new Doctor(doctor.id(),doctor.name(),doctor.title(),doctor.department(),doctor.period(),doctor.date(),
                    doctor.remaining(),doctor.total(),doctor.fee());
            valid=actions.prepare(patient,doctorId,assessment(store,patient,d),id()).actionId();
            String staleSession=assessment(store,patient,d);
            stale=actions.prepare(patient,doctorId,staleSession,id()).actionId();
            store.appendMessage(staleSession,"USER","Synthetic new information after proposal");
            cancelled=actions.prepare(patient,doctorId,"walk-in",id()).actionId();
            actions.cancel(patient,cancelled);

            observationSession=id();
            store.createSession(observationSession,patient,LocalDateTime.now());
            var claim=store.beginTurn(observationSession,id(),"SyntheticRollbackObservation",180);
            assertEquals("CLAIMED",claim.outcome());
            var conversation=new Conversation(store.sessions(patient).stream()
                    .filter(s->s.id().equals(observationSession)).findFirst().orElseThrow(),
                    store.messages(observationSession),store.assessments(observationSession),null,claim.turn());
            var source=conversation.messages().get(conversation.messages().size()-1);
            var extraction=new IntentExtraction(1,List.of(IntentExtraction.Intent.GENERAL_HEALTH),List.of(
                    new IntentExtraction.SymptomEvent("SyntheticRollbackObservation",null,null,null,null,null,
                            IntentExtraction.Subject.SELF,IntentExtraction.Temporality.CURRENT,IntentExtraction.Assertion.AFFIRMED,
                            List.of(new IntentExtraction.SourceRef(source.id(),source.content())))),List.of(),List.of());
            context.getBean(SessionFactStore.class).stage(patient,conversation,IntentInput.recent(conversation.messages()),extraction);
            store.completeTurn(claim.turn(),()->store.appendAssistantMessage(observationSession,"Synthetic complete",
                    new ResponseProvenance("DEMO",0,0,0)));
            assertEquals(3,remaining(context,doctorId));
        }
        try (var context = start(url, "OFF")) {
            assertEquals(AgentWorkflowSettings.Mode.OFF,context.getBean(AgentWorkflowSettings.class).mode());
            var facts=context.getBean(SessionFactStore.class).completed(patient,observationSession);
            assertEquals(1,facts.events().size());
            assertEquals("SyntheticRollbackObservation",facts.events().get(0).symptom());
            var store=context.getBean(TriageStore.class);
            var conversation=new Conversation(store.sessions(patient).stream()
                    .filter(s->s.id().equals(observationSession)).findFirst().orElseThrow(),
                    store.messages(observationSession),store.assessments(observationSession),null);
            var jdbc=context.getBean(JdbcTemplate.class);
            int snapshots=jdbc.queryForObject("SELECT COUNT(*) FROM triage_fact_snapshot",Integer.class);
            assertNull(context.getBean(AgentWorkflowCoordinator.class).prepare(conversation,patient,"SyntheticRollbackObservation"));
            assertEquals(snapshots,jdbc.queryForObject("SELECT COUNT(*) FROM triage_fact_snapshot",Integer.class));
            var actions=context.getBean(PendingBookingService.class);
            rejects(404,()->actions.confirm(id(),valid,true));
            rejects(409,()->actions.confirm(patient,stale,true));
            rejects(409,()->actions.confirm(patient,cancelled,true));
            assertEquals(3,remaining(context,doctorId));
            var receipt=actions.confirm(patient,valid,true);
            assertEquals(receipt,actions.confirm(patient,valid,true));
            assertEquals(2,remaining(context,doctorId));
            assertEquals(1,context.getBean(SimulationBookingService.class).appointments(patient).size());
        }
    }
    private ConfigurableApplicationContext start(String url,String mode) {
        return new SpringApplicationBuilder(HospitalApplication.class).web(WebApplicationType.NONE)
                .run("--spring.datasource.url="+url,"--spring.datasource.username=sa","--spring.datasource.password=",
                        "--spring.sql.init.mode=always","--ai.mode=demo","--ai.agent.mode="+mode,
                        "--ai.retrieval.lexical-backend=memory","--spring.main.banner-mode=off","--logging.level.root=ERROR");
    }
    private String assessment(TriageStore store,String patient,Doctor doctor) {
        String session=id();store.createSession(session,patient,LocalDateTime.now());
        store.saveAssessmentAndAnswer(session,1,new TriageResult(session,Disposition.ROUTINE,0,doctor.department(),doctor,
                "Synthetic nonmedical assessment","",List.of(),List.of(),List.of(),"DEMO","fixture",LocalDateTime.now(),null,true,"Synthetic only"));
        store.updateSession(session,"Synthetic","Fixture","已完成分诊");return session;
    }
    private int remaining(ConfigurableApplicationContext context,String doctor) {
        return context.getBean(JdbcTemplate.class).queryForObject("SELECT remaining FROM sim_slot WHERE doctor_id=?",Integer.class,doctor);
    }
    private void rejects(int code,Runnable call) {
        assertEquals(code,assertThrows(ResponseStatusException.class,call::run).getStatusCode().value());
    }
    private String id() { return UUID.randomUUID().toString(); }
}
