package com.aihospital.patient.application;
import com.aihospital.patient.domain.*;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.time.*;
@Service
public class PatientProfileService {
    private final PatientProfileStore store;
    public PatientProfileService(PatientProfileStore store){this.store=store;}
    public PatientProfile own(String patient){var p=store.find(patient);return p==null?PatientProfile.empty():p;}
    public PatientProfile saveOwn(String patient,PatientProfile.Edit edit){
        if(edit==null||!edit.selfReportConfirmed())throw bad("请确认资料为本人自述，未经过医院核验");
        String name=clean(edit.displayName(),80);if(name.isEmpty())throw bad("请填写称呼");
        if(edit.version()<0||edit.version()==Long.MAX_VALUE)throw bad("资料版本无效");
        String birth=null;
        if(edit.birthDate()!=null&&!edit.birthDate().isBlank()){
            LocalDate date;try{date=LocalDate.parse(edit.birthDate());}catch(Exception ex){throw bad("出生日期格式无效");}
            if(date.isAfter(LocalDate.now().minusYears(18))||date.isBefore(LocalDate.now().minusYears(120)))throw bad("当前资料功能仅支持18至120周岁成年人本人");
            birth=date.toString();
        }
        var p=new PatientProfile(name,birth,clean(edit.allergies(),1000),clean(edit.medications(),1000),
                clean(edit.healthBackground(),1000),edit.version()+1,LocalDateTime.now(),PatientProfile.SOURCE);
        if(!store.save(patient,p,edit.version()))throw new ResponseStatusException(HttpStatus.CONFLICT,"资料已在其他页面更新，请重新加载后核对再保存");
        return own(patient);
    }
    private static String clean(String v,int max){if(v==null)return "";v=v.trim();if(v.length()>max)throw bad("资料字段不能超过"+max+"字");return v;}
    private static ResponseStatusException bad(String reason){return new ResponseStatusException(HttpStatus.BAD_REQUEST,reason);}
}
