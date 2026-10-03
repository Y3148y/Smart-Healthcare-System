package com.aihospital.catalog.api;
import com.aihospital.catalog.application.CatalogManagementService;
import com.aihospital.catalog.domain.CatalogRecords.*;
import com.aihospital.shared.security.RoleGuard;
import org.springframework.web.bind.annotation.*;
import java.util.List;
import java.util.Map;
import org.springframework.http.ResponseEntity;
import org.springframework.web.server.ResponseStatusException;

@RestController @RequestMapping("/api/admin/catalog")
public class AdminCatalogController {
    private final CatalogManagementService service;private final RoleGuard guard;
    public AdminCatalogController(CatalogManagementService service,RoleGuard guard){this.service=service;this.guard=guard;}
    @GetMapping("/departments") public List<Department> departments(@RequestHeader(value="Authorization",required=false)String auth){guard.require(auth,"ADMIN");return service.departments();}
    @PostMapping("/departments") public Department addDepartment(@RequestHeader(value="Authorization",required=false)String auth,@RequestBody DepartmentEdit edit){guard.require(auth,"ADMIN");return service.saveDepartment(null,edit);}
    @PutMapping("/departments/{id}") public Department updateDepartment(@RequestHeader(value="Authorization",required=false)String auth,@PathVariable String id,@RequestBody DepartmentEdit edit){guard.require(auth,"ADMIN");return service.saveDepartment(id,edit);}
    @GetMapping("/doctors") public List<ManagedDoctor> doctors(@RequestHeader(value="Authorization",required=false)String auth){guard.require(auth,"ADMIN");return service.doctors();}
    @PostMapping("/doctors") public ManagedDoctor addDoctor(@RequestHeader(value="Authorization",required=false)String auth,@RequestBody DoctorEdit edit){guard.require(auth,"ADMIN");return service.saveDoctor(null,edit);}
    @PutMapping("/doctors/{id}") public ManagedDoctor updateDoctor(@RequestHeader(value="Authorization",required=false)String auth,@PathVariable String id,@RequestBody DoctorEdit edit){guard.require(auth,"ADMIN");return service.saveDoctor(id,edit);}
    @ExceptionHandler(ResponseStatusException.class)
    public ResponseEntity<Map<String,String>> rejected(ResponseStatusException ex){
        return ResponseEntity.status(ex.getStatusCode()).body(Map.of("message",ex.getReason()==null?"操作被拒绝":ex.getReason()));
    }
}
