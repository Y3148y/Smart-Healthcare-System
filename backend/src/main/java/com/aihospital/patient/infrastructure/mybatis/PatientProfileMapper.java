package com.aihospital.patient.infrastructure.mybatis;
import com.aihospital.patient.domain.PatientProfile;
import org.apache.ibatis.annotations.*;

@Mapper
public interface PatientProfileMapper {
    @Select("SELECT display_name AS displayName,birth_date AS birthDate,allergies,medications,health_background AS healthBackground,version_number AS version,updated_at AS updatedAt,'PATIENT_SELF_REPORT' AS source FROM patient_profile WHERE patient_id=#{patient}")
    PatientProfile find(String patient);
    @Insert("INSERT INTO patient_profile(patient_id,display_name,birth_date,allergies,medications,health_background,version_number,updated_at) VALUES(#{patient},#{p.displayName},#{p.birthDate},#{p.allergies},#{p.medications},#{p.healthBackground},#{p.version},#{p.updatedAt})")
    int insert(@Param("patient")String patient,@Param("p")PatientProfile p);
    @Update("UPDATE patient_profile SET display_name=#{p.displayName},birth_date=#{p.birthDate},allergies=#{p.allergies},medications=#{p.medications},health_background=#{p.healthBackground},version_number=#{p.version},updated_at=#{p.updatedAt} WHERE patient_id=#{patient} AND version_number=#{expected}")
    int update(@Param("patient")String patient,@Param("p")PatientProfile p,@Param("expected")long expected);
}
