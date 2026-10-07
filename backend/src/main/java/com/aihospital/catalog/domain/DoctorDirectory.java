package com.aihospital.catalog.domain;

import com.aihospital.shared.model.Models.Doctor;
import java.util.List;

/** Demo catalogue now; a hospital schedule adapter can implement this later. */
public interface DoctorDirectory {
    List<Doctor> doctors(String department);

    /** Adapters must query the department catalogue, not infer existence from bookable doctors. */
    default DepartmentAvailability departmentAvailability(String department) {
        throw new UnsupportedOperationException("该目录适配器未实现科室状态查询");
    }
}
