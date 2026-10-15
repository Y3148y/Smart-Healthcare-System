package com.aihospital;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.*;
import java.time.LocalDate;
import java.util.Map;
import java.util.UUID;
import static org.junit.jupiter.api.Assertions.*;

/** Real loopback HTTP and persistence, synthetic data only; no external model calls. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RuntimeJourneyTest {
    @Autowired TestRestTemplate http;

    private JsonNode request(HttpMethod method, String path, String token, Object body, int expected) {
        var headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) headers.setBearerAuth(token);
        var response = http.exchange("/api" + path, method, new HttpEntity<>(body, headers), JsonNode.class);
        assertEquals(expected, response.getStatusCode().value(), method + " " + path);
        return response.getBody();
    }
    private String login(String name) {
        return request(HttpMethod.POST, "/auth/login", null,
                Map.of("username", name, "password", "synthetic-test"), 200).path("token").asText();
    }
    private JsonNode get(String path, String token) { return request(HttpMethod.GET, path, token, null, 200); }

    @Test void explicitTurnIdentityReplaysAcrossRequestsAndCannotChangeQuestion(){
        String patient=login("lisi"),other=login("zhangsan");
        var created=request(HttpMethod.POST,"/triage/sessions",patient,
                Map.of("adultConfirmed",true,"forSelfConfirmed",true,"notPregnantConfirmed",true),200);
        String path="/triage/sessions/"+created.path("session").path("id").asText(),key=UUID.randomUUID().toString();
        var body=Map.of("content","咳嗽两天，想了解注意事项","requestId",key);
        var first=request(HttpMethod.POST,path+"/turns",patient,body,200);
        var replay=request(HttpMethod.POST,path+"/turns",patient,body,200);
        assertEquals(first.path("messages"),replay.path("messages"));
        assertEquals("COMPLETED",get(path+"/turns/"+key,patient).path("status").asText());
        request(HttpMethod.POST,path+"/turns",patient,Map.of("content","Different question","requestId",key),409);
        request(HttpMethod.POST,path+"/turns",patient,Map.of("content","Invalid key","requestId","bad"),400);
        request(HttpMethod.GET,path+"/turns/"+key,other,null,404);
    }

    @Test void catalogBookingReplayAndDisablingRemainConsistentAcrossHttpRequests() {
        String admin = login("admin"), patient = login("zhangsan"), other = login("lisi");
        String name = "runtime-fixture-" + UUID.randomUUID();
        var department = request(HttpMethod.POST, "/admin/catalog/departments", admin,
                Map.of("name", name, "enabled", true), 200);
        String departmentId = department.path("id").asText();
        var doctorBody = new java.util.HashMap<String, Object>(Map.of("name", name, "title", "测试医师",
                "departmentId", departmentId, "enabled", true, "date", LocalDate.now().plusDays(2).toString(),
                "period", "上午", "total", 3, "fee", 0));
        var doctor = request(HttpMethod.POST, "/admin/catalog/doctors", admin, doctorBody, 200);
        String doctorId = doctor.path("id").asText();
        var booking = Map.of("doctorId", doctorId, "sessionId", "walk-in", "idempotencyKey", UUID.randomUUID().toString());
        var receipt = request(HttpMethod.POST, "/appointments", patient, booking, 200);
        assertEquals(receipt, request(HttpMethod.POST, "/appointments", patient, booking, 200));
        var managed = get("/admin/catalog/doctors", admin);
        assertEquals(2, find(managed, doctorId).path("remaining").asInt());
        assertFalse(contains(get("/appointments", other), receipt.path("id").asText()));
        request(HttpMethod.GET, "/admin/catalog/doctors", patient, null, 403);
        doctorBody.put("total", 0);
        request(HttpMethod.PUT, "/admin/catalog/doctors/" + doctorId, admin, doctorBody, 409);
        assertEquals(2, find(get("/admin/catalog/doctors", admin), doctorId).path("remaining").asInt());
        doctorBody.put("total", 3); doctorBody.put("enabled", false);
        request(HttpMethod.PUT, "/admin/catalog/doctors/" + doctorId, admin, doctorBody, 200);
        assertFalse(contains(get("/doctors", null), doctorId));
        assertTrue(contains(get("/appointments", patient), receipt.path("id").asText()));
        request(HttpMethod.PUT, "/admin/catalog/departments/" + departmentId, admin,
                Map.of("name", name, "enabled", false), 200);
    }

    @Test void syntheticKnowledgeApprovalAndWithdrawalChangeRetrievalNotClinicalStatus() {
        String admin = login("admin"), patient = login("zhangsan");
        String title = "runtimefixture" + UUID.randomUUID().toString().replace("-", "");
        var document = request(HttpMethod.POST, "/admin/knowledge", admin,
                Map.of("title", title, "body", title + " synthetic non-medical test content."), 200);
        String id = document.path("id").asText();
        assertEquals("PENDING_REVIEW", document.path("status").asText());
        assertFalse(hasTitle(get("/admin/knowledge/search?q=" + title, admin), title));
        request(HttpMethod.POST, "/admin/knowledge/" + id + "/approve", patient, null, 403);
        var approved = request(HttpMethod.POST, "/admin/knowledge/" + id + "/approve", admin, null, 200);
        assertEquals("READY", approved.path("status").asText());
        assertTrue(hasTitle(get("/admin/knowledge/search?q=" + title, admin), title));
        assertFalse(get("/admin/knowledge/" + id + "/details", admin).path("segments").isEmpty());
        assertEquals("NOT_REVIEWED", get("/admin/knowledge/" + id + "/metadata", admin).path("clinicalReview").asText());
        request(HttpMethod.POST, "/admin/knowledge/" + id + "/withdraw", admin, null, 200);
        assertFalse(hasTitle(get("/admin/knowledge/search?q=" + title, admin), title));
    }

    @Test void historicalConversationPreservesPerTurnExecutionAndPatientOwnership() {
        String patient = login("zhangsan"), other = login("lisi");
        var created = request(HttpMethod.POST, "/triage/sessions", patient,
                Map.of("adultConfirmed", true, "forSelfConfirmed", true, "notPregnantConfirmed", true), 200);
        String path = "/triage/sessions/" + created.path("session").path("id").asText();
        var answered = request(HttpMethod.POST, path + "/turns", patient, Map.of("content", "咳嗽两天，没有发烧，想了解日常注意事项"), 200);
        var historical = get(path, patient);
        assertEquals(answered.path("messages"), historical.path("messages"));
        var assistant = historical.path("messages").get(historical.path("messages").size() - 1);
        assertEquals("ASSISTANT", assistant.path("role").asText());
        assertTrue(assistant.path("provenance").path("turnTrace").path("executions").isArray());
        assertFalse(assistant.path("provenance").path("turnTrace").path("traceId").asText().isBlank());
        var denied = http.exchange("/api" + path, HttpMethod.GET,
                new HttpEntity<>(null, bearer(other)), JsonNode.class);
        assertTrue(denied.getStatusCode().is4xxClientError());
    }
    private static HttpHeaders bearer(String token) { var h = new HttpHeaders(); h.setBearerAuth(token); return h; }
    private static JsonNode find(JsonNode rows, String id) {
        for (var row : rows) if (id.equals(row.path("id").asText())) return row;
        fail("Expected synthetic record missing"); return null;
    }
    private static boolean contains(JsonNode rows, String id) {
        for (var row : rows) if (id.equals(row.path("id").asText())) return true;
        return false;
    }
    private static boolean hasTitle(JsonNode rows, String title) {
        for (var row : rows) if (title.equals(row.path("title").asText())) return true;
        return false;
    }
}
