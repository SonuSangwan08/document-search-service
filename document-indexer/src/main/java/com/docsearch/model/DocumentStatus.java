package com.docsearch.model;

public enum DocumentStatus {
    /** Persisted in Postgres, outbox event written, not yet visible in search. */
    PENDING,
    /** Successfully written to Elasticsearch; visible in search results. */
    INDEXED,
    /** The consumer exhausted its retries indexing this document; see DLQ. */
    INDEX_FAILED,
    /** Soft-deleted in Postgres; a DELETE event has been queued for Elasticsearch. */
    DELETED,
    /**
     * Soft-deleted in Postgres, but the consumer failed to remove it from
     * Elasticsearch - see DocumentIndexConsumer.handleDelete. Kept in sync
     * by hand with document-service's copy of this enum (and its
     * chk_document_status constraint, V5 migration) - this is exactly the
     * kind of drift the deliberate duplication between these two services
     * requires watching for, see document-indexer/README.md.
     */
    DELETE_FAILED
}
