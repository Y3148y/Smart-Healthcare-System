package com.aihospital.booking.application;

import com.aihospital.shared.model.Models.Appointment;
import com.aihospital.shared.model.Models.TriageResult;
import com.aihospital.triage.application.TriageConversationService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;

@Service
public class BookingApplicationService {
    private final SimulationBookingService booking;
    private final TriageConversationService conversations;
    public BookingApplicationService(SimulationBookingService booking, TriageConversationService conversations) {
        this.booking = booking; this.conversations = conversations;
    }

    public Appointment book(String doctorId, String sessionId, String patient, String idempotencyKey) {
        String effectiveSession = sessionId == null ? "walk-in" : sessionId;
        if (!"walk-in".equals(effectiveSession)) {
            TriageResult result = conversations.latestResult(effectiveSession, patient);
            boolean primary = result != null && result.doctor() != null && result.doctor().id().equals(doctorId);
            boolean candidate = result != null && result.candidates() != null && result.candidates().stream()
                    .anyMatch(option -> option.doctor() != null && option.doctor().id().equals(doctorId));
            if (result == null || "紧急".equals(result.riskLevel())
                    || "待补充信息".equals(result.riskLevel()) || (!primary && !candidate))
                throw new ResponseStatusException(HttpStatus.CONFLICT, "该分诊结果不能预约此模拟号源");
        }
        return booking.book(doctorId, effectiveSession, patient, idempotencyKey);
    }

    public List<Appointment> appointments(String patient) { return booking.appointments(patient); }
}
