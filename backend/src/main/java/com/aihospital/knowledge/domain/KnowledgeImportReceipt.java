package com.aihospital.knowledge.domain;

/** Persisted idempotency binding for a structured knowledge import. */
public record KnowledgeImportReceipt(StoredKnowledgeDocument document, String requestHash) {}
