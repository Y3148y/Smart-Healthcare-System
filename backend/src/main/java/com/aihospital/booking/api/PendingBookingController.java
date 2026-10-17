package com.aihospital.booking.api;

import com.aihospital.booking.application.PendingBookingService;
import com.aihospital.booking.domain.PendingBooking;
import com.aihospital.shared.model.Models.Appointment;
import com.aihospital.shared.security.RoleGuard;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/appointments/actions")
public class PendingBookingController {
    private final PendingBookingService booking;
    private final RoleGuard guard;
    public PendingBookingController(PendingBookingService booking,RoleGuard guard) {this.booking=booking;this.guard=guard;}
    public record Prepare(String doctorId,String sessionId,String requestKey) {}
    public record Confirm(boolean confirmed) {}
    @PostMapping public PendingBooking.View prepare(@RequestBody Prepare body,
            @RequestHeader(value="Authorization",required=false) String auth) {
        return booking.prepare(guard.require(auth,"PATIENT").subject(),body.doctorId(),body.sessionId(),body.requestKey());
    }
    @PostMapping("/{id}/confirm") public Appointment confirm(@PathVariable String id,@RequestBody Confirm body,
            @RequestHeader(value="Authorization",required=false) String auth) {
        return booking.confirm(guard.require(auth,"PATIENT").subject(),id,body.confirmed());
    }
    @DeleteMapping("/{id}") public java.util.Map<String,String> cancel(@PathVariable String id,
            @RequestHeader(value="Authorization",required=false) String auth) {
        booking.cancel(guard.require(auth,"PATIENT").subject(),id); return java.util.Map.of("status","CANCELLED");
    }
}
