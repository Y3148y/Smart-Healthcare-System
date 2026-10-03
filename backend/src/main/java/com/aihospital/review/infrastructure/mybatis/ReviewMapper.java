package com.aihospital.review.infrastructure.mybatis;
import org.apache.ibatis.annotations.*;
import java.util.List;
import java.util.Map;
@Mapper
public interface ReviewMapper {
    @Select("SELECT id,session_id,patient_id,reason,status,created_at FROM human_review_request WHERE patient_id=#{patient} ORDER BY created_at DESC,id")
    List<Map<String,Object>> own(String patient);
    @Select("SELECT id,session_id,patient_id,reason,status,created_at FROM human_review_request WHERE id=#{id}")
    Map<String,Object> find(String id);
    @Update("UPDATE human_review_request SET status=#{next} WHERE id=#{id} AND status=#{expected}")
    int transition(@Param("id")String id,@Param("expected")String expected,@Param("next")String next);
}
