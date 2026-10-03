package com.aihospital.review.api;
import com.aihospital.review.application.HumanReviewService;
import com.aihospital.shared.security.RoleGuard;
import com.aihospital.triage.domain.TriageRecords.HumanReview;
import org.springframework.web.bind.annotation.*;
import java.util.List;
@RestController @RequestMapping("/api/patient/human-reviews")
public class PatientReviewController {
    private final HumanReviewService service;private final RoleGuard guard;
    public PatientReviewController(HumanReviewService service,RoleGuard guard){this.service=service;this.guard=guard;}
    @GetMapping public List<HumanReview> own(@RequestHeader(value="Authorization",required=false)String auth){return service.own(guard.require(auth,"PATIENT").subject());}
}
