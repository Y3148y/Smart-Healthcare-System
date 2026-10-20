package com.aihospital.triage.application;

import com.aihospital.shared.security.JwtService;
import com.aihospital.triage.domain.TriageRecords.Eligibility;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.doAnswer;

/** Real loopback HTTP disconnect, real turn ledger and database save; no external model. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "server.address=127.0.0.1",
        "spring.datasource.url=jdbc:h2:mem:stream_disconnect_test;MODE=MySQL;DATABASE_TO_LOWER=TRUE;DB_CLOSE_DELAY=-1"
})
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class TriageStreamDisconnectTest {
    @LocalServerPort int port;
    @MockitoSpyBean TriageConversationService service;
    @Autowired ObjectMapper json;

    @Test void disconnectedStreamStillCommitsExactlyOneTurnAndCanBeRecoveredByRequestId() throws Exception {
        String patient = "disconnect-" + UUID.randomUUID();
        String sessionId = service.create(patient, new Eligibility(true, true, true)).session().id();
        String requestId = UUID.randomUUID().toString();
        String auth = "Bearer " + new JwtService().issue(patient, "PATIENT");
        String path = "/api/triage/sessions/" + sessionId;
        String payload = json.writeValueAsString(java.util.Map.of("content", "请给我开药", "requestId", requestId));
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var calls = new AtomicInteger();
        doAnswer(invocation -> {
            calls.incrementAndGet();
            entered.countDown();
            if (!release.await(15, TimeUnit.SECONDS)) throw new AssertionError("Test workflow was not released");
            return invocation.callRealMethod();
        }).when(service).send(eq(sessionId), eq(patient), anyString(), eq(requestId), any());

        var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        var readerExecutor = Executors.newSingleThreadExecutor();
        try {
            var stream = client.send(request(path + "/turns/stream", auth).POST(HttpRequest.BodyPublishers.ofString(payload)).build(),
                    HttpResponse.BodyHandlers.ofInputStream());
            try (var reader = new BufferedReader(new InputStreamReader(stream.body(), StandardCharsets.UTF_8))) {
                assertEquals(200, stream.statusCode());
                assertTrue(stream.headers().firstValue("Content-Type").orElse("").contains("text/event-stream"));
                String firstEvent = readerExecutor.submit(() -> {
                    StringBuilder frame = new StringBuilder();
                    for (int line = 0; line < 8; line++) {
                        String value = reader.readLine();
                        if (value == null || value.isEmpty()) break;
                        frame.append(value).append('\n');
                    }
                    return frame.toString();
                }).get(5, TimeUnit.SECONDS);
                assertTrue(firstEvent.contains("event:status"));
                assertTrue(firstEvent.contains("ACCEPTED"));
                assertTrue(entered.await(5, TimeUnit.SECONDS));
                var duplicate = client.send(request(path + "/turns/stream", auth)
                                .POST(HttpRequest.BodyPublishers.ofString(payload)).build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(409, duplicate.statusCode());
            } finally {
                // Close the actual HTTP body before allowing the real workflow/save to continue.
                stream.body().close();
                release.countDown();
            }

            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            String state;
            do {
                var turn = client.send(request(path + "/turns/" + requestId, auth).GET().build(), HttpResponse.BodyHandlers.ofString());
                // The paused service has not registered the turn yet; 404 can precede RUNNING.
                assertTrue(turn.statusCode() == 200 || turn.statusCode() == 404);
                state = turn.statusCode() == 404 ? "NOT_REGISTERED" : json.readTree(turn.body()).path("status").asText();
                if (!state.equals("RUNNING") && !state.equals("NOT_REGISTERED")) break;
                Thread.sleep(25);
            } while (System.nanoTime() < deadline);
            assertEquals("COMPLETED", state, "Closed SSE must not cancel or duplicate the persisted turn");

            var saved = client.send(request(path, auth).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, saved.statusCode());
            var messages = json.readTree(saved.body()).path("messages");
            assertEquals(2, messages.size());
            assertEquals("USER", messages.get(0).path("role").asText());
            assertEquals("ASSISTANT", messages.get(1).path("role").asText());
            assertEquals("POLICY_REFUSAL", messages.get(1).path("provenance").path("modelStatus").asText());
            var replay = client.send(request(path + "/turns", auth).POST(HttpRequest.BodyPublishers.ofString(payload)).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, replay.statusCode());
            assertEquals(messages, json.readTree(replay.body()).path("messages"));
            assertEquals(2, calls.get(), "Only the original and explicit replay reach the application service");
            var conflict = client.send(request(path + "/turns", auth)
                            .POST(HttpRequest.BodyPublishers.ofString(payload.replace("请给我开药", "different input"))).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(409, conflict.statusCode());
        } finally {
            release.countDown();
            readerExecutor.shutdownNow();
            assertTrue(readerExecutor.awaitTermination(5, TimeUnit.SECONDS));
        }
    }

    private HttpRequest.Builder request(String path, String auth) {
        return HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).timeout(Duration.ofSeconds(5))
                .header("Authorization", auth).header("Content-Type", "application/json");
    }
}
