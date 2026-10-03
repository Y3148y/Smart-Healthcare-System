package com.aihospital.patient.infrastructure.mybatis;
import com.aihospital.patient.domain.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Repository;
@Repository
public class MybatisPatientProfileStore implements PatientProfileStore {
    private final PatientProfileMapper mapper;
    public MybatisPatientProfileStore(PatientProfileMapper mapper){this.mapper=mapper;}
    public PatientProfile find(String patient){return mapper.find(patient);}
    public boolean save(String patient,PatientProfile p,long expected){
        if(expected>0)return mapper.update(patient,p,expected)==1;
        try{return mapper.insert(patient,p)==1;}catch(DuplicateKeyException ex){return false;}
    }
}
