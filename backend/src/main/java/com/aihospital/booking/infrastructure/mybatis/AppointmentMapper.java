package com.aihospital.booking.infrastructure.mybatis;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Mapper
public interface AppointmentMapper {
    @Select("SELECT id,patient_id,session_id,doctor_json,status,created_at FROM sim_appointment WHERE patient_id=#{patient} ORDER BY created_at DESC")
    List<Map<String, Object>> appointments(@Param("patient") String patient);

    @Select("SELECT id,patient_id,session_id,doctor_json,status,created_at FROM sim_appointment WHERE patient_id=#{patient} AND idempotency_key=#{key}")
    List<Map<String, Object>> appointmentByKey(@Param("patient") String patient, @Param("key") String key);

    @Insert("INSERT INTO sim_appointment(id,patient_id,doctor_id,session_id,doctor_json,status,idempotency_key,created_at) VALUES(#{id},#{patient},#{doctorId},#{sessionId},#{doctorJson},#{status},#{key},#{createdAt})")
    int insertAppointment(@Param("id") String id, @Param("patient") String patient,
                          @Param("doctorId") String doctorId, @Param("sessionId") String sessionId,
                          @Param("doctorJson") String doctorJson, @Param("status") String status,
                          @Param("key") String key, @Param("createdAt") LocalDateTime createdAt);
}
