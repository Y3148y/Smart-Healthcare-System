package com.aihospital.review.infrastructure.mybatis;

import com.aihospital.review.domain.ReviewAccessAudit;
import org.springframework.stereotype.Repository;
import java.time.LocalDateTime;
import java.util.UUID;

@Repository
public class MybatisReviewAccessAudit implements ReviewAccessAudit {
    private final ReviewAccessAuditMapper mapper;
    public MybatisReviewAccessAudit(ReviewAccessAuditMapper mapper) { this.mapper = mapper; }
    public void record(String actor, String requestId, String outcome) {
        if (mapper.insert(UUID.randomUUID().toString(), actor, requestId, outcome, LocalDateTime.now()) != 1)
            throw new IllegalStateException("Access audit was not persisted");
    }
}
