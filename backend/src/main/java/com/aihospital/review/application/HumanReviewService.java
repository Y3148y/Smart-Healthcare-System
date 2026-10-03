package com.aihospital.review.application;
import com.aihospital.review.domain.ReviewRecords.Summary;
import com.aihospital.review.domain.ReviewStore;
import com.aihospital.triage.domain.TriageRecords.HumanReview;
import com.aihospital.triage.domain.TriageStore;
import org.springframework.stereotype.Service;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.util.List;
@Service
public class HumanReviewService {
    private final ReviewStore reviews;private final TriageStore store;
    public HumanReviewService(ReviewStore reviews,TriageStore store){this.reviews=reviews;this.store=store;}
    public List<HumanReview> own(String patient){return reviews.own(patient);}
    public List<HumanReview> all(){return reviews.all();}
    public HumanReview change(String id,String next){
        if(!"ACCEPTED".equals(next)&&!"CLOSED".equals(next))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"状态只可设为 ACCEPTED 或 CLOSED");
        var current=require(id);
        if("CLOSED".equals(current.status())||("ACCEPTED".equals(next)&&!"PENDING".equals(current.status())))throw new ResponseStatusException(HttpStatus.CONFLICT,"申请已处理，请刷新后核对");
        if(!reviews.transition(id,current.status(),next))throw new ResponseStatusException(HttpStatus.CONFLICT,"申请已由其他页面处理，请刷新后核对");
        return require(id);
    }
    public Summary summary(String id){
        var request=require(id);
        var session=store.sessions(request.patient()).stream().filter(s->s.id().equals(request.sessionId())).findFirst().orElseThrow(()->new ResponseStatusException(HttpStatus.NOT_FOUND,"申请关联会话不存在"));
        String statement=store.messages(request.sessionId()).stream().filter(m->"USER".equals(m.role())).reduce((a,b)->b).map(m->bounded(m.content(),600)).orElse("");
        var assessments=store.assessments(request.sessionId());var assessment=assessments.isEmpty()?null:assessments.get(assessments.size()-1);var result=assessment==null?null:assessment.result();
        return new Summary(request,session.title(),statement,result==null?session.status():result.riskLevel(),result==null?null:result.department(),result==null?null:bounded(result.safetyTip(),600),assessment==null?null:assessment.version(),assessment==null?null:assessment.createdAt(),"PATIENT_SELF_REPORT_AND_SYSTEM_TRIAGE");
    }
    private HumanReview require(String id){var request=reviews.find(id);if(request==null)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"申请不存在");return request;}
    private static String bounded(String text,int max){return text==null?"":text.substring(0,Math.min(max,text.length()));}
}
