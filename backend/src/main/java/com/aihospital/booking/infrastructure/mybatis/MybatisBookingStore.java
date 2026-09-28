package com.aihospital.booking.infrastructure.mybatis;

import com.aihospital.booking.domain.BookingStore;
import com.aihospital.shared.model.Models.Appointment;
import com.aihospital.shared.model.Models.Doctor;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static com.aihospital.shared.infrastructure.mybatis.RowValues.*;

@Repository
public class MybatisBookingStore implements BookingStore {
    private final AppointmentMapper mapper;
    private final ObjectMapper json;

    public MybatisBookingStore(AppointmentMapper mapper, ObjectMapper json) {
        this.mapper = mapper;
        this.json = json;
    }

    @Override public List<Appointment> appointments(String patient) {
        return mapper.appointments(patient).stream().map(this::appointmentFromRow).toList();
    }
    @Override public Optional<Appointment> appointmentByKey(String patient, String key) {
        return mapper.appointmentByKey(patient, key).stream().findFirst().map(this::appointmentFromRow);
    }
    @Override public void saveAppointment(Appointment appointment, String key) {
        try {
            mapper.insertAppointment(appointment.id(), appointment.patient(), appointment.doctor().id(),
                    appointment.triageSessionId(), json.writeValueAsString(appointment.doctor()),
                    appointment.status(), key, appointment.createdAt());
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("无法保存模拟预约", ex);
        }
    }

    private Appointment appointmentFromRow(Map<String, Object> row) {
        String id = string(row, "id");
        try {
            return new Appointment(id, id, string(row, "patient_id"),
                    json.readValue(string(row, "doctor_json"), Doctor.class), string(row, "status"),
                    string(row, "session_id"), dateTime(row, "created_at"));
        } catch (JsonProcessingException ex) {
            throw new IllegalStateException("模拟预约记录损坏", ex);
        }
    }
}
