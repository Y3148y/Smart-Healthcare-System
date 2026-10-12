package com.aihospital.booking.application;

import com.aihospital.shared.model.Models.Appointment;
import com.aihospital.booking.domain.BookingStore;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class BookingApplicationService {
    private final SimulationBookingService booking;
    public BookingApplicationService(SimulationBookingService booking) {
        this.booking = booking;
    }

    public Appointment book(String doctorId, String sessionId, String patient, String idempotencyKey) {
        // Catch outside the transactional method: after the losing claim rolls back,
        // replay the winner's committed receipt without deducting another slot.
        try {
            return booking.book(doctorId, sessionId, patient, idempotencyKey);
        } catch (BookingStore.KeyClaimConflict conflict) {
            return booking.replayClaim(doctorId, sessionId, patient, idempotencyKey);
        }
    }

    public List<Appointment> appointments(String patient) { return booking.appointments(patient); }
}
