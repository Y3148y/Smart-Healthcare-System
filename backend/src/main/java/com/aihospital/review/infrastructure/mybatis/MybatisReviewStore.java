package com.aihospital.review.infrastructure.mybatis;
import com.aihospital.review.domain.ReviewStore;
import com.aihospital.triage.domain.TriageRecords.HumanReview;
import com.aihospital.triage.infrastructure.mybatis.TriageMapper;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.Map;
import static com.aihospital.shared.infrastructure.mybatis.RowValues.*;
@Repository
public class MybatisReviewStore implements ReviewStore {
    private final ReviewMapper mapper;private final TriageMapper triage;
    public MybatisReviewStore(ReviewMapper mapper,TriageMapper triage){this.mapper=mapper;this.triage=triage;}
    public List<HumanReview> own(String patient){return mapper.own(patient).stream().map(MybatisReviewStore::fromRow).toList();}
    public List<HumanReview> all(){return triage.humanReviews().stream().map(MybatisReviewStore::fromRow).toList();}
    public HumanReview find(String id){var row=mapper.find(id);return row==null?null:fromRow(row);}
    public boolean transition(String id,String expected,String next){return mapper.transition(id,expected,next)==1;}
    private static HumanReview fromRow(Map<String,Object> row){return new HumanReview(string(row,"id"),string(row,"session_id"),string(row,"patient_id"),string(row,"reason"),string(row,"status"),dateTime(row,"created_at"));}
}
