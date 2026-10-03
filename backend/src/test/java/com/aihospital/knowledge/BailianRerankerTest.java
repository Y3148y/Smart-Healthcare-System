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
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/rerank", exchange -> {
            received.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] bytes = reply.get().getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(200, bytes.length);
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
        } finally { server.stop(0); }
    }
}
