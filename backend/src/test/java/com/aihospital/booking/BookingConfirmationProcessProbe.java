package com.aihospital.booking;

import com.aihospital.HospitalApplication;
import com.aihospital.booking.application.PendingBookingService;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.web.server.ResponseStatusException;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;

/** Child fixture only; not packaged in the application. No web port or external model. */
public final class BookingConfirmationProcessProbe {
    public static void main(String[] args)throws Exception{
        if(args.length!=5)throw new IllegalArgumentException("Synthetic booking probe arguments required");
        var ready=Path.of(args[2]);var gate=Path.of(args[3]);var result=Path.of(args[4]);
        try(var context=new SpringApplicationBuilder(HospitalApplication.class).web(WebApplicationType.NONE)
                .properties("spring.main.banner-mode=off","logging.level.root=ERROR")
                .run("--ai.mode=demo","--ai.agent.mode=OFF","--server.address=127.0.0.1")){
            Files.writeString(ready,"READY",StandardOpenOption.CREATE_NEW);
            long deadline=System.nanoTime()+TimeUnit.SECONDS.toNanos(60);
            while(!Files.exists(gate)){
                if(System.nanoTime()>deadline)throw new IllegalStateException("Synthetic start gate timed out");
                Thread.sleep(25);
            }
            String outcome;
            try{outcome="SUCCESS "+context.getBean(PendingBookingService.class).confirm(args[0],args[1],true).id();}
            catch(ResponseStatusException conflict){
                if(conflict.getStatusCode().value()!=409)throw conflict;
                outcome="CONFLICT";
            }
            Files.writeString(result,outcome,StandardOpenOption.CREATE_NEW);
        }
    }
}
