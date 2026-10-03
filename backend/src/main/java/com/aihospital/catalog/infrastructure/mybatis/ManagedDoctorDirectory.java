package com.aihospital.catalog.infrastructure.mybatis;
import com.aihospital.catalog.application.CatalogManagementService;
import com.aihospital.catalog.domain.DoctorDirectory;
import com.aihospital.shared.model.Models.Doctor;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Repository;
import java.util.List;

@Primary @Repository
public class ManagedDoctorDirectory implements DoctorDirectory {
    private final CatalogManagementService service;
    public ManagedDoctorDirectory(CatalogManagementService service){this.service=service;}
    @Override public List<Doctor> doctors(String department){return service.available(department);}
}
