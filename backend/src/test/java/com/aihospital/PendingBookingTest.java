package com.aihospital;

import com.aihospital.booking.application.PendingBookingService;
import com.aihospital.booking.application.SimulationBookingService;
import com.aihospital.catalog.application.CatalogManagementService;
import com.aihospital.catalog.domain.CatalogRecords.*;
import com.aihospital.shared.model.Models.*;
import com.aihospital.triage.domain.TriageStore;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest
class PendingBookingTest {
    @Autowired PendingBookingService actions;
    @Autowired SimulationBookingService booking;
    @Autowired CatalogManagementService catalog;
    @Autowired TriageStore triage;
    @Autowired JdbcTemplate jdbc;
    private String id(){return UUID.randomUUID().toString();}
    private Doctor fixture(int total) {
        var department=catalog.saveDepartment(null,new DepartmentEdit("Synthetic action "+id(),true));
        var d=catalog.saveDoctor(null,new DoctorEdit("Synthetic doctor "+id(),"Synthetic",department.id(),true,
                LocalDate.now().plusDays(2).toString(),"上午",total,0));
        return new Doctor(d.id(),d.name(),d.title(),d.department(),d.period(),d.date(),d.remaining(),d.total(),d.fee());
    }
    private int remaining(Doctor d){return jdbc.queryForObject("SELECT remaining FROM sim_slot WHERE doctor_id=? AND slot_date=?",Integer.class,d.id(),d.date());}
    private void rejects(int status,Runnable action){assertEquals(status,assertThrows(ResponseStatusException.class,action::run).getStatusCode().value());}
    private String session(String patient,Doctor doctor){
        String session=id();triage.createSession(session,patient,LocalDateTime.now());
        triage.saveAssessmentAndAnswer(session,1,new TriageResult(session,"普通",0,doctor.department(),doctor,
                "Synthetic nonmedical assessment fixture","",List.of(),List.of(),List.of(),"DEMO","fixture",
                LocalDateTime.now(),null,true,"Synthetic fixture only"));
        triage.updateSession(session,"Synthetic","Fixture","已完成分诊");return session;
    }

