package com.aihospital.review.domain;

/** Access metadata only; no patient text, prompt, token, or summary body. */
public interface ReviewAccessAudit {
    void record(String actor, String requestId, String outcome);
}
