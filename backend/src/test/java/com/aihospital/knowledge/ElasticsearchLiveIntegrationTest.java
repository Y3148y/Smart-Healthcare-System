package com.aihospital.knowledge;

import com.aihospital.knowledge.infrastructure.lexical.ElasticsearchLexicalSearch;
import com.aihospital.shared.model.Models.Evidence;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.test.util.ReflectionTestUtils;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;

/** Explicit isolated ES target only; synthetic corpus and exact owned-index cleanup. */
@EnabledIfSystemProperty(named = "ai.test.elasticsearch-url", matches = ".+")
class ElasticsearchLiveIntegrationTest {
    @Test void realLuceneAnalysisIndexingIncrementAndWithdrawal() throws Exception {
        String url = System.getProperty("ai.test.elasticsearch-url");
        URI endpoint = URI.create(url);
        assertEquals("http", endpoint.getScheme());
        assertTrue(Set.of("127.0.0.1", "localhost").contains(endpoint.getHost()));
        assertNull(endpoint.getUserInfo());
        assertNull(endpoint.getQuery());
        assertNull(endpoint.getFragment());
        assertTrue(endpoint.getPath().isEmpty());
        String prefix = "ai-hospital-test-" + UUID.randomUUID().toString();
        var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
        var json = new ObjectMapper();
        var engine = new ElasticsearchLexicalSearch(json);
        ReflectionTestUtils.setField(engine, "url", url);
        ReflectionTestUtils.setField(engine, "prefix", prefix);
        engine.validate();
        try {
            var headache = new Evidence("头痛资料", "synthetic-headache", "偏头痛伴恶心，需要进一步评估。", 0);
            var cough = new Evidence("咳嗽资料", "synthetic-cough", "持续咳嗽的评估。", 0);
            var first = engine.search("偏头痛", List.of(headache), 3, 0);
            assertEquals("ELASTICSEARCH_READY", first.status());
            assertEquals("synthetic-headache", first.evidence().get(0).source());
            var increment = engine.search("咳嗽", List.of(headache, cough), 3, 0);
            assertEquals("ELASTICSEARCH_READY", increment.status());
            assertEquals("synthetic-cough", increment.evidence().get(0).source());
            assertTrue(engine.search("头痛", List.of(cough), 3, 0).evidence().isEmpty());
            // Native analyzer output is tested, not a locally simulated tokenizer.
            var analyzed = client.send(HttpRequest.newBuilder(URI.create(url + "/_analyze"))
                    .timeout(Duration.ofSeconds(5)).header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"analyzer\":\"cjk\",\"text\":\"急性心肌梗死\"}")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, analyzed.statusCode());
            var terms = new ArrayList<String>();
            json.readTree(analyzed.body()).path("tokens").forEach(token -> terms.add(token.path("token").asText()));
            assertEquals(List.of("急性", "性心", "心肌", "肌梗", "梗死"), terms);
        } finally {
            var listed = client.send(HttpRequest.newBuilder(URI.create(url + "/_cat/indices/" + prefix + "-*?format=json"))
                    .timeout(Duration.ofSeconds(5)).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, listed.statusCode());
            for (var index : json.readTree(listed.body())) {
                String name = index.path("index").asText();
                assertTrue(name.matches(java.util.regex.Pattern.quote(prefix) + "-[0-9a-f]{64}"));
                var removed = client.send(HttpRequest.newBuilder(URI.create(url + "/" + name)).timeout(Duration.ofSeconds(5))
                        .DELETE().build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(200, removed.statusCode());
            }
        }
    }
}
