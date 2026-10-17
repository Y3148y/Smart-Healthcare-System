package com.aihospital.booking;

import com.aihospital.booking.application.*;
import com.aihospital.catalog.application.CatalogManagementService;
import com.aihospital.catalog.domain.CatalogRecords.*;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import java.nio.file.*;
import java.time.LocalDate;
import java.util.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** Two real JVMs; explicit opt-in and disposable loopback MySQL only. */
@SpringBootTest
@EnabledIfSystemProperty(named="ai.test.booking-process-race",matches="true")
class BookingConfirmationProcessIntegrationTest {
    @Autowired PendingBookingService actions;
    @Autowired SimulationBookingService booking;
    @Autowired CatalogManagementService catalog;
    @Autowired Environment env;
    @Autowired JdbcTemplate jdbc;
    @TempDir Path directory;
    private String id(){return UUID.randomUUID().toString();}
    private ManagedDoctor fixture(){
        var d=catalog.saveDepartment(null,new DepartmentEdit("Synthetic process "+id(),true));
        return catalog.saveDoctor(null,new DoctorEdit("Synthetic process doctor "+id(),"Synthetic",d.id(),true,
                LocalDate.now().plusDays(2).toString(),"上午",1,0));
    }
    private Process start(String patient,String action,String name)throws Exception{
        String url=env.getRequiredProperty("spring.datasource.url");
        assertTrue(url.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/[^?]+(?:\\?.*)?"),"Use disposable loopback MySQL with an explicit port");
        var command=List.of(Path.of(System.getProperty("java.home"),"bin","java").toString(),"-Xmx256m","-cp",
                System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),
                BookingConfirmationProcessProbe.class.getName(),patient,action,directory.resolve(name+".ready").toString(),
                directory.resolve("start.gate").toString(),directory.resolve(name+".result").toString());
        var builder=new ProcessBuilder(command).redirectErrorStream(true).redirectOutput(directory.resolve(name+".log").toFile());
        builder.environment().keySet().removeIf(key->key.startsWith("AI_")||key.startsWith("SPRING_")
                ||Set.of("JAVA_TOOL_OPTIONS","JDK_JAVA_OPTIONS","MAVEN_OPTS").contains(key));
        builder.environment().put("AI_MODE","demo");builder.environment().put("AI_AGENT_MODE","OFF");
        builder.environment().put("SPRING_DATASOURCE_URL",url);
        builder.environment().put("SPRING_DATASOURCE_USERNAME",env.getRequiredProperty("spring.datasource.username"));
        builder.environment().put("SPRING_DATASOURCE_PASSWORD",env.getProperty("spring.datasource.password",""));
        return builder.start();
    }
    private List<String> race(String p,String action,String other,String otherAction)throws Exception{
        Process first=null,second=null;
        try{
            first=start(p,action,"first");second=start(other,otherAction,"second");
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(60);
            while(!Files.exists(directory.resolve("first.ready"))||!Files.exists(directory.resolve("second.ready"))){
                assertTrue(first.isAlive()&&second.isAlive(),"Child JVM ended before synthetic barrier");
                assertTrue(System.nanoTime()<deadline,"Child startup deadline exceeded");Thread.sleep(25);
            }
            Files.writeString(directory.resolve("start.gate"),"START",StandardOpenOption.CREATE_NEW);
            assertTrue(first.waitFor(30,TimeUnit.SECONDS));assertTrue(second.waitFor(30,TimeUnit.SECONDS));
            assertEquals(0,first.exitValue());assertEquals(0,second.exitValue());
            return List.of(Files.readString(directory.resolve("first.result")),Files.readString(directory.resolve("second.result")));
        }finally{
            if(first!=null&&first.isAlive()){first.destroyForcibly();first.waitFor(15,TimeUnit.SECONDS);}
            if(second!=null&&second.isAlive()){second.destroyForcibly();second.waitFor(15,TimeUnit.SECONDS);}
        }
    }
    private int remaining(ManagedDoctor d){return jdbc.queryForObject("SELECT remaining FROM sim_slot WHERE doctor_id=? AND slot_date=?",Integer.class,d.id(),d.date());}
    @Test @Timeout(120) void duplicateConfirmationAcrossJvmsReplaysOneReceiptAndDeductsOnce()throws Exception{
        var doctor=fixture();String p=id();var a=actions.prepare(p,doctor.id(),"walk-in",id());
        var results=race(p,a.actionId(),p,a.actionId());
        assertTrue(results.get(0).matches("SUCCESS SIM[A-F0-9]{16}"));assertEquals(results.get(0),results.get(1));
        assertEquals(1,booking.appointments(p).size());assertEquals(0,remaining(doctor));
        assertEquals(results.get(0).substring(8),actions.confirm(p,a.actionId(),true).id());
    }
    @Test @Timeout(120) void competingActionsAcrossJvmsCannotOversellTheLastSlot()throws Exception{
        var doctor=fixture();String p=id(),other=id();
        var a=actions.prepare(p,doctor.id(),"walk-in",id());var b=actions.prepare(other,doctor.id(),"walk-in",id());
        var results=race(p,a.actionId(),other,b.actionId());
        assertEquals(1,results.stream().filter(x->x.startsWith("SUCCESS SIM")).count());
        assertEquals(1,results.stream().filter(x->x.equals("CONFLICT")).count());
        assertEquals(1,booking.appointments(p).size()+booking.appointments(other).size());assertEquals(0,remaining(doctor));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM pending_booking_action WHERE id IN (?,?) AND status='CONFIRMED'",Integer.class,a.actionId(),b.actionId()));
    }
}
