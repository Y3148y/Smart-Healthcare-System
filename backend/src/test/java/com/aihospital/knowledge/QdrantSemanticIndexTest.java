package com.aihospital.knowledge;

import com.aihospital.knowledge.infrastructure.qdrant.QdrantSemanticIndex;
import com.aihospital.knowledge.infrastructure.qdrant.HybridKnowledgeCatalog;
import com.aihospital.knowledge.infrastructure.demo.InMemoryKnowledgeCatalog;
import com.aihospital.shared.model.Models.Evidence;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.*;

class QdrantSemanticIndexTest {
    @Test
    void configuredSemanticIndexCreatesCollectionAndReturnsCitedChunk() throws Exception {
        var upsertedTitles = new CopyOnWriteArrayList<String>();
        var upsertedPoints = new java.util.concurrent.ConcurrentHashMap<String, com.fasterxml.jackson.databind.JsonNode>();
        var corruptPayload = new java.util.concurrent.atomic.AtomicBoolean();
        var collectionConfig = new AtomicReference<com.fasterxml.jackson.databind.JsonNode>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/embeddings", exchange -> {
            var body = new ObjectMapper().readTree(exchange.getRequestBody());
            int count = body.path("input").size();
            StringBuilder response = new StringBuilder("{\"data\":[");
            for (int i = 0; i < count; i++) {
                if (i > 0) response.append(',');
                response.append("{\"index\":").append(i).append(",\"embedding\":[1.0,0.0]}");
            }
            respond(exchange, 200, response.append("]}").toString());
        });
        server.createContext("/collections/ai_hospital_knowledge_v1", exchange -> {
            if ("PUT".equals(exchange.getRequestMethod()))
                collectionConfig.set(new ObjectMapper().readTree(exchange.getRequestBody()));
            respond(exchange, "GET".equals(exchange.getRequestMethod()) ? 404 : 200, "{\"result\":true}");
        });
        server.createContext("/collections/ai_hospital_knowledge_v1/points", exchange -> {
            var request = new ObjectMapper().readTree(exchange.getRequestBody());
            for (var point : request.path("points")) {
                String title = point.path("payload").path("title").asText();
                upsertedTitles.add(title);
                upsertedPoints.put(title, point);
            }
            respond(exchange, 200, "{\"result\":true}");
        });
        server.createContext("/collections/ai_hospital_knowledge_v1/points/search", exchange -> {
            var json = new ObjectMapper();
            var point = upsertedPoints.get(upsertedTitles.contains("新增骨科资料") ? "新增骨科资料" : "呼吸资料");
            var payload = point.path("payload").deepCopy();
            if (corruptPayload.get()) ((com.fasterxml.jackson.databind.node.ObjectNode) payload).put("excerpt", "Wrong remote content");
            var hit = json.createObjectNode().put("id", point.path("id").asText()).put("score", 0.91).set("payload", payload);
            respond(exchange, 200, json.createObjectNode().set("result", json.createArrayNode().add(hit)).toString());
        });
        server.start();
        try {
            QdrantSemanticIndex index = new QdrantSemanticIndex(new ObjectMapper());
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            ReflectionTestUtils.setField(index, "model", "test-embedding");
            ReflectionTestUtils.setField(index, "apiKey", "test-only-key");
            ReflectionTestUtils.setField(index, "embeddingBaseUrl", base);
            ReflectionTestUtils.setField(index, "qdrantUrl", base);
            ReflectionTestUtils.setField(index, "collection", "ai_hospital_knowledge_v1");
            assertTrue(index.ensureIndexed(List.of(new Evidence("呼吸资料", "https://www.who.int/tools/triage",
                    "咳嗽需要结合症状判断就医方向", 1))));
            assertNotNull(collectionConfig.get());
            assertEquals(16, collectionConfig.get().path("hnsw_config").path("m").asInt());
            assertEquals(100, collectionConfig.get().path("hnsw_config").path("ef_construct").asInt());
            assertEquals(10000, collectionConfig.get().path("hnsw_config").path("full_scan_threshold").asInt());
            var hits = index.search("咳嗽", 3, 0.5);
            assertEquals(1, hits.size());
            assertEquals("呼吸资料", hits.get(0).title());
            assertEquals(0.91, hits.get(0).score(), 0.001);
            var local = new InMemoryKnowledgeCatalog();
            var hybrid = new HybridKnowledgeCatalog(local, index);
            var pending = hybrid.addDocument("新增骨科资料", "膝关节外伤应由医生评估");
            assertEquals("PENDING_REVIEW", pending.status());
            assertEquals("READY", hybrid.approveDocument(pending.id()).status());
            assertTrue(upsertedTitles.contains("新增骨科资料"));
            var provenance = upsertedPoints.get("新增骨科资料").path("payload").path("provenance");
            assertEquals(pending.id(), provenance.get(0).path("documentId").asText());
            assertEquals(local.documentChunks(pending.id()).get(0).chunkId(), provenance.get(0).path("chunkId").asText());
            assertTrue(hybrid.retrieve("膝关节外伤", 3, 0.28).evidence().stream()
                    .anyMatch(item -> item.title().equals("新增骨科资料")));
            int beforeRepeat = upsertedTitles.size();
            assertTrue(index.ensureIndexed(local.approvedCorpus()));
            assertEquals(beforeRepeat, upsertedTitles.size(), "重复审批或检索不得重复 upsert 相同 point id");
            corruptPayload.set(true);
            assertTrue(index.search("膝关节外伤", 3, 0.28).isEmpty(), "mismatched remote payload is not evidence");
        } finally { server.stop(0); }
    }

    @Test
    void newCollectionUsesExplicitConfiguredHnswParameters() throws Exception {
        var collectionConfig = new AtomicReference<com.fasterxml.jackson.databind.JsonNode>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/embeddings", exchange -> respond(exchange, 200,
                "{\"data\":[{\"index\":0,\"embedding\":[1.0,0.0]}]}"));
        server.createContext("/collections/index-config-test", exchange -> {
            if ("PUT".equals(exchange.getRequestMethod()))
                collectionConfig.set(new ObjectMapper().readTree(exchange.getRequestBody()));
            respond(exchange, "GET".equals(exchange.getRequestMethod()) ? 404 : 200, "{\"result\":true}");
        });
        server.createContext("/collections/index-config-test/points", exchange -> respond(exchange, 200, "{\"result\":true}"));
        server.start();
        try {
            QdrantSemanticIndex index = new QdrantSemanticIndex(new ObjectMapper());
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            ReflectionTestUtils.setField(index, "model", "test-embedding");
            ReflectionTestUtils.setField(index, "apiKey", "test-only-key");
            ReflectionTestUtils.setField(index, "embeddingBaseUrl", base);
            ReflectionTestUtils.setField(index, "qdrantUrl", base);
            ReflectionTestUtils.setField(index, "collection", "index-config-test");
            ReflectionTestUtils.setField(index, "hnswM", 24);
            ReflectionTestUtils.setField(index, "hnswEfConstruct", 160);
            ReflectionTestUtils.setField(index, "hnswFullScanThresholdKb", 2048);

            assertTrue(index.ensureIndexed(List.of(new Evidence("测试资料", "https://source.invalid", "测试片段", 1))));
            var hnsw = collectionConfig.get().path("hnsw_config");
            assertEquals(24, hnsw.path("m").asInt());
            assertEquals(160, hnsw.path("ef_construct").asInt());
            assertEquals(2048, hnsw.path("full_scan_threshold").asInt());
        } finally { server.stop(0); }
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var stream = exchange.getResponseBody()) { stream.write(bytes); }
    }
}
