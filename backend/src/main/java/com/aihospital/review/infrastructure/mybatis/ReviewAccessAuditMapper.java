package com.aihospital.review.infrastructure.mybatis;

import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import java.time.LocalDateTime;

@Mapper
public interface ReviewAccessAuditMapper {
    @Insert("INSERT INTO review_access_audit(id,actor,request_id,outcome,accessed_at) VALUES(#{id},#{actor},#{request},#{outcome},#{time})")
    int insert(@Param("id") String id, @Param("actor") String actor, @Param("request") String request,
               @Param("outcome") String outcome, @Param("time") LocalDateTime time);
}
