package com.aihospital.catalog.domain;

/** Catalogue capability, independent of medical evidence and appointment eligibility. */
public record DepartmentAvailability(String department, Status status, int activeDoctors,
                                     int scheduledDoctors, int bookableDoctors) {
    public enum Status {
        DEPARTMENT_NOT_CONFIGURED, DEPARTMENT_DISABLED, NO_ACTIVE_DOCTOR,
        NO_MATCHING_SCHEDULE, NO_SLOTS, AVAILABLE
    }

    public boolean exists() { return status != Status.DEPARTMENT_NOT_CONFIGURED; }
    public boolean enabled() { return exists() && status != Status.DEPARTMENT_DISABLED; }

    public String message() {
        return switch (status) {
            case DEPARTMENT_NOT_CONFIGURED -> "系统尚未配置该科室，暂不能提供该方向的模拟预约";
            case DEPARTMENT_DISABLED -> "该科室已停用，暂不能提供模拟预约";
            case NO_ACTIVE_DOCTOR -> "科室已配置，但暂无启用的医生";
            case NO_MATCHING_SCHEDULE -> "科室有医生，但暂无今日或未来的模拟排班";
            case NO_SLOTS -> "科室有模拟排班，但暂无剩余号源";
            case AVAILABLE -> "科室有可查询的模拟医生和号源；能否预约仍需校验分诊资格";
        };
    }
}
