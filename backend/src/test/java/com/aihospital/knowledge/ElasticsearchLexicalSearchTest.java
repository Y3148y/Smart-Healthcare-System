package com.aihospital.knowledge;

import com.aihospital.knowledge.infrastructure.lexical.ElasticsearchLexicalSearch;
import com.aihospital.shared.model.Models.Evidence;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import static org.junit.jupiter.api.Assertions.*;

class ElasticsearchLexicalSearchTest {
    @Test void approvedSnapshotsAreIsolatedAndSourceIdentityIsValidated() throws Exception {
        try (var fixture = new Fixture()) {
            var first = new Evidence("头痛", "source-a", "偏头痛伴恶心", 0);
            var second = new Evidence("咳嗽", "source-b", "持续咳嗽", 0);
            var engine = fixture.engine();
            assertEquals(List.of("头痛"), engine.search("偏头痛", List.of(first), 3, 10000).evidence().stream().map(Evidence::title).toList());
            assertEquals(1, fixture.indexes.size());
            engine.search("头痛", List.of(first), 3, 0);
            assertEquals(1, fixture.bulkCalls);
            engine.search("咳嗽", List.of(first, second), 3, 0);
            assertEquals(2, fixture.indexes.size());
            var withdrawn = engine.search("咳嗽", List.of(second), 3, 0);
            assertEquals(List.of("咳嗽"), withdrawn.evidence().stream().map(Evidence::title).toList());
            assertEquals(3, fixture.indexes.size());
            assertEquals("cjk", fixture.mapping.path("mappings").path("properties").path("body").path("analyzer").asText());
            assertEquals("BM25", fixture.mapping.path("settings").path("similarity").path("medical_bm25").path("type").asText());
            assertEquals(0, fixture.query.path("min_score").asDouble());
            assertEquals("title^2.0", fixture.query.path("query").path("multi_match").path("fields").get(0).asText());
            fixture.corrupt.set(true);
            var invalid = engine.search("咳嗽", List.of(second), 3, 0);
            assertEquals("ELASTICSEARCH_INVALID_RESPONSE", invalid.status());
            assertTrue(invalid.evidence().isEmpty());
        }
    }

    @Test void bulkFailureDoesNotPublishSnapshotAndNextRequestRetries() throws Exception {
        try (var fixture = new Fixture()) {
            var corpus = List.of(new Evidence("alpha", "a", "beta", 0));
            var engine = fixture.engine();
            fixture.bulkFailure.set(true);
            assertEquals("ELASTICSEARCH_UNAVAILABLE", engine.search("alpha", corpus, 3, 0).status());
            assertEquals(0, fixture.searchCalls);
            fixture.bulkFailure.set(false);
            assertEquals("ELASTICSEARCH_READY", engine.search("alpha", corpus, 3, 0).status());
            assertEquals(2, fixture.bulkCalls);
            assertTrue(engine.search("alpha", List.of(), 3, 0).evidence().isEmpty());
            assertEquals(1, fixture.searchCalls);
            // A new process safely reuses the existing index and idempotently refreshes its contents.
            assertEquals("ELASTICSEARCH_READY", fixture.engine().search("alpha", corpus, 3, 0).status());
            assertEquals(1, fixture.indexes.size());
        }
    }

    @Test void invalidEndpointAndScoreConfigurationAreRejected() {
        var engine = new ElasticsearchLexicalSearch(new ObjectMapper());
        ReflectionTestUtils.setField(engine, "url", "http://example.com:9200");
        assertThrows(IllegalArgumentException.class, engine::validate);
        ReflectionTestUtils.setField(engine, "url", "https://user:secret@example.com");
        assertThrows(IllegalArgumentException.class, engine::validate);
        ReflectionTestUtils.setField(engine, "url", "http://127.0.0.1:9200");
        ReflectionTestUtils.setField(engine, "minimum", Double.NaN);
        assertThrows(IllegalArgumentException.class, engine::validate);
    }

    private static final class Fixture implements AutoCloseable {
        final ObjectMapper json = new ObjectMapper();
        final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        final Map<String, Map<String, JsonNode>> indexes = new LinkedHashMap<>();
        final Map<String, JsonNode> mappings = new HashMap<>();
        final AtomicBoolean corrupt = new AtomicBoolean(), bulkFailure = new AtomicBoolean();
        JsonNode mapping, query;
        int bulkCalls, searchCalls;
        Fixture() throws Exception {
            server.createContext("/", exchange -> {
                int status = 200;
                Object response;
                String path = exchange.getRequestURI().getPath();
                String index = path.split("/")[1];
                if (path.endsWith("/_mapping")) {
                    response = Map.of(index, mappings.get(index));
                } else if (path.endsWith("/_bulk")) {
                    bulkCalls++;
                    String[] lines = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8).split("\n");
                    var items = new ArrayList<Object>();
                    for (int i = 0; i < lines.length; i += 2) {
                        indexes.get(index).put(json.readTree(lines[i]).path("index").path("_id").asText(), json.readTree(lines[i + 1]));
                        items.add(Map.of("index", Map.of("status", bulkFailure.get() ? 429 : 201)));
                    }
                    response = Map.of("errors", bulkFailure.get(), "items", items);
                } else if (path.endsWith("/_search")) {
                    searchCalls++;
                    query = json.readTree(exchange.getRequestBody());
                    var hits = new ArrayList<Object>();
                    indexes.get(index).forEach((id, source) -> hits.add(Map.of("_id", corrupt.get() ? "stale" : id,
                            "_score", 2.0, "_source", source)));
                    response = Map.of("timed_out", false, "_shards", Map.of("failed", 0), "hits", Map.of("hits", hits));
                } else if (indexes.containsKey(index)) {
                    status = 400;
                    response = Map.of("error", Map.of("type", "resource_already_exists_exception"));
                } else {
                    mapping = json.readTree(exchange.getRequestBody());
                    mappings.put(index, mapping);
                    indexes.put(index, new LinkedHashMap<>());
                    response = Map.of("acknowledged", true);
                }
                byte[] body = json.writeValueAsBytes(response);
                exchange.sendResponseHeaders(status, body.length);
                exchange.getResponseBody().write(body);
                exchange.close();
            });
            server.start();
        }
        ElasticsearchLexicalSearch engine() {
            var engine = new ElasticsearchLexicalSearch(json);
            ReflectionTestUtils.setField(engine, "url", "http://127.0.0.1:" + server.getAddress().getPort());
            engine.validate();
            return engine;
        }
        @Override public void close() { server.stop(0); }
    }
}
