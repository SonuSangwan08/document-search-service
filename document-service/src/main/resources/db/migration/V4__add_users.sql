-- Login accounts for JWT-based auth. Previously the app trusted a raw
-- X-Tenant-Id / X-User-Role header pair with no verification at all; now a
-- user authenticates via POST /auth/login and every subsequent request
-- carries a Spring-Security-verified JWT whose tenantId/role claims were
-- signed at login time, not supplied by the client on each call.
CREATE TABLE users (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       VARCHAR(64)  NOT NULL REFERENCES tenants(id),
    username        VARCHAR(255) NOT NULL UNIQUE,
    password_hash   VARCHAR(255) NOT NULL,
    role            VARCHAR(16)  NOT NULL,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT chk_user_role CHECK (role IN ('USER', 'ADMIN'))
);

-- Demo accounts so the login flow is testable immediately after
-- `docker compose up`, same spirit as the seed tenants in V1. Password for
-- all three is "password123" (BCrypt-hashed below, strength 10).
INSERT INTO users (tenant_id, username, password_hash, role) VALUES
    ('acme',   'admin@acme',   '$2a$10$UcKcOxc0DTmq6SXIik0TZ.ZdC9J/tCZTjLwsda2h1CopGRRu7PGn6', 'ADMIN'),
    ('acme',   'user@acme',    '$2a$10$UcKcOxc0DTmq6SXIik0TZ.ZdC9J/tCZTjLwsda2h1CopGRRu7PGn6', 'USER'),
    ('globex', 'admin@globex', '$2a$10$UcKcOxc0DTmq6SXIik0TZ.ZdC9J/tCZTjLwsda2h1CopGRRu7PGn6', 'ADMIN');
