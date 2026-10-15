package com.aihospital.triage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;
import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;

/** Opt-in: two real child JVMs sharing a disposable loopback MySQL database. */
@SpringBootTest
@EnabledIfSystemProperty(named="ai.test.turn-process-restart",matches="true")
class DurableTurnProcessIntegrationTest {
    @Autowired Environment env;
    @Autowired JdbcTemplate jdbc;
    private Process start(String... args) throws Exception {
        String url=env.getRequiredProperty("spring.datasource.url");
        assertTrue(url.startsWith("jdbc:mysql://127.0.0.1:"),"Use a disposable loopback MySQL test instance");
        var command=new ArrayList<>(List.of(Path.of(System.getProperty("java.home"),"bin","java").toString(),
                "-Xmx256m","-cp",System.getProperty("surefire.test.class.path",System.getProperty("java.class.path")),DurableTurnProcessProbe.class.getName()));
        command.addAll(List.of(args));
        var builder=new ProcessBuilder(command).redirectErrorStream(true);
        builder.environment().put("SPRING_DATASOURCE_URL",url);
        builder.environment().put("SPRING_DATASOURCE_USERNAME",env.getRequiredProperty("spring.datasource.username"));
        builder.environment().put("SPRING_DATASOURCE_PASSWORD",env.getProperty("spring.datasource.password",""));
        return builder.start();
    }
    private String marker(Process process,String prefix) throws Exception {
        var reader=Executors.newSingleThreadExecutor();
        try{
            return reader.submit(()->{
                try(var input=new BufferedReader(new InputStreamReader(process.getInputStream()))){
                    String line;while((line=input.readLine())!=null)if(line.startsWith(prefix))return line;
                    throw new IllegalStateException("Child JVM exited before marker "+prefix);
                }
            }).get(60,TimeUnit.SECONDS);
        }finally{reader.shutdownNow();}
    }
    @Test void committedClaimSurvivesForcedProcessDeathAndNewJvmRecoversWithoutReplay() throws Exception {
        String key=UUID.randomUUID().toString();Process first=null,second=null;
        try{
            first=start("begin",key);
            String session=marker(first,"TURN_READY ").substring("TURN_READY ".length());
            first.destroyForcibly();assertTrue(first.waitFor(15,TimeUnit.SECONDS));
            assertEquals("RUNNING",jdbc.queryForObject("SELECT status FROM triage_turn WHERE session_id=? AND request_id=?",String.class,session,key));
            // Advance the stored deadline instead of spending three minutes waiting in CI.
            jdbc.update("UPDATE triage_turn SET expires_at=started_at WHERE session_id=? AND request_id=?",session,key);
            second=start("recover",session,key);
            assertEquals("TURN_RECOVERED",marker(second,"TURN_RECOVERED"));
            assertTrue(second.waitFor(15,TimeUnit.SECONDS));assertEquals(0,second.exitValue());
            assertEquals("INTERRUPTED",jdbc.queryForObject("SELECT status FROM triage_turn WHERE session_id=? AND request_id=?",String.class,session,key));
        }finally{
            if(first!=null&&first.isAlive()){first.destroyForcibly();first.waitFor(15,TimeUnit.SECONDS);}
            if(second!=null&&second.isAlive()){second.destroyForcibly();second.waitFor(15,TimeUnit.SECONDS);}
        }
    }
}
