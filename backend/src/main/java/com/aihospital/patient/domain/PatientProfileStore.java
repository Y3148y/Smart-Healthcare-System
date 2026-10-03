package com.aihospital.patient.domain;
public interface PatientProfileStore {
    PatientProfile find(String patient);
    boolean save(String patient,PatientProfile profile,long expectedVersion);
}
