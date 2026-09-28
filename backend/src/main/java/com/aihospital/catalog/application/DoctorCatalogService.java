package com.aihospital.catalog.application;

import com.aihospital.catalog.domain.DoctorDirectory;
import com.aihospital.catalog.domain.SlotStore;
import com.aihospital.shared.model.Models.Doctor;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;

import java.util.List;

@Service
public class DoctorCatalogService {
    private final DoctorDirectory directory;
    private final SlotStore slots;

    public DoctorCatalogService(DoctorDirectory directory, SlotStore slots) {
        this.directory = directory;
        this.slots = slots;
    }

    @PostConstruct public void seedSlots() {
        for (Doctor doctor : directory.doctors(null))
            if (slots.slotCount(doctor.id(), doctor.date()) == 0) slots.createSlot(doctor);
    }

    public List<Doctor> doctors(String department) {
        return directory.doctors(department).stream().map(doctor -> {
            Integer remaining = slots.remaining(doctor.id(), doctor.date());
            return new Doctor(doctor.id(), doctor.name(), doctor.title(), doctor.department(), doctor.period(),
                    doctor.date(), remaining == null ? 0 : remaining, doctor.total(), doctor.fee());
        }).toList();
    }

    public Doctor find(String doctorId) {
        return directory.doctors(null).stream().filter(doctor -> doctor.id().equals(doctorId))
                .findFirst().orElse(null);
    }
}
