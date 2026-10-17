package com.aihospital.booking.infrastructure.mybatis;

import com.aihospital.booking.domain.PendingBooking;
import org.apache.ibatis.annotations.*;
import java.time.LocalDateTime;

@Mapper
public interface PendingBookingMapper {
    String COLUMNS="id,patient,request_key AS requestKey,session_id AS sessionId,doctor_id AS doctorId,doctor_fingerprint AS doctorFingerprint,state_version AS stateVersion,assessment_version AS assessmentVersion,expires_at AS expiresAt,status";
    @Select("SELECT "+COLUMNS+" FROM pending_booking_action WHERE patient=#{patient} AND request_key=#{key} FOR UPDATE")
    PendingBooking byKey(@Param("patient") String patient, @Param("key") String key);
    @Select("SELECT "+COLUMNS+" FROM pending_booking_action WHERE id=#{id} AND patient=#{patient} FOR UPDATE")
    PendingBooking lock(@Param("id") String id, @Param("patient") String patient);
    @Select("SELECT "+COLUMNS+" FROM pending_booking_action WHERE id=#{id} AND patient=#{patient}")
    PendingBooking find(@Param("id") String id, @Param("patient") String patient);
    @Select("SELECT id FROM triage_session WHERE id=#{id} AND patient_id=#{patient} FOR UPDATE")
    String lockSession(@Param("id") String id, @Param("patient") String patient);
    @Select("SELECT d.id FROM catalog_doctor d JOIN catalog_department p ON p.id=d.department_id WHERE d.id=#{id} FOR UPDATE")
    String lockDoctor(String id);
    @Select("SELECT CURRENT_TIMESTAMP") LocalDateTime now();
    @Insert("INSERT INTO pending_booking_action(id,patient,request_key,session_id,doctor_id,doctor_fingerprint,state_version,assessment_version,expires_at,status) VALUES(#{id},#{patient},#{requestKey},#{sessionId},#{doctorId},#{doctorFingerprint},#{stateVersion},#{assessmentVersion},#{expiresAt},#{status}) ON DUPLICATE KEY UPDATE id=id")
    int insert(PendingBooking action);
    @Update("UPDATE pending_booking_action SET status=#{status} WHERE id=#{id} AND status='PENDING'")
    int transition(@Param("id") String id, @Param("status") String status);
}
