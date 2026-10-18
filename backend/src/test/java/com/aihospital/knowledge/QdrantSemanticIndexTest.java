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
    void exposesOnlyKnownProviderErrorAndParameterIdentifiers() {
        var index = new QdrantSemanticIndex(new ObjectMapper());
        assertEquals(":InvalidParameter:model", ReflectionTestUtils.invokeMethod(index, "safeEmbeddingError",
                "{\"error\":{\"code\":\"InvalidParameter\",\"param\":\"model\",\"message\":\"private detail\"}}"));
        assertEquals(":Arrearage", ReflectionTestUtils.invokeMethod(index, "safeEmbeddingError",
                "{\"code\":\"Arrearage\",\"message\":\"private detail\"}"));
        assertEquals(":invalid_request_error:input", ReflectionTestUtils.invokeMethod(index, "safeEmbeddingError",
                "{\"error\":{\"code\":\"invalid_request_error\",\"param\":\"input\"}}"));
        for (String body : List.of("not json", "{}", "x".repeat(16_385),
                "{\"error\":{\"code\":\"private-user-input\",\"param\":\"model\"}}"))
            assertEquals("", ReflectionTestUtils.invokeMethod(index, "safeEmbeddingError", body));
        assertEquals(":InvalidParameter", ReflectionTestUtils.invokeMethod(index, "safeEmbeddingError",
                "{\"error\":{\"code\":\"InvalidParameter\",\"param\":\"private-user-input\"}}"));
    }

    @Test
    void embedding400PreservesSafeCodeButNeverRawMessage() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/embeddings", exchange -> respond(exchange, 400,
                "{\"error\":{\"code\":\"InvalidParameter\",\"param\":\"model\",\"message\":\"private detail\"}}"));
        server.createContext("/collections/diagnostic-test", exchange -> respond(exchange, 404, "{}"));
        server.start();
        try {
            var index = new QdrantSemanticIndex(new ObjectMapper());
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            ReflectionTestUtils.setField(index, "model", "test-embedding");
            ReflectionTestUtils.setField(index, "apiKey", "test-only-key");
            ReflectionTestUtils.setField(index, "embeddingBaseUrl", base);
            ReflectionTestUtils.setField(index, "qdrantUrl", base);
            ReflectionTestUtils.setField(index, "collection", "diagnostic-test");
            assertFalse(index.ensureIndexed(List.of(new Evidence("Synthetic", "source", "text", 0))));
            assertEquals("EMBEDDING_HTTP_400:InvalidParameter:model", index.status());
        } finally { server.stop(0); }
    }

    @Test
    void classifiesEmbeddingHttpFailureWithoutExposingResponseBody() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/embeddings", exchange -> respond(exchange, 429, "{\"message\":\"private detail\"}"));
        server.createContext("/collections/diagnostic-test", exchange -> respond(exchange, 404, "{}"));
        server.start();
        try {
            var index = new QdrantSemanticIndex(new ObjectMapper());
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            ReflectionTestUtils.setField(index, "model", "test-embedding");
            ReflectionTestUtils.setField(index, "apiKey", "test-only-key");
            ReflectionTestUtils.setField(index, "embeddingBaseUrl", base);
            ReflectionTestUtils.setField(index, "qdrantUrl", base);
            ReflectionTestUtils.setField(index, "collection", "diagnostic-test");
            assertFalse(index.ensureIndexed(List.of(new Evidence("Synthetic", "source", "text", 0))));
            assertEquals("EMBEDDING_HTTP_429", index.status());
        } finally { server.stop(0); }
    }

    @Test
    void classifiesQdrantSearchHttpFailureSeparatelyFromEmbedding() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/embeddings", exchange -> respond(exchange, 200,
                "{\"data\":[{\"index\":0,\"embedding\":[1.0,0.0]}]}"));
        server.createContext("/collections/diagnostic-test", exchange ->
                respond(exchange, "GET".equals(exchange.getRequestMethod()) ? 404 : 200, "{\"result\":true}"));
        server.createContext("/collections/diagnostic-test/points", exchange -> respond(exchange, 200, "{\"result\":true}"));
        server.createContext("/collections/diagnostic-test/points/search", exchange ->
                respond(exchange, 503, "{\"message\":\"private detail\"}"));
        server.start();
        try {
            var index = new QdrantSemanticIndex(new ObjectMapper());
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            ReflectionTestUtils.setField(index, "model", "test-embedding");
            ReflectionTestUtils.setField(index, "apiKey", "test-only-key");
            ReflectionTestUtils.setField(index, "embeddingBaseUrl", base);
            ReflectionTestUtils.setField(index, "qdrantUrl", base);
            ReflectionTestUtils.setField(index, "collection", "diagnostic-test");
            assertTrue(index.ensureIndexed(List.of(new Evidence("Synthetic", "source", "text", 0))));
            assertEquals("QDRANT_HTTP_503", index.searchDetailed("query", 3, 0.45).status());
            assertEquals("QDRANT_HTTP_503", index.status());
        } finally { server.stop(0); }
    }

    @Test
    void classifiesMalformedEmbeddingResponseAndCollectionDimensionMismatchWithoutRawDetails() throws Exception {
        var responseBody = new AtomicReference<>("{\"data\":[],\"message\":\"private provider detail\"}");
        HttpServer malformed = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        malformed.createContext("/embeddings", exchange -> respond(exchange, 200, responseBody.get()));
        malformed.start();
        try {
            var index = configuredIndex("diagnostic-test", malformed.getAddress().getPort());
            assertFalse(index.ensureIndexed(List.of(new Evidence("Synthetic", "source", "text", 0))));
            assertEquals("EMBEDDING_RESPONSE_VECTOR_COUNT_MISMATCH", index.status());
            assertFalse(index.status().contains("private"));
            responseBody.set("private provider detail: invalid JSON");
            assertFalse(index.ensureIndexed(List.of(new Evidence("Synthetic", "source", "text", 0))));
            assertEquals("EMBEDDING_INVALID_RESPONSE", index.status());
        } finally { malformed.stop(0); }

        HttpServer mismatch = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        mismatch.createContext("/embeddings", exchange -> respond(exchange, 200,
                "{\"data\":[{\"index\":0,\"embedding\":[1.0,0.0]}]}"));
        mismatch.createContext("/collections/diagnostic-test", exchange -> respond(exchange, 200,
                "{\"result\":{\"config\":{\"params\":{\"vectors\":{\"size\":3,\"distance\":\"Cosine\"}}}}}"));
        var upserts = new java.util.concurrent.atomic.AtomicInteger();
        mismatch.createContext("/collections/diagnostic-test/points", exchange -> {
            if ("PUT".equals(exchange.getRequestMethod())) upserts.incrementAndGet();
            respond(exchange, 200, "{\"result\":[]}");
        });
        mismatch.start();
        try {
            var index = configuredIndex("diagnostic-test", mismatch.getAddress().getPort());
            assertFalse(index.ensureIndexed(List.of(new Evidence("Synthetic", "source", "text", 0))));
            assertEquals("QDRANT_COLLECTION_DIMENSION_MISMATCH", index.status());
            assertEquals(0, upserts.get(), "mismatched collection must not receive vectors");
        } finally { mismatch.stop(0); }
    }

    private QdrantSemanticIndex configuredIndex(String collection, int port) {
        var index = new QdrantSemanticIndex(new ObjectMapper());
        String base = "http://127.0.0.1:" + port;
        ReflectionTestUtils.setField(index, "model", "test-embedding");
        ReflectionTestUtils.setField(index, "apiKey", "test-only-key");
        ReflectionTestUtils.setField(index, "embeddingBaseUrl", base);
        ReflectionTestUtils.setField(index, "qdrantUrl", base);
        ReflectionTestUtils.setField(index, "collection", collection);
        return index;
    }

    @Test
    void retriesAProviderBatchWithDuplicateIndicesAsUnambiguousSingletonRequests() throws Exception {
        var batchRequests = new java.util.concurrent.atomic.AtomicInteger();
        var singletonRequests = new java.util.concurrent.atomic.AtomicInteger();
        var storedVectors = new java.util.concurrent.ConcurrentHashMap<String, Double>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/embeddings", exchange -> {
            var request = new ObjectMapper().readTree(exchange.getRequestBody());
            var inputs = request.path("input");
            var response = new StringBuilder("{\"data\":[");
            if (inputs.size() > 1) {
                batchRequests.incrementAndGet();
                for (int i = 0; i < inputs.size(); i++) {
                    if (i > 0) response.append(',');
                    response.append("{\"index\":0,\"embedding\":[99.0,0.0]}");
                }
            } else {
                singletonRequests.incrementAndGet();
                String input = inputs.get(0).asText();
                int marker = input.contains("片段A") ? 1 : input.contains("片段B") ? 2 : 3;
                response.append("{\"index\":0,\"embedding\":[").append(marker).append(".0,0.0]}");
            }
            respond(exchange, 200, response.append("]}").toString());
        });
        server.createContext("/collections/batch-index", exchange ->
                respond(exchange, "GET".equals(exchange.getRequestMethod()) ? 404 : 200, "{\"result\":true}"));
        server.createContext("/collections/batch-index/points", exchange -> {
            var request = new ObjectMapper().readTree(exchange.getRequestBody());
            for (var point : request.path("points")) {
                storedVectors.put(point.path("payload").path("title").asText(), point.path("vector").get(0).asDouble());
            }
            respond(exchange, 200, "{\"result\":true}");
        });
        server.start();
        try {
            var index = new QdrantSemanticIndex(new ObjectMapper());
            String base = "http://127.0.0.1:" + server.getAddress().getPort();
            ReflectionTestUtils.setField(index, "model", "qwen3.7-text-embedding-flash");
            ReflectionTestUtils.setField(index, "apiKey", "test-only-key");
            ReflectionTestUtils.setField(index, "embeddingBaseUrl", base);
            ReflectionTestUtils.setField(index, "qdrantUrl", base);
            ReflectionTestUtils.setField(index, "collection", "batch-index");
            ReflectionTestUtils.setField(index, "batchSize", 3);

            assertTrue(index.ensureIndexed(List.of(
                    new Evidence("A", "source-a", "片段A", 1),
                    new Evidence("B", "source-b", "片段B", 1),
                    new Evidence("C", "source-c", "片段C", 1))));

            assertEquals("INDEXED", index.status());
            assertEquals(1, batchRequests.get(), "one malformed provider batch should be detected");
            assertEquals(3, singletonRequests.get(), "each source text should be retried alone");
            assertEquals(1.0, storedVectors.get("A"));
            assertEquals(2.0, storedVectors.get("B"));
            assertEquals(3.0, storedVectors.get("C"));
        } finally { server.stop(0); }
    }

    @Test
    void configuredSemanticIndexCreatesCollectionAndReturnsCitedChunk() throws Exception {
        var upsertedTitles = new CopyOnWriteArrayList<String>();
        var embeddingFormats = new CopyOnWriteArrayList<String>();
        var upsertedPoints = new java.util.concurrent.ConcurrentHashMap<String, com.fasterxml.jackson.databind.JsonNode>();
        var corruptPayload = new java.util.concurrent.atomic.AtomicBoolean();
        var collectionConfig = new AtomicReference<com.fasterxml.jackson.databind.JsonNode>();
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/embeddings", exchange -> {
            var body = new ObjectMapper().readTree(exchange.getRequestBody());
            embeddingFormats.add(body.path("encoding_format").asText());
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
            assertTrue(embeddingFormats.size() >= 2, "indexing and query embedding both exercised");
            assertTrue(embeddingFormats.stream().allMatch("float"::equals));
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

    @Test
    void reportsExistingCollectionIndexStateInsteadOfAssumingHnswIsBuilt() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/collections/index-state", exchange -> respond(exchange, 200,
                "{\"result\":{\"status\":\"green\",\"points_count\":12,\"indexed_vectors_count\":0,"
                        + "\"config\":{\"params\":{\"vectors\":{\"size\":1024,\"distance\":\"Cosine\"}},"
                        + "\"hnsw_config\":{\"m\":16,\"ef_construct\":100,\"full_scan_threshold\":10000}}}}"));
        server.start();
        try {
            var index = new QdrantSemanticIndex(new ObjectMapper());
            ReflectionTestUtils.setField(index, "qdrantUrl", "http://127.0.0.1:" + server.getAddress().getPort());
            ReflectionTestUtils.setField(index, "collection", "index-state");
            var actual = index.collectionConfiguration();
            assertEquals(12, actual.pointCount());
            assertEquals(0, actual.indexedVectorsCount(), "small collection may use exact scan without HNSW vectors");
            assertEquals(1024, actual.vectorSize());
            assertEquals(10000, actual.fullScanThresholdKb());
        } finally { server.stop(0); }
    }

    @Test
    void olderQdrantWithoutIndexCountReportsUnknownNotZero() throws Exception {
        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/collections/legacy-index", exchange -> respond(exchange, 200,
                "{\"result\":{\"status\":\"green\",\"points_count\":12,"
                        + "\"config\":{\"params\":{\"vectors\":{\"size\":1024,\"distance\":\"Cosine\"}},"
                        + "\"hnsw_config\":{\"m\":16,\"ef_construct\":100,\"full_scan_threshold\":10000}}}}"));
        server.start();
        try {
            var index = new QdrantSemanticIndex(new ObjectMapper());
            ReflectionTestUtils.setField(index, "qdrantUrl", "http://127.0.0.1:" + server.getAddress().getPort());
            ReflectionTestUtils.setField(index, "collection", "legacy-index");
            assertEquals(-1, index.collectionConfiguration().indexedVectorsCount());
        } finally { server.stop(0); }
    }

    private static void respond(com.sun.net.httpserver.HttpExchange exchange, int status, String body) throws java.io.IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var stream = exchange.getResponseBody()) { stream.write(bytes); }
    }
}
