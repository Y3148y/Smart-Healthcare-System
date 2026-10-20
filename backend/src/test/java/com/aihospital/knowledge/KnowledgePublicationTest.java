package com.aihospital.knowledge;

import com.aihospital.knowledge.application.KnowledgePublicationService;
import com.aihospital.knowledge.domain.*;
import com.aihospital.knowledge.infrastructure.qdrant.QdrantSemanticIndex;
import com.aihospital.knowledge.infrastructure.mybatis.KnowledgeReleaseMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import com.aihospital.shared.security.JwtService;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.web.server.ResponseStatusException;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

@SpringBootTest(properties={"ai.knowledge.bundled-approved=false"})
@DirtiesContext(classMode=DirtiesContext.ClassMode.AFTER_CLASS)
@AutoConfigureMockMvc
@Timeout(30)
class KnowledgePublicationTest {
    @DynamicPropertySource static void database(DynamicPropertyRegistry properties) {
        String supplied=System.getProperty("ai.test.publication-db-url");
        if(supplied!=null&&!supplied.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/ai_hospital_publication_test"))
            throw new IllegalArgumentException("Explicit isolated publication test database required");
        if(supplied!=null&&(System.getenv("KNOWLEDGE_TEST_PASSWORD")==null||System.getenv("KNOWLEDGE_TEST_PASSWORD").isBlank()))
            throw new IllegalArgumentException("KNOWLEDGE_TEST_PASSWORD is required");
        properties.add("spring.datasource.url",()->supplied==null?"jdbc:h2:mem:publication_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1":supplied);
        properties.add("spring.datasource.username",()->supplied==null?"sa":"root");
        properties.add("spring.datasource.password",()->supplied==null?"":System.getenv("KNOWLEDGE_TEST_PASSWORD"));
    }
    @Autowired KnowledgePublicationService service;
    @Autowired KnowledgeDocumentStore documents;
    @Autowired KnowledgeReleaseMapper releases;
    @Autowired JdbcTemplate jdbc;
    @Autowired ObjectMapper json;
    @Autowired MockMvc mvc;
    @Autowired KnowledgeCatalog catalog;
    @MockitoBean LexicalSearch lexical;
    @MockitoBean QdrantSemanticIndex semantic;
    @BeforeEach void ready() {
        doReturn(new LexicalSearch.Result(List.of(),"MEMORY_BM25_READY",Map.of())).when(lexical).search(anyString(),anyList(),anyInt(),anyDouble());
        when(lexical.verify(anyList())).thenReturn(true);
        when(semantic.modelName()).thenReturn("synthetic-none");
    }
    String approved() {
        String id=UUID.randomUUID().toString();
        documents.insert(new StoredKnowledgeDocument(id,"Synthetic "+id,"Synthetic nonmedical publication body.","self-authored","PENDING_REVIEW",1,LocalDateTime.now()));
        documents.approve(id,1,LocalDateTime.now());return id;
    }
    long version(){return service.pointerVersion();}
    @Test void everyPublicationAndHistoryEndpointRejectsUnauthenticatedAndPatientCallers() throws Exception {
        var patient="Bearer "+new JwtService().issue("synthetic-publication-patient","PATIENT");
        for(String auth:List.of("",patient))for(String path:List.of("/api/admin/knowledge/releases","/api/admin/knowledge/synthetic/history"))
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(path).header("Authorization",auth))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().is(auth.isEmpty()?401:403));
        for(String auth:List.of("",patient))for(String method:List.of("POST","DELETE")) {
            String path=method.equals("DELETE")?"/api/admin/knowledge/releases/synthetic":"/api/admin/knowledge/releases";
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request(org.springframework.http.HttpMethod.valueOf(method),path)
                    .header("Authorization",auth).contentType("application/json").content("{\"expectedVersion\":0}"))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().is(auth.isEmpty()?401:403));
            mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post("/api/admin/knowledge/releases/synthetic/activate")
                    .header("Authorization",auth).contentType("application/json").content("{\"expectedVersion\":0}"))
                    .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().is(auth.isEmpty()?401:403));
        }
    }
    @Test void catalogReadsOnlyActiveApprovedSnapshotAndImmediatelyExcludesWithdrawnMaterial() {
        String first=approved();service.buildAndPublish(version());
        var evidence=catalog.documentDetails(first).segments();
        assertFalse(evidence.isEmpty());assertEquals(KnowledgeCatalog.EvidenceState.CURRENT,catalog.evidenceState(evidence));
        String next=approved();var pendingPublication=catalog.documentDetails(next).segments();
        assertEquals(KnowledgeCatalog.EvidenceState.CHANGED,catalog.evidenceState(pendingPublication));
        service.buildAndPublish(version());
        assertEquals(KnowledgeCatalog.EvidenceState.CURRENT,catalog.evidenceState(pendingPublication));
        documents.withdraw(first,LocalDateTime.now());
        assertEquals(KnowledgeCatalog.EvidenceState.CHANGED,catalog.evidenceState(evidence));
        assertEquals(KnowledgeCatalog.EvidenceState.CURRENT,catalog.evidenceState(pendingPublication));
    }
    @Test void immutableApprovalHistoryAndPublishedSnapshotSurviveReplaysButNotWithdrawalOrReapproval() {
        String id=approved();var before=service.history(id);assertEquals(1,before.size());
        documents.approve(id,1,LocalDateTime.now());assertEquals(before,service.history(id));
        long v=version();var published=service.buildAndPublish(v);String release=(String)published.get("releaseId");
        assertTrue(service.active().documents().stream().anyMatch(d->d.id().equals(id)));
        documents.withdraw(id,LocalDateTime.now());
        assertTrue(service.active().documents().stream().noneMatch(d->d.id().equals(id)));
        assertEquals(409,assertThrows(ResponseStatusException.class,()->service.rollback(release,version())).getStatusCode().value());
        documents.approve(id,1,LocalDateTime.now());
        assertEquals(409,assertThrows(ResponseStatusException.class,()->service.rollback(release,version())).getStatusCode().value());
        assertEquals(3,service.history(id).size());
        assertEquals(before.get(0),service.history(id).get(2));
        assertTrue(service.active().documents().stream().noneMatch(d->d.id().equals(id)),"New approval does not rewrite the old release");
    }
    @Test void buildFailureDoesNotSwitchPointerAndCasRejectsOldClientVersion() {
        approved();long v=version();
        when(lexical.search(anyString(),anyList(),anyInt(),anyDouble())).thenThrow(new IllegalStateException("private dependency detail"));
        assertEquals(503,assertThrows(ResponseStatusException.class,()->service.buildAndPublish(v)).getStatusCode().value());
        assertEquals(v,version());assertTrue(service.list().stream().anyMatch(r->"FAILED".equals(r.get("build_status"))));
        ready();service.buildAndPublish(v);
        assertEquals(409,assertThrows(ResponseStatusException.class,()->service.buildAndPublish(v)).getStatusCode().value());
    }
    @Test void approvalDuringRemoteBuildRejectsActivationWithoutHoldingDatabaseLock() throws Exception {
        String id=approved();long v=version();var entered=new CountDownLatch(1);var release=new CountDownLatch(1);
        when(lexical.search(anyString(),anyList(),anyInt(),anyDouble())).thenAnswer(invocation->{entered.countDown();assertTrue(release.await(5,TimeUnit.SECONDS));return new LexicalSearch.Result(List.of(),"MEMORY_BM25_READY",Map.of());});
        var executor=Executors.newSingleThreadExecutor();
        try {
            var result=executor.submit(()->service.buildAndPublish(v));assertTrue(entered.await(5,TimeUnit.SECONDS));
            documents.withdraw(id,LocalDateTime.now());release.countDown();
            var failure=assertThrows(ExecutionException.class,()->result.get(5,TimeUnit.SECONDS));
            assertInstanceOf(ResponseStatusException.class,failure.getCause());assertEquals(v,version());
        } finally {release.countDown();executor.shutdownNow();assertTrue(executor.awaitTermination(5,TimeUnit.SECONDS));}
    }
    @Test void incompleteBuildIsInvisibleAndActiveOrRecentHistoryCannotBeRemoved() {
        approved();long v=version();var active=service.buildAndPublish(v);String id=(String)active.get("releaseId");
        assertEquals(409,assertThrows(ResponseStatusException.class,()->service.deleteExpired(id,version())).getStatusCode().value());
        String pending=UUID.randomUUID().toString();
        releases.insert(pending,documents.publishedRevision(),version(),"{}","a".repeat(64));
        assertEquals(id,service.active().releaseId());
        assertEquals(409,assertThrows(ResponseStatusException.class,()->service.rollback(pending,version())).getStatusCode().value());
        assertEquals(409,assertThrows(ResponseStatusException.class,()->service.deleteExpired(pending,version())).getStatusCode().value());
    }
    @Test void rollbackToStillAuthorizedSubsetWorksAndConcurrentBuildersCannotBothActivate() throws Exception {
        approved();String first=(String)service.buildAndPublish(version()).get("releaseId");
        String newer=approved();service.buildAndPublish(version());
        service.rollback(first,version());
        assertTrue(service.active().documents().stream().noneMatch(d->d.id().equals(newer)));
        long expected=version();var entered=new CountDownLatch(2);var release=new CountDownLatch(1);
        doAnswer(invocation->{entered.countDown();assertTrue(release.await(5,TimeUnit.SECONDS));return new LexicalSearch.Result(List.of(),"MEMORY_BM25_READY",Map.of());})
                .when(lexical).search(anyString(),anyList(),anyInt(),anyDouble());
        var pool=Executors.newFixedThreadPool(2);
        try {
            var a=pool.submit(()->service.buildAndPublish(expected));var b=pool.submit(()->service.buildAndPublish(expected));
            assertTrue(entered.await(5,TimeUnit.SECONDS));release.countDown();int success=0,conflicts=0;
            for(var future:List.of(a,b))try{future.get(5,TimeUnit.SECONDS);success++;}catch(ExecutionException e){assertInstanceOf(ResponseStatusException.class,e.getCause());conflicts++;}
            assertEquals(1,success);assertEquals(1,conflicts);assertEquals(expected+1,version());
        } finally {release.countDown();pool.shutdownNow();assertTrue(pool.awaitTermination(5,TimeUnit.SECONDS));}
    }
    @Test void failedBuildPaginationCannotHideTheThreeProtectedReadyVersions() {
        var created=new ArrayList<String>();
        try {
            for(int i=0;i<3;i++) {
                String id=UUID.randomUUID().toString();created.add(id);releases.insert(id,0,0,"{}","a".repeat(64));releases.finish(id,"READY","{}");
                jdbc.update("UPDATE knowledge_release SET created_at=TIMESTAMP '2001-01-01 00:00:00' WHERE id=?",id);
            }
            // Make every other READY older, then create 100 newer failed rows that fill the UI page.
            for(var row:service.list())if("READY".equals(row.get("build_status"))&&!created.contains(row.get("id")))
                jdbc.update("UPDATE knowledge_release SET created_at=TIMESTAMP '2000-01-01 00:00:00' WHERE id=?",row.get("id"));
            String protectedId=created.get(0);
            for(int i=0;i<100;i++) {
                String id=UUID.randomUUID().toString();created.add(id);releases.insert(id,0,0,"{}","a".repeat(64));releases.finish(id,"FAILED","{}");
                jdbc.update("UPDATE knowledge_release SET created_at=TIMESTAMP '2002-01-01 00:00:00' WHERE id=?",id);
            }
            assertTrue(releases.latestReady().contains(protectedId));
            assertEquals(409,assertThrows(ResponseStatusException.class,()->service.deleteExpired(protectedId,version())).getStatusCode().value());
        } finally {for(String id:created)jdbc.update("DELETE FROM knowledge_release WHERE id=?",id);}
    }
}
