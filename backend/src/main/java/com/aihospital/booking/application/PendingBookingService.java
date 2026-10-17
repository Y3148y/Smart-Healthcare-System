package com.aihospital.booking.application;

import com.aihospital.booking.domain.PendingBooking;
import com.aihospital.booking.infrastructure.mybatis.PendingBookingMapper;
import com.aihospital.catalog.application.DoctorCatalogService;
import com.aihospital.shared.model.Models.Appointment;
import com.aihospital.shared.model.Models.Doctor;
import com.aihospital.triage.application.TriageConversationService;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.UUID;

/** Confirmation binds server parameters and the latest session; no tool can invoke this service. */
@Service
@Transactional(isolation = Isolation.READ_COMMITTED)
public class PendingBookingService {
    private final PendingBookingMapper actions;
    private final DoctorCatalogService catalog;
    private final TriageConversationService triage;
    private final SimulationBookingService booking;
    private final int ttlSeconds;
    public PendingBookingService(PendingBookingMapper actions, DoctorCatalogService catalog,
            TriageConversationService triage, SimulationBookingService booking,
            @Value("${ai.booking.action-ttl-seconds:300}") int ttlSeconds) {
        if (ttlSeconds < 30 || ttlSeconds > 1800) throw new IllegalArgumentException("Invalid booking action TTL");
        this.actions=actions; this.catalog=catalog; this.triage=triage; this.booking=booking; this.ttlSeconds=ttlSeconds;
    }

    public PendingBooking.View prepare(String patient, String doctorId, String sessionId, String requestKey) {
        identifier(doctorId,100); identifier(sessionId,100); identifier(requestKey,64);
        var version=version(patient,sessionId);
        Doctor proposed=doctor(doctorId);
        actions.insert(new PendingBooking(UUID.randomUUID().toString(),patient,requestKey,sessionId,doctorId,
                fingerprint(proposed),version[0],(int)version[1],actions.now().plusSeconds(ttlSeconds),"PENDING"));
        var action=actions.byKey(patient,requestKey);
        if (!action.doctorId().equals(doctorId) || !action.sessionId().equals(sessionId))
            throw conflict("准备请求标识已用于其他预约参数");
        actions.lockDoctor(doctorId);
        booking.validateEligibility(doctorId,sessionId,patient);
        Doctor doctor=doctor(doctorId);
        if (doctor.remaining() <= 0) throw conflict("该模拟号源已约满");
        if (!"PENDING".equals(action.status()) || !action.expiresAt().isAfter(actions.now())
                || action.stateVersion()!=version[0] || action.assessmentVersion()!=version[1]
                || !action.doctorFingerprint().equals(fingerprint(doctor)))
            throw conflict("待确认预约已变化或失效，请重新选择");
        return new PendingBooking.View(action.id(),sessionId,doctor,action.stateVersion(),action.assessmentVersion(),
                action.expiresAt(),action.status());
    }

    public Appointment confirm(String patient,String actionId,boolean confirmed) {
        identifier(actionId,64);
        if (!confirmed) throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"须明确确认本次模拟预约");
        var observed=find(patient,actionId);
        if ("CONFIRMED".equals(observed.status()))
            return booking.replayClaim(observed.doctorId(),observed.sessionId(),patient,observed.id());
        // All paths lock the session before the action, preventing inversion against preparation.
        var version=version(patient,observed.sessionId());
        var action=actions.lock(actionId,patient);
        if ("CONFIRMED".equals(action.status()))
            return booking.replayClaim(action.doctorId(),action.sessionId(),patient,action.id());
        if (!"PENDING".equals(action.status()) || !action.expiresAt().isAfter(actions.now()))
            throw conflict("待确认预约已取消或过期，请重新选择");
        if (action.stateVersion()!=version[0] || action.assessmentVersion()!=version[1])
            throw conflict("会话已有新信息，请重新核对预约建议");
        actions.lockDoctor(action.doctorId());
        if (!action.doctorFingerprint().equals(fingerprint(doctor(action.doctorId()))))
            throw conflict("医生或排班已变化，请重新选择");
        Appointment receipt=booking.book(action.doctorId(),action.sessionId(),patient,action.id());
        if (actions.transition(action.id(),"CONFIRMED")!=1) throw conflict("预约动作状态冲突");
        return receipt;
    }

    public void cancel(String patient,String actionId) {
        identifier(actionId,64);
        find(patient,actionId);
        var action=actions.lock(actionId,patient);
        if ("CONFIRMED".equals(action.status())) throw conflict("已确认预约不能作为待办取消");
        if ("PENDING".equals(action.status())) actions.transition(actionId,"CANCELLED");
    }

    private PendingBooking find(String patient,String id) {
        var action=actions.find(id,patient);
        if (action==null) throw new ResponseStatusException(HttpStatus.NOT_FOUND,"待确认预约不存在");
        return action;
    }
    private long[] version(String patient,String session) {
        if ("walk-in".equals(session)) return new long[]{0,0};
        if (actions.lockSession(session,patient)==null)
            throw new ResponseStatusException(HttpStatus.NOT_FOUND,"会话不存在");
        var conversation=triage.conversation(session,patient);
        if (conversation.latestTurn()!=null && "RUNNING".equals(conversation.latestTurn().status()))
            throw conflict("会话仍在处理中，请等待本轮结果");
        return new long[]{conversation.messages().size(),conversation.assessments().stream()
                .mapToInt(a->a.version()).max().orElse(0)};
    }
    private Doctor doctor(String id) {
        return catalog.doctors(null).stream().filter(d->d.id().equals(id)).findFirst()
                .orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"模拟医生不存在或已停用"));
    }
    private String fingerprint(Doctor d) {
        try {
            // Length framing avoids delimiter collisions. Availability is rechecked, never frozen.
            var digest=MessageDigest.getInstance("SHA-256");
            for (var value:new Object[]{d.id(),d.name(),d.title(),d.department(),d.date(),d.period(),d.total(),d.fee()}) {
                var bytes=String.valueOf(value).getBytes(StandardCharsets.UTF_8);
                digest.update(java.nio.ByteBuffer.allocate(4).putInt(bytes.length).array()); digest.update(bytes);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) {throw new IllegalStateException(impossible);}
    }
    private void identifier(String value,int limit) {
        if (value==null || value.isBlank() || value.length()>limit || value.chars().anyMatch(Character::isISOControl))
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"缺少有效的预约动作参数");
    }
    private ResponseStatusException conflict(String message) {return new ResponseStatusException(HttpStatus.CONFLICT,message);}
}
