package com.aihospital.knowledge.infrastructure.lexical;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;

class ElasticsearchContainerEndpointTest {
    private ElasticsearchLexicalSearch engine(boolean docker, boolean enabled, String url, String key) {
        var engine = new ElasticsearchLexicalSearch(new ObjectMapper(), () -> docker);
        ReflectionTestUtils.setField(engine, "localContainerDemo", enabled);
        ReflectionTestUtils.setField(engine, "url", url);
        ReflectionTestUtils.setField(engine, "apiKey", key);
        return engine;
    }
    @Test void explicitlyEnabledDockerDemoAcceptsOnlyTheCredentialFreeComposeEndpoint() {
        assertDoesNotThrow(() -> engine(true, true, "http://elasticsearch:9200", "").validate());
        assertDoesNotThrow(() -> engine(false, false, "http://127.0.0.1:9201", "").validate());
    }
    @Test void hostAndUnacknowledgedDockerCannotUseTheComposeHttpException() {
        assertThrows(IllegalArgumentException.class, () -> engine(false, true, "http://elasticsearch:9200", "").validate());
        assertThrows(IllegalArgumentException.class, () -> engine(true, false, "http://elasticsearch:9200", "").validate());
    }
    @Test void dockerExceptionCannotSendKeysOrAuthorizeOtherHostsPortsAndPaths() {
        for (String url : new String[]{"http://example.com:9200", "http://elasticsearch:9201",
                "http://elasticsearch", "http://user@elasticsearch:9200", "http://elasticsearch:9200/proxy",
                "http://elasticsearch:9200?x=1", "http://elasticsearch:9200#fragment"})
            assertThrows(IllegalArgumentException.class, () -> engine(true, true, url, "").validate());
        assertThrows(IllegalArgumentException.class, () -> engine(true, true, "http://elasticsearch:9200", "synthetic-key").validate());
    }
}
