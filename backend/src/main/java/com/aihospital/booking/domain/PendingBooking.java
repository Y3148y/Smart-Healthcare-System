package com.aihospital.booking.domain;

import com.aihospital.shared.model.Models.Doctor;
import java.time.LocalDateTime;

/** A server-bound proposal, never a reservation or a model authorization. */
public record PendingBooking(String id, String patient, String requestKey, String sessionId,
                             String doctorId, String doctorFingerprint, long stateVersion,
                             int assessmentVersion, LocalDateTime expiresAt, String status) {
    public record View(String actionId, String sessionId, Doctor doctor, long stateVersion,
                       int assessmentVersion, LocalDateTime expiresAt, String status) {}
}
