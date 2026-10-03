package com.aihospital.catalog.domain;

public final class CatalogRecords {
    private CatalogRecords() {}
    public record Department(String id,String name,boolean enabled) {}
    public record DepartmentEdit(String name,boolean enabled) {}
    public record ManagedDoctor(String id,String name,String title,String departmentId,String department,
                                boolean enabled,String date,String period,int total,int remaining,int fee) {}
    public record DoctorEdit(String name,String title,String departmentId,boolean enabled,
                             String date,String period,int total,int fee) {}
}
