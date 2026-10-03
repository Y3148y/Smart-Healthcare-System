package com.aihospital.triage.application;
import com.aihospital.triage.domain.TriageProgress;
import com.aihospital.triage.domain.TriageRecords.Eligibility;
import com.aihospital.shared.security.JwtService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
@SpringBootTest @AutoConfigureMockMvc
class TriageProgressTest {
    @Autowired TriageConversationService service;
    @Autowired MockMvc mvc;
    @Test void realMilestonesAreEmittedAndCompleteAnswerIsPersisted(){
        String p="progress-"+UUID.randomUUID();var c=service.create(p,new Eligibility(true,true,true));var stages=new ArrayList<TriageProgress>();
        var result=service.send(c.session().id(),p,"咳嗽两天，没有胸痛，我想挂号",stages::add);
        assertEquals(TriageProgress.SAFETY_CHECK,stages.get(0));assertTrue(stages.contains(TriageProgress.KNOWLEDGE_RETRIEVAL));assertTrue(stages.contains(TriageProgress.SCHEDULE_LOOKUP));assertTrue(stages.contains(TriageProgress.ANSWER_GENERATION));assertEquals(TriageProgress.SAVING,stages.get(stages.size()-1));assertEquals(2,result.messages().size());
    }
    @Test void emergencyNeverReportsRetrievalOrModelGeneration(){
        String p="progress-"+UUID.randomUUID();var c=service.create(p,new Eligibility(true,true,true));var stages=new ArrayList<TriageProgress>();
        var result=service.send(c.session().id(),p,"胸痛，喘不过气",stages::add);
        assertEquals(List.of(TriageProgress.SAFETY_CHECK,TriageProgress.SAVING),stages);assertEquals("SAFETY_RULE",result.messages().get(1).provenance().modelStatus());
    }
    @Test void streamIsAuthenticatedAndRejectsForeignSession() throws Exception {
        String p="progress-"+UUID.randomUUID();var c=service.create(p,new Eligibility(true,true,true));String path="/api/triage/sessions/"+c.session().id()+"/turns/stream";
        mvc.perform(post(path).contentType("application/json").content("{\"content\":\"test\"}")).andExpect(status().isUnauthorized());
        mvc.perform(post(path).header("Authorization","Bearer "+new JwtService().issue("other","PATIENT")).contentType("application/json").content("{\"content\":\"test\"}")).andExpect(status().isNotFound());
    }
    @Test void sseReturnsStatusThenOneConversation() throws Exception {
        String p="progress-"+UUID.randomUUID();var c=service.create(p,new Eligibility(true,true,true));
        var result=mvc.perform(post("/api/triage/sessions/"+c.session().id()+"/turns/stream").header("Authorization","Bearer "+new JwtService().issue(p,"PATIENT")).contentType("application/json").content("{\"content\":\"请给我开药\"}")).andExpect(request().asyncStarted()).andReturn();
        result.getAsyncResult(10000);
        var response=mvc.perform(asyncDispatch(result)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);
        assertTrue(response.contains("event:status"));assertTrue(response.contains("event:result"));assertTrue(response.contains("POLICY_REFUSAL"));assertFalse(response.contains("KNOWLEDGE_RETRIEVAL"));assertTrue(response.indexOf("event:status")<response.indexOf("event:result"));
    }
}
