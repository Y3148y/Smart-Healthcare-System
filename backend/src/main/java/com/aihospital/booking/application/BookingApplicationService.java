package com.aihospital.booking.application;

import com.aihospital.shared.model.Models.Appointment;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class BookingApplicationService {
    private final SimulationBookingService booking;
    public BookingApplicationService(SimulationBookingService booking) {
        this.booking = booking;
    }

    public Appointment book(String doctorId, String sessionId, String patient, String idempotencyKey) {
        // One transactional authority: existing idempotent receipt first, then current risk/ownership/slot gates.
        // Missing session identity is never silently converted to an independent booking.
        return booking.book(doctorId, sessionId, patient, idempotencyKey);
    }

    public List<Appointment> appointments(String patient) { return booking.appointments(patient); }
}
