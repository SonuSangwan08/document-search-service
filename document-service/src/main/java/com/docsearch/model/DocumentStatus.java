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
     * Elasticsearch (e.g. ES was unreachable) - confirmed by review that
     * without this status, that failure was silently swallowed and the
     * document stayed indexed/searchable forever with no marker at all,
     * worse than the already-tracked INDEX_FAILED case. No automatic retry
     * here either (same DLQ gap noted on INDEX_FAILED); this is what a
     * manual/future replay tool would query for.
     */
    DELETE_FAILED
}
