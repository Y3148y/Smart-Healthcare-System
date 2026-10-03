package com.aihospital.catalog.infrastructure.mybatis;

import com.aihospital.catalog.domain.CatalogRecords.*;
import org.apache.ibatis.annotations.*;
import java.util.List;

@Mapper
public interface CatalogMapper {
    @Select("SELECT id,name,enabled FROM catalog_department ORDER BY name,id")
    List<Department> departments();
    @Insert("INSERT INTO catalog_department(id,name,enabled) VALUES(#{id},#{name},#{enabled})")
    int addDepartment(Department d);
    @Update("UPDATE catalog_department SET name=#{name},enabled=#{enabled} WHERE id=#{id}")
    int updateDepartment(Department d);
    @Select("SELECT id FROM catalog_department WHERE id=#{id} FOR UPDATE")
    String lockDepartment(String id);
    @Select("SELECT d.id,d.name,d.title,d.department_id AS departmentId,p.name AS department,d.enabled,d.slot_date AS date,d.period,COALESCE(s.total,0) AS total,COALESCE(s.remaining,0) AS remaining,d.fee FROM catalog_doctor d JOIN catalog_department p ON p.id=d.department_id LEFT JOIN sim_slot s ON s.doctor_id=d.id AND s.slot_date=d.slot_date ORDER BY d.id")
    List<ManagedDoctor> doctors();
    @Select("SELECT id FROM catalog_doctor WHERE id=#{id} FOR UPDATE")
    String lockDoctor(String id);
    @Select("SELECT total-remaining FROM sim_slot WHERE doctor_id=#{id} AND slot_date=#{date} FOR UPDATE")
    Integer lockBookedCount(@Param("id") String id,@Param("date") String date);
    @Insert("INSERT INTO catalog_doctor(id,name,title,department_id,enabled,slot_date,period,fee) VALUES(#{id},#{edit.name},#{edit.title},#{edit.departmentId},#{edit.enabled},#{edit.date},#{edit.period},#{edit.fee})")
    int addDoctor(@Param("id") String id,@Param("edit") DoctorEdit edit);
    @Update("UPDATE catalog_doctor SET name=#{edit.name},title=#{edit.title},department_id=#{edit.departmentId},enabled=#{edit.enabled},slot_date=#{edit.date},period=#{edit.period},fee=#{edit.fee} WHERE id=#{id}")
    int updateDoctor(@Param("id") String id,@Param("edit") DoctorEdit edit);
    @Update("UPDATE sim_slot SET remaining=remaining+(#{total}-total),total=#{total} WHERE doctor_id=#{id} AND slot_date=#{date} AND #{total}>=total-remaining")
    int resizeSlot(@Param("id") String id,@Param("date") String date,@Param("total") int total);
}
