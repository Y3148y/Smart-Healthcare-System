package com.aihospital.review;
import com.aihospital.review.application.HumanReviewService;
import com.aihospital.triage.application.TriageConversationService;
import com.aihospital.triage.domain.TriageStore;
import com.aihospital.triage.domain.TriageRecords.*;
import com.aihospital.shared.security.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.server.ResponseStatusException;
import java.util.UUID;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
@SpringBootTest(properties="review.authorized-subjects=reviewer") @AutoConfigureMockMvc
class HumanReviewFlowTest {
    @Autowired TriageConversationService conversations;
    @Autowired HumanReviewService reviews;
    @Autowired TriageStore store;
    @Autowired MockMvc mvc;
    @Autowired org.springframework.jdbc.core.JdbcTemplate jdbc;
    private String patient(){return "review-test-"+UUID.randomUUID();}
    private Conversation session(String patient){return conversations.create(patient,new Eligibility(true,true,true));}
    @Test void patientSeesOwnRequestsAndCannotReadAdminSummary() throws Exception {
        String a=patient(),b=patient();var s=session(a);var r=conversations.requestHumanReview(s.session().id(),a,"test reason");
        String token="Bearer "+new JwtService().issue(a,"PATIENT");
        mvc.perform(get("/api/patient/human-reviews").header("Authorization",token)).andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(r.id()));
        mvc.perform(get("/api/patient/human-reviews").header("Authorization","Bearer "+new JwtService().issue(b,"PATIENT"))).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        mvc.perform(get("/api/admin/human-reviews/"+r.id()+"/summary").header("Authorization",token)).andExpect(status().isForbidden());
        mvc.perform(get("/api/patient/human-reviews")).andExpect(status().isUnauthorized());
        assertThrows(ResponseStatusException.class,()->conversations.requestHumanReview(s.session().id(),b,"other"));
    }
    @Test void summaryRequiresRequestAndContainsOnlyRelatedBoundedSelfReport() throws Exception {
        String a=patient();var s=session(a);var other=session(a);
        store.appendMessage(s.session().id(),"USER","x".repeat(700));store.appendMessage(other.session().id(),"USER","other-session-secret");
        var r=conversations.requestHumanReview(s.session().id(),a,"reason");var summary=reviews.summary(r.id());
        assertEquals(600,summary.patientStatement().length());assertFalse(summary.patientStatement().contains("other-session-secret"));assertNull(summary.assessmentVersion());
        String token="Bearer "+new JwtService().issue("reviewer","ADMIN");
        mvc.perform(get("/api/admin/human-reviews/"+s.session().id()+"/summary").header("Authorization",token)).andExpect(status().isNotFound());
        mvc.perform(get("/api/admin/human-reviews/"+r.id()+"/summary").header("Authorization",token)).andExpect(status().isOk()).andExpect(jsonPath("$.source").value("PATIENT_SELF_REPORT_AND_SYSTEM_TRIAGE"));
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM review_access_audit WHERE request_id=? AND actor=? AND outcome='READ'", Integer.class, r.id(), "reviewer"));
    }
    @Test void ordinaryAdminQueueAndChangeNeverReturnSensitiveFields() throws Exception {
        String a=patient();var s=session(a);var r=conversations.requestHumanReview(s.session().id(),a,"sensitive reason");
        String token="Bearer "+new JwtService().issue("ordinary-admin","ADMIN");
        mvc.perform(get("/api/admin/human-reviews").header("Authorization",token)).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].patient").doesNotExist()).andExpect(jsonPath("$[0].reason").doesNotExist())
                .andExpect(jsonPath("$[0].sessionId").doesNotExist()).andExpect(jsonPath("$[0].summaryAccessible").value(false));
        mvc.perform(patch("/api/admin/human-reviews/"+r.id()).header("Authorization",token)
                .contentType("application/json").content("{\"status\":\"ACCEPTED\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.patient").doesNotExist())
                .andExpect(jsonPath("$.reason").doesNotExist());
        mvc.perform(get("/api/admin/human-reviews/"+r.id()+"/summary").header("Authorization",token)).andExpect(status().isForbidden());
        assertEquals(1, jdbc.queryForObject("SELECT COUNT(*) FROM review_access_audit WHERE request_id=? AND actor=? AND outcome='DENIED'", Integer.class, r.id(), "ordinary-admin"));
    }
    @Test void acceptedCanCloseButClosedCannotReopen(){
        String a=patient();var s=session(a);var r=conversations.requestHumanReview(s.session().id(),a,"test");
        assertEquals("ACCEPTED",reviews.change(r.id(),"ACCEPTED").status());assertEquals("CLOSED",reviews.change(r.id(),"CLOSED").status());
        assertThrows(ResponseStatusException.class,()->reviews.change(r.id(),"ACCEPTED"));assertThrows(ResponseStatusException.class,()->reviews.change(r.id(),"INVALID"));
        assertEquals("CLOSED",reviews.own(a).get(0).status());
    }
    @Test void concurrentSubmissionIsIdempotent() throws Exception {
        String a=patient();var s=session(a);var pool=Executors.newFixedThreadPool(2);var start=new CountDownLatch(1);
        try{Callable<HumanReview> call=()->{start.await();return conversations.requestHumanReview(s.session().id(),a,"test");};var x=pool.submit(call);var y=pool.submit(call);start.countDown();assertEquals(x.get(10,TimeUnit.SECONDS).id(),y.get(10,TimeUnit.SECONDS).id());assertEquals(1,reviews.own(a).size());}finally{pool.shutdownNow();}
    }
}
