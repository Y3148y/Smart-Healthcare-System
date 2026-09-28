package com.aihospital.booking.api;

import com.aihospital.booking.application.BookingApplicationService;
import com.aihospital.shared.model.Models.Appointment;
import com.aihospital.shared.security.RoleGuard;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/appointments")
public class AppointmentController {
    private final BookingApplicationService booking;
    private final RoleGuard guard;
    public AppointmentController(BookingApplicationService booking, RoleGuard guard) {
        this.booking = booking; this.guard = guard;
    }
    @PostMapping public Appointment book(@RequestBody Map<String, String> body,
            @RequestHeader(value = "Authorization", required = false) String auth) {
        return booking.book(body.get("doctorId"), body.get("sessionId"),
                guard.require(auth, "PATIENT").subject(), body.get("idempotencyKey"));
    }
    @GetMapping public List<Appointment> appointments(@RequestHeader(value = "Authorization", required = false) String auth) {
        return booking.appointments(guard.require(auth, "PATIENT").subject());
    }
}
