package com.aihospital.triage;

import com.aihospital.triage.application.*;
import com.aihospital.triage.domain.*;
import com.aihospital.triage.domain.TriageRecords.*;
import com.aihospital.triage.infrastructure.mybatis.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;

/** Synthetic observations only; does not test medical event classification. */
@SpringBootTest
@org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc
class SessionFactPersistenceTest {
    @Autowired org.springframework.test.web.servlet.MockMvc http;
    @Test void patientObservationEndpointShowsCompletedOwnerDataOnly() throws Exception {
        String patient=id(),session=create(patient);var conversation=begin(patient,session,"SyntheticOwnerObservation");
        stage(patient,conversation,"SyntheticOwnerObservation",IntentExtraction.Assertion.AFFIRMED);
        var jwt=new com.aihospital.shared.security.JwtService();
        String url="/api/triage/sessions/"+session+"/observations";
        String owner="Bearer "+jwt.issue(patient,"PATIENT");
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(url).header("Authorization",owner))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.status").value("NO_COMPLETED_SNAPSHOT"));
        complete(conversation);
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(url).header("Authorization",owner))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.sessionId").value(session))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.observations[0].symptom").value("SyntheticOwnerObservation"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.clinicalReview").value("NOT_REVIEWED"));
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(url).param("patient",patient).header("Authorization","Bearer "+jwt.issue(id(),"PATIENT")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isNotFound());
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(url).header("Authorization","Bearer "+jwt.issue("admin","ADMIN")))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isForbidden());
        http.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(url))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isUnauthorized());
    }
    @Autowired SessionFactStore facts;
    @Autowired SessionFactMapper mapper;
    @Autowired TriageStore store;
    @Autowired IntentValidator validator;
    @Autowired ObjectMapper json;
    @Autowired JdbcTemplate jdbc;
    @Autowired com.aihospital.catalog.application.CatalogManagementService catalog;
    @Autowired com.aihospital.knowledge.domain.KnowledgeCatalog knowledge;
    @Autowired com.aihospital.observation.domain.CallLogStore calls;
    @Autowired TriageSafetyPolicy safety;
    private String id(){return UUID.randomUUID().toString();}
    private String create(String p){String s=id();store.createSession(s,p,LocalDateTime.now());return s;}
    private Conversation begin(String p,String s,String content){
        var claim=store.beginTurn(s,id(),content,180);assertEquals("CLAIMED",claim.outcome());
        return new Conversation(store.sessions(p).stream().filter(x->x.id().equals(s)).findFirst().orElseThrow(),
                store.messages(s),store.assessments(s),null,claim.turn());
    }
    private IntentExtraction proposal(Conversation c,String symptom,IntentExtraction.Assertion assertion){
        var source=c.messages().get(c.messages().size()-1);
        return new IntentExtraction(1,List.of(IntentExtraction.Intent.GENERAL_HEALTH),List.of(
                new IntentExtraction.SymptomEvent(symptom,null,null,null,null,null,IntentExtraction.Subject.SELF,
                        IntentExtraction.Temporality.CURRENT,assertion,List.of(new IntentExtraction.SourceRef(source.id(),source.content())))),List.of(),List.of());
    }
    private SessionFacts stage(String p,Conversation c,String symptom,IntentExtraction.Assertion assertion){
        return facts.stage(p,c,IntentInput.recent(c.messages()),proposal(c,symptom,assertion));
    }
    private void complete(Conversation c){store.completeTurn(c.latestTurn(),()->store.appendAssistantMessage(c.session().id(),
            "Synthetic completed answer",new ResponseProvenance("DEMO",0,0,0)));}

    @Test void observationsPublishOnlyWithCompletedTurnAndSurviveIndependentAdapterRead(){
        String p=id(),s=create(p);var c=begin(p,s,"SyntheticAlpha");
        var staged=stage(p,c,"SyntheticAlpha",IntentExtraction.Assertion.AFFIRMED);
        assertEquals(1,staged.events().size());assertTrue(facts.completed(p,s).events().isEmpty());
        assertEquals(staged,stage(p,c,"SyntheticAlpha",IntentExtraction.Assertion.AFFIRMED));
        complete(c);assertEquals(staged,facts.completed(p,s));
        var another=new MybatisSessionFactStore(mapper,store,validator,json);
        assertEquals(staged,another.completed(p,s));
        assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM triage_fact_snapshot WHERE session_id=?",Integer.class,s));
    }
    @Test void failedTurnCannotPublishOrErasePreviousObservations(){
        String p=id(),s=create(p);var first=begin(p,s,"SyntheticAlpha");
        var previous=stage(p,first,"SyntheticAlpha",IntentExtraction.Assertion.AFFIRMED);complete(first);
        var failed=begin(p,s,"SyntheticBeta");stage(p,failed,"SyntheticBeta",IntentExtraction.Assertion.AFFIRMED);
        store.failTurn(failed.latestTurn(),"SYNTHETIC_FAILURE");assertEquals(previous,facts.completed(p,s));
        var next=begin(p,s,"SyntheticGamma");stage(p,next,"SyntheticGamma",IntentExtraction.Assertion.AFFIRMED);complete(next);
        assertEquals(List.of("SyntheticAlpha","SyntheticGamma"),facts.completed(p,s).events().stream().map(IntentExtraction.SymptomEvent::symptom).toList());
    }
    @Test void answerTransactionRollbackCannotPublishTheStagedObservation(){
        String p=id(),s=create(p);var c=begin(p,s,"SyntheticAlpha");stage(p,c,"SyntheticAlpha",IntentExtraction.Assertion.AFFIRMED);
        assertThrows(IllegalStateException.class,()->store.completeTurn(c.latestTurn(),()->{
            store.appendAssistantMessage(s,"Synthetic answer rolled back",new ResponseProvenance("DEMO",0,0,0));
            throw new IllegalStateException("Synthetic save failure");
        }));
        assertEquals(1,store.messages(s).size());assertTrue(facts.completed(p,s).events().isEmpty());
        store.failTurn(c.latestTurn(),"SYNTHETIC_SAVE_FAILED");assertTrue(facts.completed(p,s).events().isEmpty());
    }
    @Test void oldWindowIsRetainedAsObservationWithoutExpandingTheExtractorWindow(){
        String p=id(),s=create(p);String firstMessage=null;
        for(int i=0;i<7;i++){
            var c=begin(p,s,"SyntheticAlpha "+i);if(i==0)firstMessage=c.latestTurn().userMessageId();
            var input=IntentInput.recent(c.messages());assertTrue(input.messages().stream().filter(x->x.role().equals("USER")).count()<=5);
            stage(p,c,"SyntheticAlpha",IntentExtraction.Assertion.AFFIRMED);complete(c);
        }
        var observed=facts.completed(p,s);assertEquals(7,observed.events().size());
        assertEquals(firstMessage,observed.events().get(0).sourceRefs().get(0).messageId());
    }
    @Test void denialIsAnAdditionalObservationAndDoesNotDeleteAffirmativeHistory(){
        String p=id(),s=create(p);var first=begin(p,s,"SyntheticAlpha affirmed");stage(p,first,"SyntheticAlpha",IntentExtraction.Assertion.AFFIRMED);complete(first);
        var next=begin(p,s,"SyntheticAlpha denied");stage(p,next,"SyntheticAlpha",IntentExtraction.Assertion.NEGATED);complete(next);
        assertEquals(2,facts.completed(p,s).events().size());
        assertTrue(facts.completed(p,s).retrievalSymptoms().isEmpty());
    }
    @Test void anotherOwnerAndStaleOrExpiredTurnCannotWrite(){
        String p=id(),s=create(p);var c=begin(p,s,"SyntheticAlpha");
        assertEquals(404,assertThrows(ResponseStatusException.class,()->facts.completed(id(),s)).getStatusCode().value());
        assertEquals(404,assertThrows(ResponseStatusException.class,()->stage(id(),c,"SyntheticAlpha",IntentExtraction.Assertion.AFFIRMED)).getStatusCode().value());
        jdbc.update("UPDATE triage_turn SET expires_at=? WHERE id=?",LocalDateTime.now().minusDays(1),c.latestTurn().id());
        assertThrows(IllegalStateException.class,()->stage(p,c,"SyntheticAlpha",IntentExtraction.Assertion.AFFIRMED));
        store.reconcileTurns(s);begin(p,s,"SyntheticBeta");
        assertThrows(IllegalStateException.class,()->stage(p,c,"SyntheticAlpha",IntentExtraction.Assertion.AFFIRMED));
        assertEquals(0,jdbc.queryForObject("SELECT COUNT(*) FROM triage_fact_snapshot WHERE session_id=?",Integer.class,s));
    }
    @Test void storageRevalidatesProposalAndRejectsDifferentObservationForSameTurn(){
        String p=id(),s=create(p);var c=begin(p,s,"SyntheticAlpha SyntheticBeta");
        assertThrows(IllegalArgumentException.class,()->stage(p,c,"NotInSource",IntentExtraction.Assertion.AFFIRMED));
        stage(p,c,"SyntheticAlpha",IntentExtraction.Assertion.AFFIRMED);
        assertThrows(IllegalStateException.class,()->stage(p,c,"SyntheticBeta",IntentExtraction.Assertion.AFFIRMED));
        complete(c);assertEquals("SyntheticAlpha",facts.completed(p,s).events().get(0).symptom());
    }
    @Test void corruptedSnapshotOrUnsupportedSchemaNeverSilentlyBecomesEmpty(){
        String p=id(),s=create(p);var c=begin(p,s,"SyntheticAlpha");stage(p,c,"SyntheticAlpha",IntentExtraction.Assertion.AFFIRMED);complete(c);
        jdbc.update("UPDATE triage_fact_snapshot SET schema_version=99 WHERE turn_id=?",c.latestTurn().id());
        assertThrows(IllegalStateException.class,()->facts.completed(p,s));
        jdbc.update("UPDATE triage_fact_snapshot SET schema_version=1,facts_hash=? WHERE turn_id=?","0".repeat(64),c.latestTurn().id());
        assertThrows(IllegalStateException.class,()->facts.completed(p,s));
    }
    @Test void concurrentStagingHasOneImmutableObservation()throws Exception {
        String p=id(),s=create(p);var c=begin(p,s,"SyntheticAlpha");var executor=Executors.newFixedThreadPool(2);
        try{
            var a=executor.submit(()->stage(p,c,"SyntheticAlpha",IntentExtraction.Assertion.AFFIRMED));
            var b=executor.submit(()->stage(p,c,"SyntheticAlpha",IntentExtraction.Assertion.AFFIRMED));
            assertEquals(a.get(15,TimeUnit.SECONDS),b.get(15,TimeUnit.SECONDS));
            assertEquals(1,jdbc.queryForObject("SELECT COUNT(*) FROM triage_fact_snapshot WHERE session_id=?",Integer.class,s));
            complete(c);assertEquals(1,facts.completed(p,s).events().size());
        }finally{executor.shutdownNow();}
    }
    @Test void shadowCoordinatorActuallyStagesSourceCheckedObservationsWithoutToolsOrExtraModelRequests(){
        String p=id(),s=create(p);var c=begin(p,s,"SyntheticAlpha");
        IntentExtractor extractor=input->new IntentExtractor.Result(IntentExtractor.Status.ACCEPTED,
                proposal(c,"SyntheticAlpha",IntentExtraction.Assertion.AFFIRMED),"synthetic","",1,1,1);
        var model=org.mockito.Mockito.mock(AgentModel.class);
        var executor=org.mockito.Mockito.mock(com.aihospital.tools.application.HospitalToolExecutor.class);
        try(var gateway=new com.aihospital.tools.application.AgentToolGateway(executor);
            var coordinator=new AgentWorkflowCoordinator(new AgentWorkflowSettings(AgentWorkflowSettings.Mode.SHADOW,8,2048),
                    extractor,model,gateway,catalog,knowledge,store,calls,safety,facts)){
            assertNull(coordinator.prepare(c,p,"SyntheticAlpha"));
            org.mockito.Mockito.verifyNoInteractions(model,executor);
            assertTrue(facts.completed(p,s).events().isEmpty());
            complete(c);assertEquals(1,facts.completed(p,s).events().size());
        }
    }
}