    @Test void prepareIsIdempotentAndDoesNotReserveThenConfirmationReplaysEvenAfterExpiry(){
        var d=fixture(3);String p=id(),key=id();var a=actions.prepare(p,d.id(),"walk-in",key);
        assertEquals(a.actionId(),actions.prepare(p,d.id(),"walk-in",key).actionId());
        assertEquals(3,remaining(d));rejects(400,()->actions.confirm(p,a.actionId(),false));
        var receipt=actions.confirm(p,a.actionId(),true);assertEquals(2,remaining(d));
        jdbc.update("UPDATE pending_booking_action SET expires_at=? WHERE id=?",LocalDateTime.now().minusDays(1),a.actionId());
        assertEquals(receipt,actions.confirm(p,a.actionId(),true));assertEquals(2,remaining(d));
    }
    @Test void ownerParametersExpiryAndCancellationCannotBeBypassed(){
        var d=fixture(2);String p=id(),key=id();var a=actions.prepare(p,d.id(),"walk-in",key);
        rejects(404,()->actions.confirm(id(),a.actionId(),true));
        rejects(409,()->actions.prepare(p,fixture(2).id(),"walk-in",key));
        actions.cancel(p,a.actionId());actions.cancel(p,a.actionId());
        rejects(409,()->actions.confirm(p,a.actionId(),true));assertEquals(2,remaining(d));
        var expired=actions.prepare(p,d.id(),"walk-in",id());
        jdbc.update("UPDATE pending_booking_action SET expires_at=? WHERE id=?",LocalDateTime.now().minusDays(1),expired.actionId());
        rejects(409,()->actions.confirm(p,expired.actionId(),true));assertEquals(2,remaining(d));
    }
    @Test void doctorScheduleDriftAndSessionVersionDriftRequireNewProposal(){
        var d=fixture(2);String p=id();var a=actions.prepare(p,d.id(),"walk-in",id());
        jdbc.update("UPDATE catalog_doctor SET fee=fee+1 WHERE id=?",d.id());
        rejects(409,()->actions.confirm(p,a.actionId(),true));assertEquals(2,remaining(d));
        String session=session(p,d);var b=actions.prepare(p,d.id(),session,id());
        triage.appendMessage(session,"USER","Synthetic subsequent turn, no medical content");
        rejects(409,()->actions.confirm(p,b.actionId(),true));assertEquals(2,remaining(d));
    }
    @Test void lastSlotRaceAndSameActionConcurrentConfirmationDeductOnce()throws Exception {
        var d=fixture(1);String p=id();var a=actions.prepare(p,d.id(),"walk-in",id());
        var executor=Executors.newFixedThreadPool(2);
        try{
            var first=executor.submit(()->actions.confirm(p,a.actionId(),true));
            var second=executor.submit(()->actions.confirm(p,a.actionId(),true));
            assertEquals(first.get(15,TimeUnit.SECONDS),second.get(15,TimeUnit.SECONDS));
            assertEquals(0,remaining(d));assertEquals(1,booking.appointments(p).size());
        }finally{executor.shutdownNow();}
    }
    @Test void capacityFailureRollsBackActionAndRequestClaim(){
        var d=fixture(1);String p=id();var a=actions.prepare(p,d.id(),"walk-in",id());
        jdbc.update("UPDATE sim_slot SET remaining=0 WHERE doctor_id=?",d.id());
        rejects(409,()->actions.confirm(p,a.actionId(),true));
        assertEquals("PENDING",jdbc.queryForObject("SELECT status FROM pending_booking_action WHERE id=?",String.class,a.actionId()));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM sim_booking_request WHERE patient_id=?",Integer.class,p));
        jdbc.update("UPDATE sim_slot SET remaining=1 WHERE doctor_id=?",d.id());
        actions.confirm(p,a.actionId(),true);assertEquals(0,remaining(d));
    }
    @Test void competingActionsForOneSlotProduceOneReceipt()throws Exception {
        var d=fixture(1);String p=id(),other=id();
        var a=actions.prepare(p,d.id(),"walk-in",id());var b=actions.prepare(other,d.id(),"walk-in",id());
        var executor=Executors.newFixedThreadPool(2);
        try{
            var attempts=List.of(executor.submit(()->attempt(p,a.actionId())),executor.submit(()->attempt(other,b.actionId())));
            int success=0;for(var future:attempts)success+=future.get(15,TimeUnit.SECONDS);
            assertEquals(1,success);assertEquals(0,remaining(d));
            assertEquals(1,booking.appointments(p).size()+booking.appointments(other).size());
        }finally{executor.shutdownNow();}
    }
    private int attempt(String patient,String action){
        try{actions.confirm(patient,action,true);return 1;}
        catch(ResponseStatusException conflict){assertEquals(409,conflict.getStatusCode().value());return 0;}
    }
    @Test void concurrentPrepareClaimsOnePersistentServerAction()throws Exception {
        var d=fixture(2);String p=id(),key=id();var executor=Executors.newFixedThreadPool(2);
        try{
            var a=executor.submit(()->actions.prepare(p,d.id(),"walk-in",key));
            var b=executor.submit(()->actions.prepare(p,d.id(),"walk-in",key));
            assertEquals(a.get(15,TimeUnit.SECONDS).actionId(),b.get(15,TimeUnit.SECONDS).actionId());
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM pending_booking_action WHERE patient=? AND request_key=?",Integer.class,p,key));
            assertEquals(2,remaining(d));
        }finally{executor.shutdownNow();}
    }
    @Test void newTurnAndOfflineReviewBlockNewConfirmationButNotHistoricalReceiptReplay(){
        var d=fixture(3);String p=id(),session=session(p,d);
        var a=actions.prepare(p,d.id(),session,id());var receipt=actions.confirm(p,a.actionId(),true);
        triage.appendMessage(session,"USER","Synthetic later information");
        triage.updateSession(session,"Synthetic","Fixture","待补充信息");
        assertEquals(receipt,actions.confirm(p,a.actionId(),true));
        rejects(409,()->actions.prepare(p,d.id(),session,id()));assertEquals(2,remaining(d));
    }
    @Test void simultaneousPreparationAndConfirmationHaveConsistentLockOrder()throws Exception {
        var executor=Executors.newFixedThreadPool(2);
        try{for(int i=0;i<8;i++){
            var d=fixture(2);String p=id(),key=id();var a=actions.prepare(p,d.id(),"walk-in",key);
            var start=new java.util.concurrent.CountDownLatch(1);
            var preparing=executor.submit(()->{start.await();try{return actions.prepare(p,d.id(),"walk-in",key).actionId();}
                catch(ResponseStatusException rejected){assertEquals(409,rejected.getStatusCode().value());return "already-confirmed";}});
            var confirming=executor.submit(()->{start.await();return actions.confirm(p,a.actionId(),true);});
            start.countDown();String outcome=preparing.get(15,TimeUnit.SECONDS);
            assertTrue(outcome.equals(a.actionId())||outcome.equals("already-confirmed"));
            assertNotNull(confirming.get(15,TimeUnit.SECONDS));assertEquals(1,remaining(d));
        }}finally{executor.shutdownNow();}
    }
    @Test void actionLifetimeRejectsUnsafeConfigurationRatherThanSilentlyClamping(){
        assertThrows(IllegalArgumentException.class,()->new PendingBookingService(null,null,null,null,29));
        assertThrows(IllegalArgumentException.class,()->new PendingBookingService(null,null,null,null,1801));
    }
}
