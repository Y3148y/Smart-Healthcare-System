package com.aihospital.patient;
import com.aihospital.patient.application.PatientProfileService;
import com.aihospital.patient.domain.PatientProfile;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
@SpringBootTest @AutoConfigureMockMvc
class PatientProfileTest {
    @Autowired PatientProfileService service;
    @Autowired MockMvc mvc;
    private String patient(){return "profile-test-"+UUID.randomUUID();}
    private PatientProfile.Edit edit(String name,long v){return new PatientProfile.Edit(name,null,"","","",v,true);}
    @Test void permissionsAndOwnerIsolation() throws Exception {
        mvc.perform(get("/api/patient/profile")).andExpect(status().isUnauthorized());
        mvc.perform(get("/api/patient/profile").header("Authorization","Bearer "+new JwtService().issue("admin","ADMIN"))).andExpect(status().isForbidden());
        String a=patient(),b=patient();service.saveOwn(a,edit("only-a",0));
        mvc.perform(get("/api/patient/profile").header("Authorization","Bearer "+new JwtService().issue(a,"PATIENT"))).andExpect(status().isOk()).andExpect(jsonPath("$.displayName").value("only-a"));
        mvc.perform(get("/api/patient/profile").header("Authorization","Bearer "+new JwtService().issue(b,"PATIENT"))).andExpect(status().isOk()).andExpect(jsonPath("$.version").value(0));
    }
    @Test void savedDataKeepsSourceAndRejectsStaleOverwrite(){
        String id=patient();var saved=service.saveOwn(id,new PatientProfile.Edit("Self report","1990-01-01","reported allergy","reported medication","reported background",0,true));
        assertEquals(1,saved.version());assertEquals(PatientProfile.SOURCE,saved.source());assertNotNull(saved.updatedAt());assertEquals("reported allergy",service.own(id).allergies());
        service.saveOwn(id,edit("updated",1));
        var conflict=assertThrows(ResponseStatusException.class,()->service.saveOwn(id,edit("stale",1)));
        assertEquals(409,conflict.getStatusCode().value());assertEquals("updated",service.own(id).displayName());
    }
    @Test void invalidDataDoesNotPersist(){
        String id=patient();
        assertThrows(ResponseStatusException.class,()->service.saveOwn(id,new PatientProfile.Edit("n",null,"","","",0,false)));
        assertThrows(ResponseStatusException.class,()->service.saveOwn(id,edit("",0)));
        assertThrows(ResponseStatusException.class,()->service.saveOwn(id,new PatientProfile.Edit("n",LocalDate.now().toString(),"","","",0,true)));
        assertThrows(ResponseStatusException.class,()->service.saveOwn(id,new PatientProfile.Edit("n","not-a-date","","","",0,true)));
        assertThrows(ResponseStatusException.class,()->service.saveOwn(id,new PatientProfile.Edit("n",null,"x".repeat(1001),"","",0,true)));
        assertEquals(0,service.own(id).version());
    }
    @Test void concurrentEditsHaveOnlyOneWinner() throws Exception {
        String id=patient();service.saveOwn(id,edit("initial",0));var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
        try{Callable<Boolean> task=()->{start.await();try{service.saveOwn(id,edit("updated",1));return true;}catch(ResponseStatusException ex){assertEquals(409,ex.getStatusCode().value());return false;}};
            var a=pool.submit(task);var b=pool.submit(task);start.countDown();assertNotEquals(a.get(10,TimeUnit.SECONDS),b.get(10,TimeUnit.SECONDS));assertEquals(2,service.own(id).version());
        }finally{pool.shutdownNow();}
    }
}
