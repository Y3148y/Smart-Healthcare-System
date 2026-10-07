package com.aihospital.knowledge;

import com.aihospital.knowledge.infrastructure.qdrant.BailianReranker;
import com.aihospital.shared.model.Models.Evidence;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import static org.junit.jupiter.api.Assertions.*;

class BailianRerankerTest {
    @Test void providerRanksAreValidatedAndLowRelevanceIsFiltered() throws Exception {
        var reply = new AtomicReference<>("{\"output\":{\"results\":[{\"index\":1,\"relevance_score\":0.9},{\"index\":0,\"relevance_score\":0.1}]}}");
        var received = new AtomicReference<String>();
        var httpStatus = new java.util.concurrent.atomic.AtomicInteger(200);
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/rerank", exchange -> {
            received.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = reply.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(httpStatus.get(), bytes.length);
            try(var stream = exchange.getResponseBody()) { stream.write(bytes); }
        });
        server.start();
        try {
            var reranker = new BailianReranker(new ObjectMapper());
            ReflectionTestUtils.setField(reranker, "url", "http://127.0.0.1:" + server.getAddress().getPort() + "/rerank");
            ReflectionTestUtils.setField(reranker, "key", "test-only");
            ReflectionTestUtils.setField(reranker, "model", "test-rerank");
            var candidates = List.of(new Evidence("骨折", "source1", "骨折就医", 0.4),
                    new Evidence("胸痛", "source2", "胸痛就医", 0.3));
            var result = reranker.rank("胸口痛", candidates, 3);
            assertEquals("OK", result.status());
            assertEquals("胸痛", result.evidence().get(0).title());
            assertEquals(1, result.evidence().size());
            assertEquals("胸口痛", new ObjectMapper().readTree(received.get()).path("input").path("query").asText());
            reply.set("{\"output\":{\"results\":[{\"index\":1,\"relevance_score\":0.9},{\"index\":1,\"relevance_score\":0.8}]}}");
            assertEquals("INVALID_RESPONSE", reranker.rank("胸口痛", candidates, 3).status());
            httpStatus.set(400);
            reply.set("{\"code\":\"Arrearage\",\"message\":\"private-query-and-provider-message\"}");
            var failed = reranker.rank("胸口痛", candidates, 3);
            assertEquals("HTTP_400:Arrearage", failed.status());
            assertTrue(failed.evidence().isEmpty());
            assertFalse(failed.status().contains("private-query"));
            reply.set("{\"code\":\"unsafe error with patient text\"}");
            assertEquals("HTTP_400", reranker.rank("胸口痛", candidates, 3).status());
        } finally { server.stop(0); }
    }

    @Test void transportFailuresAreClassifiedWithoutExposingExceptionText() throws Exception {
        var reranker = new BailianReranker(new ObjectMapper());
        ReflectionTestUtils.setField(reranker, "key", "test-only");
        ReflectionTestUtils.setField(reranker, "model", "test-rerank");
        ReflectionTestUtils.setField(reranker, "url", "not a URL");
        assertEquals("INVALID_CONFIGURATION", reranker.rank("private query", List.of(
                new Evidence("title", "source", "excerpt", 1)), 1).status());

        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.start();
        int port = server.getAddress().getPort();
        server.stop(0);
        ReflectionTestUtils.setField(reranker, "url", "http://127.0.0.1:" + port + "/rerank");
        var failed = reranker.rank("private query", List.of(new Evidence("title", "source", "excerpt", 1)), 1);
        assertEquals("CONNECTION_FAILED", failed.status());
        assertFalse(failed.status().contains("private query"));

        var slow = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        slow.createContext("/rerank", exchange -> {
            try { Thread.sleep(1400); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
            byte[] body = "{\"output\":{\"results\":[{\"index\":0,\"relevance_score\":0.9}]}}"
                    .getBytes(StandardCharsets.UTF_8);
            try { exchange.sendResponseHeaders(200, body.length); exchange.getResponseBody().write(body); }
            catch (java.io.IOException ignored) { }
            finally { exchange.close(); }
        });
        slow.start();
        try {
            ReflectionTestUtils.setField(reranker, "url", "http://127.0.0.1:" + slow.getAddress().getPort() + "/rerank");
            ReflectionTestUtils.setField(reranker, "timeout", 1);
            var timedOut = reranker.rank("private query", List.of(new Evidence("title", "source", "excerpt", 1)), 1);
            assertEquals("TIMEOUT", timedOut.status());
        } finally { slow.stop(0); }
    }

    @Test void finalSelectionUsesAtMostOneChunkPerDocument() throws Exception {
        var responseBody = new AtomicReference<>("{\"output\":{\"results\":["
                + "{\"index\":0,\"relevance_score\":0.95},{\"index\":1,\"relevance_score\":0.90},"
                + "{\"index\":2,\"relevance_score\":0.85}]}}");
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/rerank", exchange -> {
            exchange.getRequestBody().readAllBytes();
            byte[] body=responseBody.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200,body.length);
            try(var stream=exchange.getResponseBody()) { stream.write(body); }
        });
        server.start();
        try {
            var reranker = new BailianReranker(new ObjectMapper());
            ReflectionTestUtils.setField(reranker,"url","http://127.0.0.1:"+server.getAddress().getPort()+"/rerank");
            ReflectionTestUtils.setField(reranker,"key","test-only");
            ReflectionTestUtils.setField(reranker,"model","test-rerank");
            var candidates=List.of(new Evidence("普通感冒","https://source.invalid/cold","片段一",0.4),
                    new Evidence("普通感冒","https://source.invalid/cold","片段二",0.3),
                    new Evidence("咽痛","https://source.invalid/throat","片段三",0.2));
            var result=reranker.rank("鼻塞",candidates,3);
            assertEquals(List.of("普通感冒","咽痛"),result.evidence().stream().map(Evidence::title).toList());
            assertEquals("duplicate_document",result.ranking().stream()
                    .filter(r->r.evidence().excerpt().equals("片段二")).findFirst().orElseThrow().reason());
        } finally { server.stop(0); }
    }
}
