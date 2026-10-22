package com.aihospital.shared.diagnostics;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import static org.junit.jupiter.api.Assertions.*;

class SyntheticReviewDiagnosticsTest {
    @TempDir Path directory;
    final ObjectMapper json=new ObjectMapper();
    final String patient=authenticatedSyntheticSubject();
    private static String authenticatedSyntheticSubject() {
        var login=new com.aihospital.identity.application.DemoLoginService();
        org.springframework.test.util.ReflectionTestUtils.setField(login,"demoAuthEnabled",true);
        var response=login.login(new com.aihospital.shared.model.Models.LoginRequest("lisi","synthetic-password"));
        var claims=new com.aihospital.shared.security.JwtService().verifyBearer("Bearer "+response.token());
        assertEquals("PATIENT",claims.role());
        return claims.subject();
    }
    final String session=UUID.randomUUID().toString(),request=UUID.randomUUID().toString(),second=UUID.randomUUID().toString();
    final String user=UUID.randomUUID().toString();
    final Instant now=Instant.now();
    Map<String,Object> permit() {
        return new LinkedHashMap<>(Map.of("schemaVersion",1,"syntheticOnly",true,"sessionId",session,
                "issuedAt",now.minusSeconds(5).toString(),"expiresAt",now.plusSeconds(600).toString(),
                "turns",List.of(Map.of("requestId",request,"patientHashes",List.of(SyntheticReviewDiagnostics.sha256("合成甲"))),
                        Map.of("requestId",second,"patientHashes",List.of(SyntheticReviewDiagnostics.sha256("合成甲"),SyntheticReviewDiagnostics.sha256("合成乙"))))));
    }
    void save(Map<String,Object> permit) throws Exception {Files.writeString(directory.resolve("permit.json"),json.writeValueAsString(permit));}
    @Test void defaultAbsentDoesNotReadPatientHistory() {
        var reads=new AtomicInteger();
        try(var trace=TurnTraceContext.open(user,"TEST");var scope=SyntheticReviewDiagnostics.open(directory,session,patient,request,user,()->{reads.incrementAndGet();return List.of("合成甲");},now)){
            assertFalse(SyntheticReviewDiagnostics.enabled());assertEquals(0,reads.get());
        }
    }
    @Test void exactPermitCapturesOnceAndDetachedWorkerUsesSameTraceWithoutLeakingAfterClose() throws Exception {
        save(permit());
        try(var trace=TurnTraceContext.open(user,"TEST");var scope=SyntheticReviewDiagnostics.open(directory,session,patient,request,user,()->List.of("合成甲"),now)) {
            assertTrue(SyntheticReviewDiagnostics.enabled());
            var metadata=TurnTraceContext.metadata();
            java.util.concurrent.CompletableFuture.runAsync(()->{
                try(var restored=TurnTraceContext.restore(metadata)){
                    assertTrue(SyntheticReviewDiagnostics.enabled());
                    SyntheticReviewDiagnostics.record(json.createObjectNode().put("fixture","checked draft"),json.createArrayNode(),null,json.createObjectNode().put("status","REJECTED"));
                    SyntheticReviewDiagnostics.record(json.createObjectNode().put("fixture","must not overwrite"),null,null,null);
                }
            }).join();
            var packet=json.readTree(Files.readString(directory.resolve(request+".json")));
            assertEquals("REJECTED_SYNTHETIC_DRAFT_DIAGNOSTIC",packet.path("kind").asText());
            assertEquals("checked draft",packet.path("draft").path("fixture").asText());
            assertTrue(packet.path("finalAdoptedReferenceIds").isEmpty());
        }
        try(var trace=TurnTraceContext.open(user,"TEST")) {assertFalse(SyntheticReviewDiagnostics.enabled());}
        try(var trace=TurnTraceContext.open(user,"TEST");var again=SyntheticReviewDiagnostics.open(directory,session,patient,request,user,()->List.of("合成甲"),now)) {
            assertFalse(SyntheticReviewDiagnostics.enabled());
        }
    }
    @Test void mismatchedOwnerSessionRequestAndCompletePatientHistoryCannotArm() throws Exception {
        save(permit());
        try(var trace=TurnTraceContext.open(user,"TEST")) {
            var histories=List.of(List.of("合成甲","真实额外自述"),List.of("不同内容"),List.<String>of());
            for(var history:histories)try(var scope=SyntheticReviewDiagnostics.open(directory,session,patient,request,user,()->history,now)){assertFalse(SyntheticReviewDiagnostics.enabled());}
            try(var scope=SyntheticReviewDiagnostics.open(directory,session,"zhangsan",request,user,()->List.of("合成甲"),now)){assertFalse(SyntheticReviewDiagnostics.enabled());}
            try(var scope=SyntheticReviewDiagnostics.open(directory,session,"lisi",request,user,()->List.of("合成甲"),now)){assertFalse(SyntheticReviewDiagnostics.enabled(),"An account name is not the authenticated subject");}
            try(var scope=SyntheticReviewDiagnostics.open(directory,UUID.randomUUID().toString(),patient,request,user,()->List.of("合成甲"),now)){assertFalse(SyntheticReviewDiagnostics.enabled());}
            try(var scope=SyntheticReviewDiagnostics.open(directory,session,patient,UUID.randomUUID().toString(),user,()->List.of("合成甲"),now)){assertFalse(SyntheticReviewDiagnostics.enabled());}
            assertFalse(Files.exists(directory.resolve(request+".claimed")));
        }
    }
    @Test void secondTurnRequiresBothExactOrderedStatements() throws Exception {
        save(permit());
        try(var trace=TurnTraceContext.open(user,"TEST")) {
            try(var scope=SyntheticReviewDiagnostics.open(directory,session,patient,second,user,()->List.of("合成乙","合成甲"),now)){assertFalse(SyntheticReviewDiagnostics.enabled());}
            try(var scope=SyntheticReviewDiagnostics.open(directory,session,patient,second,user,()->List.of("合成甲","合成乙"),now)){assertTrue(SyntheticReviewDiagnostics.enabled());}
        }
    }
    @Test void expiredFutureIssuedOverlongAndUnknownFieldPermitsFailClosed() throws Exception {
        for(String failure:List.of("expired","future","overlong","unknown","duplicate")) {
            var permit=permit();
            switch(failure) {
                case "expired" -> permit.put("expiresAt",now.minusSeconds(1).toString());
                case "future" -> permit.put("issuedAt",now.plusSeconds(1).toString());
                case "overlong" -> permit.put("issuedAt",now.minusSeconds(7200).toString());
                case "unknown" -> permit.put("captureAllPatients",true);
                case "duplicate" -> permit.put("turns",List.of(Map.of("requestId",request,"patientHashes",List.of(SyntheticReviewDiagnostics.sha256("合成甲"))),Map.of("requestId",request,"patientHashes",List.of(SyntheticReviewDiagnostics.sha256("合成甲"),SyntheticReviewDiagnostics.sha256("合成乙")))));
            }
            save(permit);
            try(var trace=TurnTraceContext.open(user,"TEST");var scope=SyntheticReviewDiagnostics.open(directory,session,patient,request,user,()->List.of("合成甲"),now)){assertFalse(SyntheticReviewDiagnostics.enabled(),failure);}
        }
    }
    @Test void corruptOversizedAndPathLikeIdsDoNotCapture() throws Exception {
        for(String raw:List.of("not-json","{} {}","x".repeat(8193))) {
            Files.writeString(directory.resolve("permit.json"),raw);
            try(var trace=TurnTraceContext.open(user,"TEST");var scope=SyntheticReviewDiagnostics.open(directory,session,patient,request,user,()->List.of("合成甲"),now)){assertFalse(SyntheticReviewDiagnostics.enabled());}
        }
        save(permit());
        try(var trace=TurnTraceContext.open(user,"TEST");var scope=SyntheticReviewDiagnostics.open(directory,session,patient,"../escape",user,()->List.of("合成甲"),now)){assertFalse(SyntheticReviewDiagnostics.enabled());}
    }
    @Test void outputFailureDoesNotThrowAndPermitStillConsumed() throws Exception {
        save(permit());Files.createDirectory(directory.resolve(request+".json"));
        try(var trace=TurnTraceContext.open(user,"TEST");var scope=SyntheticReviewDiagnostics.open(directory,session,patient,request,user,()->List.of("合成甲"),now)) {
            assertTrue(SyntheticReviewDiagnostics.enabled());
            assertDoesNotThrow(()->SyntheticReviewDiagnostics.record(json.createObjectNode(),null,null,json.createObjectNode().put("status","REJECTED")));
        }
        assertTrue(Files.exists(directory.resolve(request+".claimed")));
    }
    @Test void passedOutcomeCannotBeWrittenEvenThroughDirectSink() throws Exception {
        save(permit());
        try(var trace=TurnTraceContext.open(user,"TEST");var scope=SyntheticReviewDiagnostics.open(directory,session,patient,request,user,()->List.of("合成甲"),now)) {
            SyntheticReviewDiagnostics.record(json.createObjectNode(),null,null,json.createObjectNode().put("status","PASSED"));
            assertFalse(Files.exists(directory.resolve(request+".json")));
        }
    }
    @Test void missingTraceDifferentTraceAndClosedRestorationCannotCapture() throws Exception {
        save(permit());
        try(var scope=SyntheticReviewDiagnostics.open(directory,session,patient,request,user,()->List.of("合成甲"),now)){assertFalse(SyntheticReviewDiagnostics.enabled());}
        TurnTraceContext.Metadata snapshot;
        try(var trace=TurnTraceContext.open(user,"TEST");var scope=SyntheticReviewDiagnostics.open(directory,session,patient,request,user,()->List.of("合成甲"),now)) {
            assertTrue(SyntheticReviewDiagnostics.enabled());snapshot=TurnTraceContext.metadata();
            try(var other=TurnTraceContext.open(user,"TEST")){assertFalse(SyntheticReviewDiagnostics.enabled());}
            assertTrue(SyntheticReviewDiagnostics.enabled());
        }
        try(var restored=TurnTraceContext.restore(snapshot)){assertFalse(SyntheticReviewDiagnostics.enabled());}
    }
    @Test void linkedPermitOrLinkedDirectoryCannotArm() throws Exception {
        Path original=directory.resolve("original.json");Files.writeString(original,json.writeValueAsString(permit()));
        try {Files.createSymbolicLink(directory.resolve("permit.json"),original);}
        catch(java.io.IOException|UnsupportedOperationException|SecurityException unavailable) {
            org.junit.jupiter.api.Assumptions.assumeTrue(false,"Host cannot create symbolic links");
        }
        try(var trace=TurnTraceContext.open(user,"TEST");var scope=SyntheticReviewDiagnostics.open(directory,session,patient,request,user,()->List.of("合成甲"),now)){assertFalse(SyntheticReviewDiagnostics.enabled());}
        Path link=directory.resolve("linked");Files.createSymbolicLink(link,directory);
        try(var trace=TurnTraceContext.open(user,"TEST");var scope=SyntheticReviewDiagnostics.open(link,session,patient,request,user,()->List.of("合成甲"),now)){assertFalse(SyntheticReviewDiagnostics.enabled());}
    }
    @Test void realNarrationAdapterStoresRejectedOnlyAndKeepsPatientFallback() throws Exception {
        save(permit());
        var rejected=new java.util.concurrent.atomic.AtomicBoolean(true);
        var server=com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v1/chat/completions",exchange->{
            String sent=new String(exchange.getRequestBody().readAllBytes(),java.nio.charset.StandardCharsets.UTF_8);
            boolean reviewing=sent.contains("<review_data>");
            String decision=json.writeValueAsString(Map.of("claimsSupported",!rejected.get(),"conditionsPreserved",true,
                    "onTopic",true,"noServiceClaims",true,"referencesCorrect",!rejected.get(),"questionsJustified",true));
            String draft=json.writeValueAsString(Map.of("sourceIds",List.of("E1"),"transition","REFERENCE","questionKeys",List.of(),"gap","NONE"));
            byte[] body=json.writeValueAsBytes(Map.of("id","fixture","object","chat.completion","created",1,"model","fixture",
                    "choices",List.of(Map.of("index",0,"finish_reason","stop","message",Map.of("role","assistant","content",reviewing?decision:draft,"reasoning_content","never store hidden thoughts")))));
            exchange.getResponseHeaders().set("Content-Type","application/json");exchange.sendResponseHeaders(200,body.length);
            exchange.getResponseBody().write(body);exchange.close();
        });
        server.start();
        try {
            var model=new com.aihospital.triage.infrastructure.llm.OptionalNarrationModel();
            org.springframework.test.util.ReflectionTestUtils.setField(model,"mode","openai-compatible");
            org.springframework.test.util.ReflectionTestUtils.setField(model,"apiKey","synthetic-test-key");
            org.springframework.test.util.ReflectionTestUtils.setField(model,"model","fixture");
            org.springframework.test.util.ReflectionTestUtils.setField(model,"baseUrl","http://127.0.0.1:"+server.getAddress().getPort()+"/v1");
            org.springframework.test.util.ReflectionTestUtils.setField(model,"timeoutSeconds",3);
            var evidence=List.of(new com.aihospital.shared.model.Models.Evidence("fixture","https://example.test","合成资料内容。",1.0));
            try(var trace=TurnTraceContext.open(user,"TEST");var scope=SyntheticReviewDiagnostics.open(directory,session,patient,request,user,()->List.of("合成甲"),now)) {
                var answer=model.answerWithEvidence("合成甲","","",evidence,"fallback",List.of(),null,false,"READY");
                assertEquals("VALIDATION_BLOCKED",answer.status());assertEquals("REJECTED",answer.diagnostics().supportReview().status());
                assertFalse(answer.text().contains("合成资料内容"));assertTrue(answer.diagnostics().adoptedReferenceIds().isEmpty());
                String packet=Files.readString(directory.resolve(request+".json"));
                assertTrue(packet.contains("合成资料内容"));assertFalse(packet.contains("synthetic-test-key"));assertFalse(packet.contains("hidden thoughts"));
            }
            rejected.set(false);
            try(var trace=TurnTraceContext.open(user,"TEST");var scope=SyntheticReviewDiagnostics.open(directory,session,patient,second,user,()->List.of("合成甲","合成乙"),now)) {
                var answer=model.answerWithEvidence("合成乙","","",evidence,"fallback",List.of(),null,false,"READY");
                assertEquals("SOURCE_QUOTED",answer.status());assertFalse(Files.exists(directory.resolve(second+".json")));
            }
        } finally {server.stop(0);}
    }
}
