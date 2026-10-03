package com.aihospital.review.domain;
import com.aihospital.triage.domain.TriageRecords.HumanReview;
import java.time.LocalDateTime;
public final class ReviewRecords {
    private ReviewRecords() {}
    public record Summary(HumanReview request,String sessionTitle,String patientStatement,String riskLevel,
        String suggestedDepartment,String safetyTip,Integer assessmentVersion,LocalDateTime assessedAt,String source) {}
}
