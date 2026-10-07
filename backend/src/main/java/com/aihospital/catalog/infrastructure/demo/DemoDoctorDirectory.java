package com.aihospital.catalog.infrastructure.demo;

import com.aihospital.catalog.domain.DoctorDirectory;
import com.aihospital.catalog.domain.DepartmentAvailability;
import com.aihospital.shared.model.Models.Doctor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class DemoDoctorDirectory implements DoctorDirectory {
    private final Map<String, Doctor> doctors = new LinkedHashMap<>();
    private final java.util.Set<String> departments = new java.util.HashSet<>();
    public DemoDoctorDirectory() {
        add("d1","陈静","副主任医师","呼吸内科","上午",80);
        add("d2","张伟","主任医师","消化内科","上午",60);
        add("d3","王芳","副主任医师","心血管内科","下午",80);
        add("d4","刘强","主治医师","骨科","下午",50);
        add("d5","李敏","主治医师","神经内科","上午",60);
        add("d6","周宁","主治医师","全科医学科","下午",40);
        add("d7","赵琳","主治医师","妇科","上午",60);
    }
    private void add(String id, String name, String title, String department, String period, int fee) {
        departments.add(department);
        doctors.put(id, new Doctor(id, name, title, department, period,
                LocalDate.now().plusDays(1).toString(), 20, 20, fee));
    }
    @Override public List<Doctor> doctors(String department) {
        return doctors.values().stream().filter(doctor -> department == null || department.isBlank()
                || "全部".equals(department) || doctor.department().equals(department)).toList();
    }
    @Override public DepartmentAvailability departmentAvailability(String department) {
        if (!departments.contains(department)) return new DepartmentAvailability(department,
                DepartmentAvailability.Status.DEPARTMENT_NOT_CONFIGURED, 0, 0, 0);
        List<Doctor> active = doctors(department);
        List<Doctor> scheduled = active.stream().filter(d -> !LocalDate.parse(d.date()).isBefore(LocalDate.now())).toList();
        int bookable = (int) scheduled.stream().filter(d -> d.remaining() > 0).count();
        var status = active.isEmpty() ? DepartmentAvailability.Status.NO_ACTIVE_DOCTOR
                : scheduled.isEmpty() ? DepartmentAvailability.Status.NO_MATCHING_SCHEDULE
                : bookable == 0 ? DepartmentAvailability.Status.NO_SLOTS : DepartmentAvailability.Status.AVAILABLE;
        return new DepartmentAvailability(department, status, active.size(), scheduled.size(), bookable);
    }
}
