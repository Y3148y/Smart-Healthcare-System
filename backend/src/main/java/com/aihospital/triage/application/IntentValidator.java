package com.aihospital.triage.application;

import com.aihospital.triage.domain.IntentExtraction;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadConstraints;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import java.io.IOException;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

/** Enforces the checked-in schema and source membership, not medical semantic correctness. */
@Component
public final class IntentValidator {
    private final ObjectMapper json = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .streamReadConstraints(StreamReadConstraints.builder().maxNestingDepth(20).maxStringLength(16000).build())
            .build()).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    private final JsonNode schema;
    private final String schemaText;

    public IntentValidator() {
        try (var stream = getClass().getResourceAsStream("/intent-extraction.schema.json")) {
            if (stream == null) throw new IllegalStateException("Missing intent schema");
            schema = json.readTree(stream);
            validateSchema(schema);
            schemaText = json.writeValueAsString(schema);
        } catch (IOException failure) { throw new IllegalStateException("Cannot load intent schema", failure); }
    }

    public String schemaText() { return schemaText; }

    private void validateSchema(JsonNode rule) {
        var allowed = java.util.Set.of("$schema", "title", "type", "additionalProperties", "required", "properties",
                "enum", "minItems", "maxItems", "uniqueItems", "items", "minLength", "maxLength");
        rule.fieldNames().forEachRemaining(key -> {
            if (!allowed.contains(key)) throw new IllegalStateException("Unsupported intent schema keyword: " + key);
        });
        if (rule.has("properties")) {
            if (!rule.has("additionalProperties") || rule.get("additionalProperties").asBoolean(true))
                throw new IllegalStateException("Intent objects must reject unknown fields");
            rule.get("properties").elements().forEachRemaining(this::validateSchema);
        }
        if (rule.has("items")) validateSchema(rule.get("items"));
    }

    public IntentExtraction validate(String output, IntentInput input) {
        if (output == null || output.isBlank() || output.length() > 48000) throw invalid();
        try {
            JsonNode root = json.readTree(output);
            validateNode(root, schema);
            IntentExtraction result = json.treeToValue(root, IntentExtraction.class);
            Map<String, IntentInput.SourceMessage> sources = new HashMap<>();
            for (var message : input.messages()) {
                if (message.messageId() == null || sources.put(message.messageId(), message) != null) throw invalid();
            }
            for (var event : result.events()) {
                validateSources(event.sourceRefs(), sources);
                String quotes = event.sourceRefs().stream().map(IntentExtraction.SourceRef::quote)
                        .reduce("", (a, b) -> a + "\n" + b);
                for (String field : new String[]{event.symptom(), event.bodySite(), event.onset(),
                        event.duration(), event.trigger(), event.severity()}) {
                    if (field != null && !quotes.contains(field)) throw invalid();
                }
            }
            for (var conflict : result.conflicts()) validateSources(conflict.sourceRefs(), sources);
            return result;
        } catch (IOException | IllegalArgumentException failure) {
            // Never expose source text or JSON parser exception messages in diagnostics.
            throw invalid();
        }
    }

    private void validateSources(java.util.List<IntentExtraction.SourceRef> refs,
                                 Map<String, IntentInput.SourceMessage> sources) {
        for (var ref : refs) {
            var source = sources.get(ref.messageId());
            if (source == null || !"USER".equals(source.role()) || source.content() == null
                    || !source.content().contains(ref.quote())) throw invalid();
        }
    }

    /** The schema uses only object/array/string/integer/null, enum and bounded-length constraints. */
    private void validateNode(JsonNode value, JsonNode rule) {
        if (value == null || !matchesType(value, rule.get("type"))) throw invalid();
        if (rule.has("enum")) {
            boolean match = false;
            for (var option : rule.get("enum")) if (option.equals(value)) match = true;
            if (!match) throw invalid();
        }
        if (value.isNull()) return;
        if (value.isObject()) {
            for (var key : rule.path("required")) if (!value.has(key.asText())) throw invalid();
            var names = value.fieldNames();
            while (names.hasNext()) {
                String name = names.next();
                JsonNode property = rule.path("properties").get(name);
                if (property == null) throw invalid();
                validateNode(value.get(name), property);
            }
        } else if (value.isArray()) {
            if (value.size() < rule.path("minItems").asInt(0)
                    || value.size() > rule.path("maxItems").asInt(Integer.MAX_VALUE)) throw invalid();
            var distinct = new HashSet<JsonNode>();
            for (var item : value) {
                if (rule.path("uniqueItems").asBoolean(false) && !distinct.add(item)) throw invalid();
                validateNode(item, rule.get("items"));
            }
        } else if (value.isTextual()) {
            String text = value.textValue();
            int length = text.codePointCount(0, text.length());
            if (text.isBlank() || length < rule.path("minLength").asInt(0)
                    || length > rule.path("maxLength").asInt(Integer.MAX_VALUE)) throw invalid();
        }
    }

    private boolean matchesType(JsonNode value, JsonNode type) {
        if (type == null) throw new IllegalStateException("Schema type required");
        if (type.isArray()) {
            for (var alternative : type) if (matchesType(value, alternative)) return true;
            return false;
        }
        return switch (type.asText()) {
            case "object" -> value.isObject();
            case "array" -> value.isArray();
            case "string" -> value.isTextual();
            case "integer" -> value.isIntegralNumber();
            case "null" -> value.isNull();
            default -> throw new IllegalStateException("Unsupported intent schema type");
        };
    }

    private IllegalArgumentException invalid() { return new IllegalArgumentException("INTENT_OUTPUT_INVALID"); }
}
