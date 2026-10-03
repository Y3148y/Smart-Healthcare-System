package com.aihospital.catalog.infrastructure.mybatis;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

@Mapper
public interface SlotMapper {
    @Select("SELECT COUNT(*) FROM sim_slot WHERE doctor_id=#{doctorId} AND slot_date=#{slotDate}")
    int slotCount(@Param("doctorId") String doctorId, @Param("slotDate") String slotDate);

    @Insert("INSERT INTO sim_slot(doctor_id,slot_date,remaining,total) VALUES(#{doctorId},#{slotDate},#{remaining},#{total})")
    int insertSlot(@Param("doctorId") String doctorId, @Param("slotDate") String slotDate,
                   @Param("remaining") int remaining, @Param("total") int total);

    @Select("SELECT remaining FROM sim_slot WHERE doctor_id=#{doctorId} AND slot_date=#{slotDate}")
    Integer remaining(@Param("doctorId") String doctorId, @Param("slotDate") String slotDate);

    @Update("UPDATE sim_slot SET remaining=remaining-1 WHERE doctor_id=#{doctorId} AND slot_date=#{slotDate} AND remaining>0 AND EXISTS (SELECT 1 FROM catalog_doctor d JOIN catalog_department p ON p.id=d.department_id WHERE d.id=#{doctorId} AND d.slot_date=#{slotDate} AND d.enabled=true AND p.enabled=true)")
    int decrementAvailableSlot(@Param("doctorId") String doctorId, @Param("slotDate") String slotDate);

    @Update("UPDATE sim_slot SET remaining=#{remaining} WHERE doctor_id=#{doctorId} AND slot_date=#{slotDate}")
    int setRemaining(@Param("doctorId") String doctorId, @Param("slotDate") String slotDate,
                     @Param("remaining") int remaining);

}
