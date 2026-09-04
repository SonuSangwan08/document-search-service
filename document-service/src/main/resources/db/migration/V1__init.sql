-- Tenants: the root of the multi-tenancy model. Every document row and every
-- Elasticsearch document carries a tenant_id that must resolve to an active
-- row here before any read/write is allowed.
CREATE TABLE tenants (
    id              VARCHAR(64) PRIMARY KEY,
    name            VARCHAR(255) NOT NULL,
    status          VARCHAR(16)  NOT NULL DEFAULT 'ACTIVE',
    plan            VARCHAR(32)  NOT NULL DEFAULT 'STANDARD',
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_tenant_status CHECK (status IN ('ACTIVE', 'SUSPENDED'))
);

-- Documents: the system of record. Elasticsearch holds a derived, eventually
-- consistent search index built from these rows via the Kafka outbox below;
-- Postgres is always the source of truth for GET /documents/{id}.
CREATE TABLE documents (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       VARCHAR(64)  NOT NULL REFERENCES tenants(id),
    title           VARCHAR(512) NOT NULL,
    content         TEXT         NOT NULL,
    tags            TEXT[]       NOT NULL DEFAULT '{}',
    metadata        JSONB        NOT NULL DEFAULT '{}',
    status          VARCHAR(16)  NOT NULL DEFAULT 'PENDING',
    version         BIGINT       NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    deleted_at      TIMESTAMPTZ,
    CONSTRAINT chk_document_status CHECK (status IN ('PENDING', 'INDEXED', 'INDEX_FAILED', 'DELETED'))
);

-- Every tenant-scoped query filters on (tenant_id, id) or (tenant_id, status);
-- this composite index keeps both patterns on a single index scan and keeps
-- one tenant's rows physically clustered away from another's.
CREATE INDEX idx_documents_tenant_status ON documents (tenant_id, status) WHERE deleted_at IS NULL;
CREATE INDEX idx_documents_tenant_created ON documents (tenant_id, created_at DESC);

-- Outbox table for the async indexing pipeline: the write to `documents` and
-- the write to `document_outbox` happen in the same DB transaction, so a
-- publish to Kafka is never lost even if the app crashes between "commit"
-- and "publish". A relay polls this table and publishes+deletes rows.
CREATE TABLE document_outbox (
    id              BIGSERIAL PRIMARY KEY,
    document_id     UUID        NOT NULL,
    tenant_id       VARCHAR(64) NOT NULL,
    event_type      VARCHAR(16) NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    CONSTRAINT chk_outbox_event_type CHECK (event_type IN ('INDEX', 'DELETE'))
);
CREATE INDEX idx_outbox_created ON document_outbox (created_at);

-- Seed demo tenants so the prototype is usable immediately after `docker compose up`.
INSERT INTO tenants (id, name, plan) VALUES
    ('acme',   'Acme Corporation', 'ENTERPRISE'),
    ('globex', 'Globex Inc.',      'STANDARD');
