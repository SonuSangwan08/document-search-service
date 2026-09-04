-- Confirmed by review: a DELETE event that failed in the consumer (e.g.
-- Elasticsearch briefly unreachable) was silently swallowed - the document
-- ended up DELETED in Postgres but stayed indexed/searchable in
-- Elasticsearch forever, with no status marker at all. DELETE_FAILED closes
-- that gap the same way INDEX_FAILED already covers a failed INDEX.
ALTER TABLE documents DROP CONSTRAINT chk_document_status;
ALTER TABLE documents ADD CONSTRAINT chk_document_status
    CHECK (status IN ('PENDING', 'INDEXED', 'INDEX_FAILED', 'DELETED', 'DELETE_FAILED'));
