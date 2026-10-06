package com.aihospital.knowledge;

import com.aihospital.knowledge.infrastructure.qdrant.QdrantSemanticIndex;
import com.aihospital.shared.model.Models.Evidence;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

class QdrantRemoteIndexReuseTest {
    @Test void newProcessReusesExactRemotePointsAndEmbedsOnlyChangedChunks() throws Exception {
        ObjectMapper json = new ObjectMapper();
        var collectionExists = new AtomicBoolean();
        var embeddingCalls = new AtomicInteger();
        var upsertCalls = new AtomicInteger();
        var points = new ConcurrentHashMap<String, JsonNode>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/embeddings", exchange -> {
            embeddingCalls.incrementAndGet();
            int count = json.readTree(exchange.getRequestBody()).path("input").size();
            StringBuilder data = new StringBuilder("{\"data\":[");
            for (int i = 0; i < count; i++) {
                if (i > 0) data.append(',');
                data.append("{\"index\":").append(i).append(",\"embedding\":[1.0,0.0]}");
            }
            respond(exchange, 200, data.append("]}").toString());
        });
        server.createContext("/collections/index-reuse", exchange -> {
            if ("PUT".equals(exchange.getRequestMethod())) collectionExists.set(true);
            if ("GET".equals(exchange.getRequestMethod()) && !collectionExists.get()) {
                respond(exchange, 404, "{}");
            } else if ("GET".equals(exchange.getRequestMethod())) {
                respond(exchange, 200, "{\"result\":{\"config\":{\"params\":{\"vectors\":{\"size\":2,\"distance\":\"Cosine\"}}}}}");
            } else {
                respond(exchange, 200, "{\"result\":true}");
            }
        });
        server.createContext("/collections/index-reuse/points", exchange -> {
            if ("PUT".equals(exchange.getRequestMethod())) {
                upsertCalls.incrementAndGet();
                JsonNode request = json.readTree(exchange.getRequestBody());
                for (JsonNode point : request.path("points")) points.put(point.path("id").asText(), point.path("payload"));
                respond(exchange, 200, "{\"result\":true}");
                return;
            }
            JsonNode request = json.readTree(exchange.getRequestBody());
            var result = json.createArrayNode();
            for (JsonNode idNode : request.path("ids")) {
                String id = idNode.asText();
                JsonNode payload = points.get(id);
                if (payload != null) result.add(json.createObjectNode().put("id", id).set("payload", payload));
            }
            respond(exchange, 200, json.createObjectNode().set("result", result).toString());
        });
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            Evidence original = new Evidence("头痛资料", "https://source.invalid/headache", "头痛常见信息", 1);
            QdrantSemanticIndex firstProcess = configured(json, base);
            assertTrue(firstProcess.ensureIndexed(List.of(original)));
            assertEquals(1, embeddingCalls.get());
            assertEquals(1, upsertCalls.get());

            QdrantSemanticIndex restartedProcess = configured(json, base);
            assertTrue(restartedProcess.ensureIndexed(List.of(original)));
            assertEquals(1, embeddingCalls.get(), "an exact persisted point should not consume another embedding request");
            assertEquals(1, upsertCalls.get(), "an exact persisted point should not be upserted again");
            assertEquals(1, restartedProcess.indexedCount(List.of(original)));

            Evidence revised = new Evidence(original.title(), original.source(), "头痛正文已修订", 1);
            assertTrue(restartedProcess.ensureIndexed(List.of(revised)));
            assertEquals(2, embeddingCalls.get(), "a changed chunk receives a new point id and must be embedded");
            assertEquals(2, upsertCalls.get());
        } finally {
            server.stop(0);
        }
    }

    private static QdrantSemanticIndex configured(ObjectMapper json, String base) {
        QdrantSemanticIndex index = new QdrantSemanticIndex(json);
        ReflectionTestUtils.setField(index, "model", "test-embedding");
        ReflectionTestUtils.setField(index, "apiKey", "test-only-key");
        ReflectionTestUtils.setField(index, "embeddingBaseUrl", base);
        ReflectionTestUtils.setField(index, "qdrantUrl", base);
        ReflectionTestUtils.setField(index, "collection", "index-reuse");
        return index;
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var stream = exchange.getResponseBody()) { stream.write(bytes); }
    }
}
