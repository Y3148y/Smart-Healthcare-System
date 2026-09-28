package com.aihospital.catalog.domain;

import com.aihospital.shared.model.Models.Doctor;

/** Slot inventory boundary shared by catalog queries and booking writes. */
public interface SlotStore {
    int slotCount(String doctorId, String date);
    void createSlot(Doctor doctor);
    Integer remaining(String doctorId, String date);
    int decrementAvailableSlot(String doctorId, String date);
}
