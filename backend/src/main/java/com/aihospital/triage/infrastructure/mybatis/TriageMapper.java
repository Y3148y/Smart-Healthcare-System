package com.aihospital.triage.infrastructure.mybatis;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Mapper
public interface TriageMapper {
    @Select("SELECT id,title,preview,status,created_at,updated_at FROM triage_session WHERE patient_id=#{patient} ORDER BY updated_at DESC")
    List<Map<String, Object>> sessions(@Param("patient") String patient);

    @Insert("INSERT INTO triage_session(id,patient_id,title,preview,status,created_at,updated_at) VALUES(#{id},#{patient},#{title},#{preview},#{status},#{createdAt},#{updatedAt})")
    int insertSession(@Param("id") String id, @Param("patient") String patient, @Param("title") String title,
                      @Param("preview") String preview, @Param("status") String status,
                      @Param("createdAt") LocalDateTime createdAt, @Param("updatedAt") LocalDateTime updatedAt);

    @Insert("INSERT INTO triage_eligibility(session_id,confirmed_at) VALUES(#{sessionId},#{confirmedAt})")
    int insertEligibility(@Param("sessionId") String sessionId, @Param("confirmedAt") LocalDateTime confirmedAt);

    @Select("SELECT id,role,content,created_at FROM triage_message WHERE session_id=#{sessionId} ORDER BY created_at,id")
    List<Map<String, Object>> messages(@Param("sessionId") String sessionId);

    @Select("SELECT a.version_number,a.result_json,a.created_at,anchor.assistant_message_id FROM triage_assessment a LEFT JOIN triage_assessment_anchor anchor ON anchor.assessment_id=a.id WHERE a.session_id=#{sessionId} ORDER BY a.version_number")
    List<Map<String, Object>> assessments(@Param("sessionId") String sessionId);

    @Insert("INSERT INTO triage_message(id,session_id,role,content,created_at) VALUES(#{id},#{sessionId},#{role},#{content},#{createdAt})")
    int insertMessage(@Param("id") String id, @Param("sessionId") String sessionId,
                      @Param("role") String role, @Param("content") String content,
                      @Param("createdAt") LocalDateTime createdAt);

    @Update("UPDATE triage_session SET title=#{title},preview=#{preview},status=#{status},updated_at=#{updatedAt} WHERE id=#{id}")
    int updateSession(@Param("id") String id, @Param("title") String title, @Param("preview") String preview,
                      @Param("status") String status, @Param("updatedAt") LocalDateTime updatedAt);

    @Insert("INSERT INTO triage_assessment(id,session_id,version_number,result_json,created_at) VALUES(#{id},#{sessionId},#{version},#{resultJson},#{createdAt})")
    int insertAssessment(@Param("id") String id, @Param("sessionId") String sessionId,
                         @Param("version") int version, @Param("resultJson") String resultJson,
                         @Param("createdAt") LocalDateTime createdAt);

    @Insert("INSERT INTO triage_assessment_anchor(assessment_id,assistant_message_id) VALUES(#{assessmentId},#{messageId})")
    int insertAnchor(@Param("assessmentId") String assessmentId, @Param("messageId") String messageId);

    @Select("SELECT m.id,m.session_id,s.title,m.content,m.created_at FROM triage_message m JOIN triage_session s ON s.id=m.session_id WHERE s.patient_id=#{patient} AND m.role='USER' ORDER BY m.created_at DESC,m.id DESC")
    List<Map<String, Object>> timeline(@Param("patient") String patient);
}
