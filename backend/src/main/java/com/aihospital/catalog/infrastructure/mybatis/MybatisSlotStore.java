package com.aihospital.catalog.infrastructure.mybatis;

import com.aihospital.catalog.domain.SlotStore;
import com.aihospital.shared.model.Models.Doctor;
import org.springframework.stereotype.Repository;

@Repository
public class MybatisSlotStore implements SlotStore {
    private final SlotMapper mapper;
    public MybatisSlotStore(SlotMapper mapper) { this.mapper = mapper; }
    @Override public int slotCount(String doctorId, String date) { return mapper.slotCount(doctorId, date); }
    @Override public void createSlot(Doctor doctor) {
        mapper.insertSlot(doctor.id(), doctor.date(), doctor.remaining(), doctor.total());
    }
    @Override public Integer remaining(String doctorId, String date) { return mapper.remaining(doctorId, date); }
    @Override public int decrementAvailableSlot(String doctorId, String date) {
        return mapper.decrementAvailableSlot(doctorId, date);
    }
}
