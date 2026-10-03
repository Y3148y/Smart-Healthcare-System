package com.aihospital.catalog.application;

import com.aihospital.catalog.domain.CatalogRecords.*;
import com.aihospital.catalog.infrastructure.demo.DemoDoctorDirectory;
import com.aihospital.catalog.infrastructure.mybatis.CatalogMapper;
import com.aihospital.catalog.infrastructure.mybatis.SlotMapper;
import com.aihospital.shared.model.Models.Doctor;
import jakarta.annotation.PostConstruct;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.HttpStatus;
import java.time.LocalDate;
import java.util.*;

@Service
public class CatalogManagementService {
    private final CatalogMapper mapper;
    private final SlotMapper slots;
    private final DemoDoctorDirectory seed;
    public CatalogManagementService(CatalogMapper mapper,SlotMapper slots,DemoDoctorDirectory seed) {
        this.mapper=mapper;this.slots=slots;this.seed=seed;
    }
    @PostConstruct public void initialize() {
        // Seed only a brand-new catalogue; never overwrite administrator edits on restart.
        if(!mapper.departments().isEmpty()) return;
        for(var d:seed.doctors(null)) {
            String dep="dep-"+d.id();
            mapper.addDepartment(new Department(dep,d.department(),true));
            mapper.addDoctor(d.id(),new DoctorEdit(d.name(),d.title(),dep,true,d.date(),d.period(),d.total(),d.fee()));
            if(slots.slotCount(d.id(),d.date())==0) slots.insertSlot(d.id(),d.date(),d.total(),d.total());
        }
    }
    public List<Department> departments(){return mapper.departments();}
    public List<ManagedDoctor> doctors(){return mapper.doctors();}
    @Transactional public Department saveDepartment(String id,DepartmentEdit edit) {
        if(edit==null) throw bad("请填写科室资料");
        String name=text(edit.name(),80,"科室名称");
        if(id!=null && mapper.lockDepartment(id)==null) throw missing();
        var d=new Department(id==null?UUID.randomUUID().toString():id,name,edit.enabled());
        try {if(id==null)mapper.addDepartment(d);else mapper.updateDepartment(d);}
        catch(DataIntegrityViolationException ex){throw new ResponseStatusException(HttpStatus.CONFLICT,"科室名称已存在");}
        return d;
    }
    @Transactional public ManagedDoctor saveDoctor(String id,DoctorEdit input) {
        if(input==null)throw bad("请填写医生资料");
        String name=text(input.name(),80,"医生姓名"),title=text(input.title(),80,"职称");
        LocalDate date;
        try { date=LocalDate.parse(input.date()); }catch(Exception ex){throw bad("请选择有效日期");}
        if(date.isAfter(LocalDate.now().plusDays(365)))throw bad("号源日期须在未来一年内（含今日）");
        if(input.period()==null||!Set.of("上午","下午","全天").contains(input.period()))throw bad("时段只能为上午、下午或全天");
        if(input.total()<0||input.total()>1000||input.fee()<0||input.fee()>100000)throw bad("号源容量须为0至1000，费用须为0至100000");
        if(input.departmentId()==null||mapper.lockDepartment(input.departmentId())==null)throw bad("科室不存在");
        if(input.enabled() && departments().stream().noneMatch(d->d.id().equals(input.departmentId())&&d.enabled()))throw bad("不能在停用科室启用医生");
        var edit=new DoctorEdit(name,title,input.departmentId(),input.enabled(),date.toString(),input.period(),input.total(),input.fee());
        if(id!=null && mapper.lockDoctor(id)==null)throw missing();
        ManagedDoctor old=id==null?null:doctors().stream().filter(d->d.id().equals(id)).findFirst().orElseThrow(CatalogManagementService::missing);
        if(date.isBefore(LocalDate.now()) && (old==null||!old.date().equals(edit.date())))throw bad("不能新增或改为过去的号源日期");
        if(old!=null && old.date().equals(edit.date()) && !old.period().equals(edit.period()))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"现有日期的时段不可更改，以免与正在提交的预约冲突；请选择新的可用日期");
        Integer booked=old==null?0:mapper.lockBookedCount(id,old.date());
        if(old!=null && booked!=null && booked>0 && !LocalDate.parse(old.date()).isBefore(LocalDate.now())
                && (!old.date().equals(edit.date())||!old.period().equals(edit.period())))
            throw new ResponseStatusException(HttpStatus.CONFLICT,"已有预约，不能更改该号源日期或时段；可停用医生停止新增预约");
        String doctorId=id==null?UUID.randomUUID().toString():id;
        if(slots.slotCount(doctorId,edit.date())==0)slots.insertSlot(doctorId,edit.date(),edit.total(),edit.total());
        else if(mapper.resizeSlot(doctorId,edit.date(),edit.total())!=1)throw new ResponseStatusException(HttpStatus.CONFLICT,"容量不能低于已预约数量");
        if(id==null)mapper.addDoctor(doctorId,edit);else mapper.updateDoctor(doctorId,edit);
        return doctors().stream().filter(d->d.id().equals(doctorId)).findFirst().orElseThrow();
    }
    public List<Doctor> available(String department) {
        Set<String> enabled=new HashSet<>();
        departments().stream().filter(Department::enabled).forEach(d->enabled.add(d.id()));
        return doctors().stream().filter(d->d.enabled()&&enabled.contains(d.departmentId())&&!LocalDate.parse(d.date()).isBefore(LocalDate.now()))
            .filter(d->department==null||department.isBlank()||"全部".equals(department)||department.equals(d.department()))
            .map(d->new Doctor(d.id(),d.name(),d.title(),d.department(),d.period(),d.date(),d.remaining(),d.total(),d.fee())).toList();
    }
    private static String text(String v,int max,String label){if(v==null||v.isBlank()||v.trim().length()>max)throw bad(label+"必填且不能超过"+max+"字");return v.trim();}
    private static ResponseStatusException bad(String msg){return new ResponseStatusException(HttpStatus.BAD_REQUEST,msg);}
    private static ResponseStatusException missing(){return new ResponseStatusException(HttpStatus.NOT_FOUND,"目录记录不存在");}
}
