package com.aihospital.patient.api;
import com.aihospital.patient.application.PatientProfileService;
import com.aihospital.patient.domain.PatientProfile;
import com.aihospital.shared.security.RoleGuard;
import org.springframework.web.bind.annotation.*;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;
import java.util.Map;
@RestController @RequestMapping("/api/patient/profile")
public class PatientProfileController {
    private final PatientProfileService service;private final RoleGuard guard;
    public PatientProfileController(PatientProfileService service,RoleGuard guard){this.service=service;this.guard=guard;}
    @GetMapping public PatientProfile own(@RequestHeader(value="Authorization",required=false)String auth){return service.own(guard.require(auth,"PATIENT").subject());}
    @PutMapping public PatientProfile save(@RequestHeader(value="Authorization",required=false)String auth,@RequestBody PatientProfile.Edit edit){return service.saveOwn(guard.require(auth,"PATIENT").subject(),edit);}
    @ExceptionHandler(ResponseStatusException.class) public ResponseEntity<Map<String,String>> rejected(ResponseStatusException ex){return ResponseEntity.status(ex.getStatusCode()).body(Map.of("message",ex.getReason()==null?"请求被拒绝":ex.getReason()));}
}
