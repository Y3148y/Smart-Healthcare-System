package com.aihospital.catalog.api;

import com.aihospital.catalog.application.DoctorCatalogService;
import com.aihospital.shared.model.Models.Doctor;
import org.springframework.web.bind.annotation.*;
import java.util.List;

@RestController
@RequestMapping("/api/doctors")
public class DoctorController {
    private final DoctorCatalogService catalog;
    public DoctorController(DoctorCatalogService catalog) { this.catalog = catalog; }
    @GetMapping public List<Doctor> doctors(@RequestParam(required = false) String department) {
        return catalog.doctors(department);
    }
}
