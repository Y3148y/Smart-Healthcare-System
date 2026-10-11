package com.aihospital.triage.application;

import com.aihospital.triage.domain.IntentExtraction;

public interface IntentExtractor {
    Result extract(IntentInput input);
    enum Status { ACCEPTED, INVALID_OUTPUT, UNAVAILABLE, NOT_CONFIGURED }
    record Result(Status status, IntentExtraction extraction, String model, String failureCode,
                  Integer inputTokens, Integer outputTokens, long elapsedMs) {
        public Result {
            if ((status == Status.ACCEPTED) != (extraction != null))
                throw new IllegalArgumentException("Accepted intent requires a validated extraction");
        }
    }
}
