package com.aihospital.observation.infrastructure.mybatis;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

@Mapper
public interface OverviewMapper {
    @Select("SELECT COUNT(*) FROM triage_session") int sessions();
    @Select("SELECT COUNT(*) FROM sim_appointment") int appointments();
    @Select("SELECT COUNT(*) FROM triage_session WHERE status='已完成分诊'") int completedSessions();
    @Select("SELECT COUNT(DISTINCT session_id) FROM sim_appointment WHERE session_id<>'walk-in'") int triageAppointments();
    @Select("SELECT COUNT(*) FROM agent_call_log WHERE purpose IN ('symptom_tag_search','medical_knowledge_retrieve','department_search','doctor_schedule_search')") int toolCalls();
}
