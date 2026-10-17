package com.aihospital.triage.infrastructure.mybatis;

import org.apache.ibatis.annotations.*;
import java.time.LocalDateTime;
import java.util.Map;

@Mapper
public interface SessionFactMapper {
    @Select("SELECT id FROM triage_session WHERE id=#{session} AND patient_id=#{patient} FOR UPDATE")
    String lockSession(@Param("session") String session,@Param("patient") String patient);
    @Select("SELECT CURRENT_TIMESTAMP") LocalDateTime now();
    @Select("SELECT f.facts_json,f.facts_hash,f.state_version,f.schema_version,f.source_window_hash FROM triage_fact_snapshot f JOIN triage_turn t ON t.id=f.turn_id WHERE f.session_id=#{session} AND t.status='COMPLETED' ORDER BY f.state_version DESC LIMIT 1")
    Map<String,Object> completed(String session);
    @Select("SELECT facts_json,facts_hash,state_version,schema_version,source_window_hash FROM triage_fact_snapshot WHERE turn_id=#{turn}")
    Map<String,Object> forTurn(String turn);
    @Insert("INSERT INTO triage_fact_snapshot(turn_id,session_id,state_version,schema_version,source_window_hash,facts_hash,facts_json) VALUES(#{turn},#{session},#{version},1,#{sourceHash},#{factsHash},#{facts})")
    int insert(@Param("turn") String turn,@Param("session") String session,@Param("version") long version,
               @Param("sourceHash") String sourceHash,@Param("factsHash") String factsHash,@Param("facts") String facts);
}
