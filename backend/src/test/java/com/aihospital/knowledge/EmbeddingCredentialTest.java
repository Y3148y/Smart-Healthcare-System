package com.aihospital.knowledge;

import com.aihospital.knowledge.infrastructure.qdrant.QdrantSemanticIndex;
import com.aihospital.shared.security.ApiCredentialCheck;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import static org.junit.jupiter.api.Assertions.*;

class EmbeddingCredentialTest {
    @Test void invalidHeaderCharactersNeverCountAsConfiguredCredentials() {
        var index = new QdrantSemanticIndex(new ObjectMapper());
        ReflectionTestUtils.setField(index, "model", "synthetic-model");
        ReflectionTestUtils.setField(index, "embeddingBaseUrl", "https://example.invalid");
        for (String invalid : new String[]{"", " ", "key\n", "\u0016", "key "}) {
            ReflectionTestUtils.setField(index, "apiKey", invalid);
            assertFalse(index.configured());
            assertFalse(ApiCredentialCheck.usable(invalid));
        }
        assertFalse(ApiCredentialCheck.usable(null));
        ReflectionTestUtils.setField(index, "apiKey", "synthetic-test-key");
        assertTrue(index.configured());
    }
}
