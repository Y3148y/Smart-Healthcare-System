package com.aihospital.patient.domain;
import java.time.LocalDateTime;

/** Patient-confirmed self report, never a diagnosis or verified hospital record. */
public record PatientProfile(String displayName,String birthDate,String allergies,String medications,
                             String healthBackground,long version,LocalDateTime updatedAt,String source) {
    public static final String SOURCE="PATIENT_SELF_REPORT";
    public static PatientProfile empty(){return new PatientProfile("",null,"","","",0,null,SOURCE);}
    public record Edit(String displayName,String birthDate,String allergies,String medications,
                       String healthBackground,long version,boolean selfReportConfirmed) {}
}
