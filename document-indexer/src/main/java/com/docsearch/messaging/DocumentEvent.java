package com.docsearch.messaging;

import java.util.UUID;

/**
 * Kafka payload published by document-service's OutboxRelay and consumed
 * here by DocumentIndexConsumer. Deliberately duplicated (not shared via a
 * common module) rather than published from a shared library - this is the
 * wire contract between two independently-deployable services, and each
 * side owns its own copy of it rather than taking a compile-time dependency
 * on the other. The JSON shape (field names, enum constant strings) must
 * stay in sync by convention; in production this would likely become a
 * versioned schema in a registry (e.g. Avro + Schema Registry) instead of a
 * hand-copied POJO - see document-indexer/README.md.
 * <p>
 * Carries only identifiers, not document content - this service re-reads
 * the current row from Postgres before indexing, so a burst of rapid edits
 * collapses to "index whatever is current" instead of replaying stale
 * intermediate versions out of order.
 */
public record DocumentEvent(UUID documentId, String tenantId, EventType eventType) {

    public enum EventType {
        INDEX, DELETE
    }
}
