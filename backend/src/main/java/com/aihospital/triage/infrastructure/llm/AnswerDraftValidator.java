package com.aihospital.triage.infrastructure.llm;

import com.aihospital.triage.domain.AnswerEvidence.*;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.*;

/** Schema and reference integrity only; does not certify medical correctness. */
final class AnswerDraftValidator {
    private static final ObjectMapper JSON = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    record Checked(String text, List<String> adopted, List<String> uncovered, Draft draft) {}
    static class InvalidDraftException extends IllegalArgumentException {
        private final String code;
        InvalidDraftException(String code) { super(code); this.code = "OUTPUT_" + code; }
        String code() { return code; }
    }
    static final class InvalidReferenceException extends InvalidDraftException {
        InvalidReferenceException() { super("REFERENCE_INVALID"); }
    }
    static Checked check(String raw, List<Reference> references) throws Exception {
        if (raw == null || raw.length() > 16000) throw new InvalidDraftException("SIZE_INVALID");
        Draft draft = JSON.readValue(raw, Draft.class);
        if (draft == null || draft.paragraphs() == null || draft.paragraphs().isEmpty()
                || draft.paragraphs().size() > 8 || draft.questions() == null || draft.questions().size() > 2
                || draft.uncovered() == null || draft.uncovered().size() > 8)
            throw new InvalidDraftException("SCHEMA_INVALID");
        Set<String> allowed = new HashSet<>();
        references.forEach(ref -> allowed.add(ref.id()));
        Set<String> adopted = new LinkedHashSet<>();
        List<String> text = new ArrayList<>();
        for (Paragraph paragraph : draft.paragraphs()) {
            if (paragraph == null) throw new InvalidDraftException("PARAGRAPH_NULL");
            if (paragraph.text() == null || paragraph.text().isBlank()) throw new InvalidDraftException("PARAGRAPH_TEXT_INVALID");
            if (paragraph.referenceIds() == null) throw new InvalidDraftException("REFERENCES_MISSING");
            if (paragraph.referenceIds().size() > references.size()) throw new InvalidDraftException("REFERENCE_COUNT_INVALID");
            if (paragraph.kind() == null || !Set.of("GENERAL_INFORMATION", "LIMITATION").contains(paragraph.kind()))
                throw new InvalidDraftException("PARAGRAPH_KIND_INVALID");
            if (!allowed.containsAll(paragraph.referenceIds())) throw new InvalidReferenceException();
            if (paragraph.kind().equals("GENERAL_INFORMATION") && paragraph.referenceIds().isEmpty())
                throw new InvalidReferenceException();
            adopted.addAll(paragraph.referenceIds());
            text.add(paragraph.text());
        }
        for (String question : draft.questions()) {
            if (question == null || question.isBlank() || question.length() > 200)
                throw new InvalidDraftException("QUESTION_INVALID");
            text.add(question);
        }
        for (String uncovered : draft.uncovered())
            if (uncovered == null || uncovered.isBlank() || uncovered.length() > 200)
                throw new InvalidDraftException("UNCOVERED_INVALID");
        String joined = String.join("\n\n", text);
        if (joined.length() > 1200) throw new InvalidDraftException("TEXT_SIZE_INVALID");
        return new Checked(joined, List.copyOf(adopted), List.copyOf(draft.uncovered()), draft);
    }
}
