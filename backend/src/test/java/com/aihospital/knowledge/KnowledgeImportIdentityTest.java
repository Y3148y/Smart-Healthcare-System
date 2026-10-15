package com.aihospital.knowledge;

import com.aihospital.knowledge.domain.KnowledgeImportIdentity;
import com.aihospital.knowledge.domain.KnowledgeMetadata;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class KnowledgeImportIdentityTest {
    private final ObjectMapper json = new ObjectMapper();

    @Test void utf8FingerprintHasPinnedVectorAndExactReplay() {
        String expected = "sha256:40425081757fa3c4ec5417c24217c20cd2a4a87cb2ea17c106a2a7b8ebd7f7f6";
        assertEquals(expected, KnowledgeImportIdentity.key("标题", "正文"));
        assertTrue(KnowledgeImportIdentity.validKey(expected, "标题", "正文"));
        assertFalse(KnowledgeImportIdentity.validKey(expected.toUpperCase(), "标题", "正文"));
        assertFalse(KnowledgeImportIdentity.validKey(null, "标题", "正文"));
    }

    @Test void whitespacePunctuationAndLineEndingsAreNewContentNotSemanticDuplicates() {
        String title = "synthetic", body = "alpha，beta\nnext";
        String key = KnowledgeImportIdentity.key(title, body);
        for (String changed : List.of("alpha,beta\nnext", "alpha， beta\nnext", "alpha，beta\r\nnext", body + " ")) {
            assertNotEquals(key, KnowledgeImportIdentity.key(title, changed));
            assertFalse(KnowledgeImportIdentity.validKey(key, title, changed));
        }
        assertNotEquals(key, KnowledgeImportIdentity.key(title + " ", body));
    }

    @Test void metadataChangesBindServerHashWithoutChangingClientContentKey() {
        var first = metadata(List.of("first", "second"), "https://example.org/one");
        var reordered = metadata(List.of("second", "first"), "https://example.org/one");
        var changedSource = metadata(List.of("first", "second"), "https://example.org/two");
        String original = KnowledgeImportIdentity.requestHash("title", "body", first, json);
        assertEquals(original, KnowledgeImportIdentity.requestHash("title", "body", first, json));
        assertNotEquals(original, KnowledgeImportIdentity.requestHash("title", "body", reordered, json));
        assertNotEquals(original, KnowledgeImportIdentity.requestHash("title", "body", changedSource, json));
        assertTrue(KnowledgeImportIdentity.validKey(KnowledgeImportIdentity.key("title", "body"), "title", "body"));
    }

    @Test void incomingJsonPropertyOrderDoesNotChangeTypedMetadataFingerprint() throws Exception {
        var original = metadata(List.of("topic"), "https://example.org/one");
        var node = json.valueToTree(original);
        var reverse = json.createObjectNode();
        var names = new java.util.ArrayList<String>();node.fieldNames().forEachRemaining(names::add);
        java.util.Collections.reverse(names);
        names.forEach(name -> reverse.set(name, node.get(name)));
        var decoded = json.treeToValue(reverse, KnowledgeMetadata.class);
        assertEquals(original, decoded);
        assertEquals(KnowledgeImportIdentity.requestHash("title", "body", original, json),
                KnowledgeImportIdentity.requestHash("title", "body", decoded, json));
    }

    private KnowledgeMetadata metadata(List<String> topics, String source) {
        return new KnowledgeMetadata(1, "zh-CN", "reviewed_summary",
                List.of(new KnowledgeMetadata.Source("fixture", "synthetic", source, null, null)),
                topics, List.of("adult"), List.of(), List.of(), List.of("general_information"), "pending", null);
    }
}
