package com.aihospital.review.domain;
import com.aihospital.triage.domain.TriageRecords.HumanReview;
import java.util.List;
public interface ReviewStore {
    List<HumanReview> own(String patient);
    List<HumanReview> all();
    HumanReview find(String id);
    boolean transition(String id,String expected,String next);
}
