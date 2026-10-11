package com.aihospital.triage;

import com.aihospital.triage.application.IntentInput;
import com.aihospital.triage.application.IntentValidator;
import com.aihospital.triage.domain.IntentExtraction;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.junit.jupiter.api.Assertions.*;

class IntentValidatorTest {
    private final IntentValidator validator = new IntentValidator();
    private final ObjectMapper json = new ObjectMapper();
    private final IntentInput input = new IntentInput(List.of(
            new IntentInput.SourceMessage("a1", "ASSISTANT", "是否胸痛？"),
            new IntentInput.SourceMessage("u1", "USER", "我母亲以前头痛两天，现在没有胸痛，我只想了解健康知识")));

    private ObjectNode valid() throws Exception {
        return (ObjectNode) json.readTree("""
                {"schemaVersion":1,"intents":["GENERAL_HEALTH"],"events":[{
                  "symptom":"头痛","bodySite":null,"onset":null,"duration":"两天","trigger":null,
                  "severity":null,"subject":"OTHER","temporality":"PAST","assertion":"AFFIRMED",
                  "sourceRefs":[{"messageId":"u1","quote":"我母亲以前头痛两天"}]}],
                  "missingInformation":[],"conflicts":[]}
                """);
    }

    @Test void preservesOtherPastAndUnknownInsteadOfFillingNormalValues() throws Exception {
        var value = validator.validate(valid().toString(), input);
        assertEquals(IntentExtraction.Subject.OTHER, value.events().get(0).subject());
        assertEquals(IntentExtraction.Temporality.PAST, value.events().get(0).temporality());
        assertNull(value.events().get(0).severity());
        assertTrue(value.missingInformation().isEmpty());
    }

    @Test void preservesExplicitNegationAndMultipleIntents() throws Exception {
        var root = valid();
        root.withArray("intents").add("FIND_DEPARTMENT");
        var event = (ObjectNode) root.withArray("events").get(0);
        event.put("symptom", "胸痛").putNull("duration").put("subject", "SELF")
                .put("temporality", "CURRENT").put("assertion", "NEGATED");
        ((ObjectNode) event.withArray("sourceRefs").get(0)).put("quote", "现在没有胸痛");
        var result = validator.validate(root.toString(), input);
        assertEquals(2, result.intents().size());
        assertEquals(IntentExtraction.Assertion.NEGATED, result.events().get(0).assertion());
    }

    @Test void rejectsAssistantOrInventedSourceOrNonLiteralQuote() throws Exception {
        for (String id : List.of("a1", "invented", "u1")) {
            var root = valid();
            var ref = (ObjectNode) root.withArray("events").get(0).path("sourceRefs").get(0);
            ref.put("messageId", id).put("quote", "是否胸痛？");
            assertThrows(IllegalArgumentException.class, () -> validator.validate(root.toString(), input));
        }
    }

    @Test void rejectsGuessedDiagnosisAndTimeCourseOutsideQuotedText() throws Exception {
        for (String field : List.of("symptom", "duration", "bodySite", "trigger", "severity", "onset")) {
            var root = valid();
            ((ObjectNode) root.withArray("events").get(0)).put(field, "未经患者描述的内容");
            var error = assertThrows(IllegalArgumentException.class, () -> validator.validate(root.toString(), input));
            assertEquals("INTENT_OUTPUT_INVALID", error.getMessage());
        }
    }

    @Test void rejectsUnknownMissingCoercedAndDuplicateFields() throws Exception {
        var root = valid(); root.put("risk", "LOW");
        String unknownField = root.toString();
        assertThrows(IllegalArgumentException.class, () -> validator.validate(unknownField, input));
        root = valid(); root.remove("conflicts");
        String missing = root.toString();
        assertThrows(IllegalArgumentException.class, () -> validator.validate(missing, input));
        String coerced = valid().toString().replace("\"schemaVersion\":1", "\"schemaVersion\":\"1\"");
        assertThrows(IllegalArgumentException.class, () -> validator.validate(coerced, input));
        String duplicate = valid().toString().replace("\"schemaVersion\":1", "\"schemaVersion\":1,\"schemaVersion\":1");
        assertThrows(IllegalArgumentException.class, () -> validator.validate(duplicate, input));
    }

    @Test void rejectsUnknownEnumRepeatedIntentNullArrayAndTrailingJson() throws Exception {
        var root = valid(); root.withArray("intents").add("GENERAL_HEALTH");
        String repeated = root.toString();
        assertThrows(IllegalArgumentException.class, () -> validator.validate(repeated, input));
        String unknown = valid().toString().replace("GENERAL_HEALTH", "DELETE_KNOWLEDGE");
        assertThrows(IllegalArgumentException.class, () -> validator.validate(unknown, input));
        root = valid(); root.putNull("events");
        String nullEvents = root.toString();
        assertThrows(IllegalArgumentException.class, () -> validator.validate(nullEvents, input));
        assertThrows(IllegalArgumentException.class, () -> validator.validate(valid() + " {}", input));
    }

    @Test void boundsOutputAndEventArrays() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> validator.validate(" ".repeat(48001), input));
        var root = valid(); var event = root.withArray("events").get(0).deepCopy();
        for (int i = 0; i < 21; i++) root.withArray("events").add(event);
        assertThrows(IllegalArgumentException.class, () -> validator.validate(root.toString(), input));
    }
}
