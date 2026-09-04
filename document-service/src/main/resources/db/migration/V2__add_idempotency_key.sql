-- Client-supplied Idempotency-Key header on POST /documents: a retried
-- create with the same key returns the original document instead of
-- creating a duplicate. Partial unique index (not a full column constraint)
-- since the key is optional - most rows will have it NULL, and NULLs must
-- not collide with each other.
ALTER TABLE documents ADD COLUMN idempotency_key VARCHAR(255);

CREATE UNIQUE INDEX uq_documents_tenant_idempotency_key
    ON documents (tenant_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;
