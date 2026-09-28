package com.aihospital.booking.domain;

import com.aihospital.shared.model.Models.Appointment;

import java.util.List;
import java.util.Optional;

/** Persistence boundary used by the booking use case; SQL stays in infrastructure. */
public interface BookingStore {
    List<Appointment> appointments(String patient);
    Optional<Appointment> appointmentByKey(String patient, String key);
    void saveAppointment(Appointment appointment, String idempotencyKey);
}
