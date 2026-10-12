package com.aihospital.knowledge;

import com.aihospital.knowledge.infrastructure.qdrant.QdrantSemanticIndex;
import com.aihospital.shared.model.Models.Evidence;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;

/** Synthetic embeddings against a real isolated Qdrant; never indexes medical or patient text. */
class QdrantLiveIntegrationTest {
    @Test
    @EnabledIfSystemProperty(named = "ai.test.qdrant-url", matches = "https?://.+")
    void incrementallyIndexesAndFiltersWithdrawnCorpus() throws Exception {
        String qdrantUrl = System.getProperty("ai.test.qdrant-url").replaceAll("/+$", "");
        String collection = "test_synthetic_" + UUID.randomUUID().toString().replace("-", "");
        ObjectMapper json = new ObjectMapper();
        HttpServer embedding = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        embedding.createContext("/embeddings", exchange -> {
            var input = json.readTree(exchange.getRequestBody()).path("input");
            var data = json.createArrayNode();
            for (int i = 0; i < input.size(); i++) {
                boolean beta = input.get(i).asText().contains("beta");
                data.add(json.createObjectNode().put("index", i)
                        .set("embedding", json.createArrayNode().add(beta ? 0.0 : 1.0).add(beta ? 1.0 : 0.0)));
            }
            byte[] body = json.createObjectNode().set("data", data).toString().getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var out = exchange.getResponseBody()) { out.write(body); }
        });
        embedding.start();
        try {
            var index = new QdrantSemanticIndex(json);
            ReflectionTestUtils.setField(index, "model", "synthetic-two-dimensional");
            ReflectionTestUtils.setField(index, "apiKey", "synthetic-test-only-key");
            ReflectionTestUtils.setField(index, "embeddingBaseUrl", "http://127.0.0.1:" + embedding.getAddress().getPort());
            ReflectionTestUtils.setField(index, "qdrantUrl", qdrantUrl);
            ReflectionTestUtils.setField(index, "collection", collection);
            var alpha = new Evidence("synthetic-alpha", "https://example.invalid/alpha", "alpha fixture", 1);
            var beta = new Evidence("synthetic-beta", "https://example.invalid/beta", "beta fixture", 1);

            assertTrue(index.ensureIndexed(List.of(alpha)));
            assertEquals(List.of("synthetic-alpha"), index.search("alpha", 2, 0.5).stream().map(Evidence::title).toList());
            assertTrue(index.ensureIndexed(List.of(alpha, beta)));
            assertEquals(List.of("synthetic-beta"), index.search("beta", 2, 0.5).stream().map(Evidence::title).toList());
            assertTrue(index.ensureIndexed(List.of(alpha)));
            assertTrue(index.search("beta", 2, 0.5).isEmpty(), "withdrawn point must not be returned as active evidence");
        } finally {
            embedding.stop(0);
            HttpRequest delete = HttpRequest.newBuilder(URI.create(qdrantUrl + "/collections/" + collection))
                    .timeout(Duration.ofSeconds(10)).DELETE().build();
            var response = HttpClient.newHttpClient().send(delete, HttpResponse.BodyHandlers.discarding());
            assertTrue(response.statusCode() == 200 || response.statusCode() == 404,
                    "synthetic test collection cleanup failed");
        }
    }
}
