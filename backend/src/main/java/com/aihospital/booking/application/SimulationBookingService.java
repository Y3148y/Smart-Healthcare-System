package com.aihospital.booking.application;

import com.aihospital.shared.model.Models.Appointment;
import com.aihospital.shared.model.Models.Doctor;
import com.aihospital.catalog.application.DoctorCatalogService;
import com.aihospital.catalog.domain.SlotStore;
import com.aihospital.booking.domain.BookingStore;
import com.aihospital.triage.application.TriageConversationService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

@Service
public class SimulationBookingService {
    private final BookingStore store;
    private final DoctorCatalogService catalog;
    private final SlotStore slots;
    private final TriageConversationService triage;

    public SimulationBookingService(BookingStore store, DoctorCatalogService catalog, SlotStore slots,
                                    TriageConversationService triage) {
        this.store = store;
        this.catalog = catalog;
        this.slots = slots;
        this.triage = triage;
    }

    public List<Doctor> doctors(String department) {
        return catalog.doctors(department);
    }

    public List<Appointment> appointments(String patient) {
        return store.appointments(patient);
    }

    @Transactional
    public Appointment book(String doctorId, String sessionId, String patient, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 64)
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "缺少有效的预约幂等键");
        var prior = store.appointmentByKey(patient, idempotencyKey);
        if (prior.isPresent()) {
            Appointment existing = prior.get();
            if (!existing.doctor().id().equals(doctorId) || !existing.triageSessionId().equals(sessionId))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "预约幂等键已用于其他号源");
            return existing;
        }

        if (sessionId != null && !sessionId.isBlank() && !"walk-in".equals(sessionId)) {
            var session = triage.requireOwner(sessionId, patient);
            var result = triage.latestResult(sessionId, patient);
            if ("紧急提示".equals(session.status()) || "待补充信息".equals(session.status())
                    || result == null || !result.grounded())
                throw new ResponseStatusException(HttpStatus.CONFLICT, "该分诊会话当前不允许普通预约，请线下就医或申请人工导诊");
            boolean recommended = result.doctor() != null && doctorId.equals(result.doctor().id())
                    || result.candidates() != null && result.candidates().stream().anyMatch(candidate ->
                    candidate.doctor() != null && doctorId.equals(candidate.doctor().id()));
            if (!recommended)
                throw new ResponseStatusException(HttpStatus.CONFLICT, "该医生不属于当前分诊版本的推荐号源");
        }

        Doctor doctor = catalog.find(doctorId);
        if (doctor == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "模拟医生不存在");
        int updated = slots.decrementAvailableSlot(doctor.id(), doctor.date());
        if (updated != 1) throw new ResponseStatusException(HttpStatus.CONFLICT, "该模拟号源已约满");
        Integer remaining = slots.remaining(doctor.id(), doctor.date());
        Doctor snapshot = new Doctor(doctor.id(), doctor.name(), doctor.title(), doctor.department(), doctor.period(),
                doctor.date(), remaining == null ? 0 : remaining, doctor.total(), doctor.fee());
        String id = "SIM" + UUID.randomUUID().toString().replace("-", "").substring(0, 16).toUpperCase();
        LocalDateTime now = LocalDateTime.now();
        Appointment appointment = new Appointment(id, id, patient, snapshot, "模拟预约", sessionId, now);
        store.saveAppointment(appointment, idempotencyKey);
        return appointment;
    }
}
