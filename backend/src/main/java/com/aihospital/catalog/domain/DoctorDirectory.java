package com.aihospital.catalog.domain;

import com.aihospital.shared.model.Models.Doctor;
import java.util.List;

/** Demo catalogue now; a hospital schedule adapter can implement this later. */
public interface DoctorDirectory {
    List<Doctor> doctors(String department);
}
