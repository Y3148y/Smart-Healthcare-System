package com.aihospital.triage.api;

import com.aihospital.shared.security.RoleGuard;
import com.aihospital.triage.application.SessionFactStore;
import com.aihospital.triage.domain.IntentExtraction;
import org.springframework.web.bind.annotation.*;
import java.util.List;

/** Owner-only historical model observations. Not a medical record or current clinical state. */
@RestController
@RequestMapping("/api/triage/sessions")
public class PatientObservationController {
    private final SessionFactStore facts;
    private final RoleGuard guard;
    public PatientObservationController(SessionFactStore facts,RoleGuard guard){this.facts=facts;this.guard=guard;}
    public record ObservationView(String sessionId,long stateVersion,String status,String clinicalReview,
            List<IntentExtraction.SymptomEvent> observations,List<IntentExtraction.Conflict> unresolvedConflicts){}
    @GetMapping("/{id}/observations")
    public ObservationView observations(@PathVariable String id,
            @RequestHeader(value="Authorization",required=false)String authorization){
        String patient=guard.require(authorization,"PATIENT").subject();
        // The store repeats owner checks and publishes COMPLETED turns only.
        var snapshot=facts.completed(patient,id);
        return new ObservationView(id,snapshot.version(),snapshot.version()==0?"NO_COMPLETED_SNAPSHOT":"COMPLETED_OBSERVATIONS",
                "NOT_REVIEWED",snapshot.events(),snapshot.conflicts());
    }
}
