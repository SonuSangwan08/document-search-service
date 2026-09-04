# document-service

The synchronous request path: document CRUD, search, tenant/role
enforcement, per-tenant rate limiting, caching, and the transactional
outbox (the write side of async indexing — see
[`document-indexer`](../document-indexer/README.md) for the consumer that
reads from it). See the root [`README.md`](../README.md) for the two-service
picture and [`docs/SUBMISSION.md`](../docs/SUBMISSION.md) for full
architecture/production-readiness reasoning.

## Package layout

Layered, not feature-grouped — `controller/` (REST endpoints), `service/` (business logic),
`repository/` (Spring Data), `model/` (JPA entities), `dto/` (request/response shapes), plus
`config/`, `security/` (JWT filter, rate limiter, tenant context), `exception/`, `messaging/`
(Kafka/outbox), and `health/` for the pieces that don't fit a CRUD layer. Look in `controller/`
first for any endpoint, `service/` for the logic behind it.

## What this service owns

- **REST API**: `POST /documents`, `GET /documents/{id}`, `DELETE
  /documents/{id}`, `GET /search` (+ `GET /actuator/health` on its own
  port — see below).
- **Authentication and tenant/role resolution** — `POST /auth/login`
  (`AuthController`) exchanges a username/password for a JWT signed by this
  service (`JwtService`, Spring Security + `jjwt`); every other endpoint is
  gated by `JwtAuthFilter`, which verifies the token's signature/expiry and
  populates `TenantContext` (tenant id) and Spring Security's context (role,
  as a `ROLE_ADMIN`/`ROLE_USER` authority) from its claims — not from
  trusted request headers. `@PreAuthorize("hasRole('ADMIN')")` on the write
  endpoints is Spring Security's real authorization mechanism, not a
  hand-rolled guard. Still a prototype simplification one level up: tokens
  are self-issued with a locally-held secret, not verified against an
  external identity provider via JWKS — see §2 Security in
  `docs/SUBMISSION.md` for that production evolution.
- **Rate limiting** — Redis-backed, per-tenant, separate budgets for
  `/search` vs. everything else.
- **Caching** — search results and document-by-id in Redis; an in-process
  Caffeine cache for the tenant-active check so every request doesn't hit
  Postgres just to validate the tenant header.
- **The `documents` and `users` table schemas** — all Flyway migrations
  live here (`src/main/resources/db/migration`), including `V4__add_users.sql`
  (login accounts). This service is the schema owner; document-indexer
  explicitly is not (see its README).
- **The transactional outbox** — `OutboxRelay` polls `document_outbox` and
  publishes `DocumentEvent`s to Kafka in the same transaction as the
  document write, so a `POST /documents` never loses its indexing event to
  a Postgres-committed-but-Kafka-publish-failed race.

## What it explicitly does NOT own

- **Consuming Kafka events or writing to Elasticsearch** — that's
  `document-indexer`. This service only *publishes* to
  `document-index-events`; it never listens to that topic.

## Authentication

Every endpoint except `POST /auth/login` and `/actuator/**` requires
`Authorization: Bearer <token>`, obtained by logging in:

```bash
curl -X POST http://localhost:8080/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin@acme","password":"password123"}'
```

`tenantId` and `role` are verified claims inside that token (checked by
`JwtAuthFilter` against the `tenants` table's active-status on every
request, same 30s-staleness trade-off as the header model it replaced) —
never a body field or a client-supplied header. Three demo accounts are
seeded by `V4__add_users.sql` (all password `password123`): `admin@acme`
(tenant `acme`, `ADMIN`), `user@acme` (tenant `acme`, `USER`),
`admin@globex` (tenant `globex`, `ADMIN`). `ADMIN` has full CRUD; `USER` is
read-only. An optional `Idempotency-Key` header on `POST /documents` makes
a repeated key for the same tenant return the original document instead of
creating a duplicate.

## Degradation behavior without each dependency

- **Postgres**: hard dependency — every endpoint needs it (tenant
  validation, document reads/writes). No graceful degradation in the
  prototype.
- **Redis**: also a hard dependency today (rate limiting and the tenant
  cache both call it directly with no fallback path) — a production version
  would fail-open on cache misses and treat Redis as non-critical for
  availability; see §2 Resilience in `docs/SUBMISSION.md`. Not implemented
  as a graceful-degradation path here.
- **Elasticsearch**: only `GET /search` depends on it; `POST /documents`,
  `GET /documents/{id}`, and `DELETE /documents/{id}` are unaffected by an
  Elasticsearch outage (by design — see the write path in
  `docs/SUBMISSION.md` §1.2).
- **Kafka**: `POST /documents` and `DELETE /documents/{id}` still succeed if
  Kafka is unreachable when `OutboxRelay` next polls — events just queue up
  in `document_outbox` and drain once Kafka recovers (that's the point of
  the outbox pattern).

## Why this is a separate service from document-indexer

Search/CRUD request traffic and Kafka-consumer indexing throughput scale on
different axes — one is latency-sensitive and read-heavy, the other is
bursty write throughput bound by Elasticsearch indexing rate and Kafka
partition count. Splitting them means `document-service` can be scaled by
request rate/CPU (HPA-style) while `document-indexer` scales by consumer
lag, independently, without either dragging the other's resource profile
along for the ride.

## Configuration

Full list in `src/main/resources/application.yml` / the root
[`.env.example`](../.env.example). Notably: `SERVER_PORT` (default `8080`)
serves the public API; `MANAGEMENT_PORT` (default `8081`) serves actuator
(`health`/`info`/`metrics`/`prometheus`) on a **separate** port so it's
never exposed alongside tenant-facing traffic.

## Running in isolation

`TestDocumentServiceApplication` (in `src/test`) boots the app with
Testcontainers standing in for Postgres, Redis, and Kafka automatically.
Elasticsearch isn't covered by that convenience launcher — run it
separately (`docker compose up -d elasticsearch` from the repo root) and
it'll be picked up on `localhost:9200` (the default).

## Tests

```bash
./mvnw test -Dtest=DocumentServiceTest,SearchServiceTest   # fast, no Docker required
./mvnw test                                                 # full suite, requires Docker
```

`DocumentServiceTest` and `SearchServiceTest` are pure-Mockito unit tests
and were run in this repo's build environment (9/9 passing, including the
idempotency-replay cases). `DocumentApiIntegrationTest` is a full
Testcontainers-based (Postgres + Kafka + Redis + Elasticsearch) end-to-end
test of the login → create → async-index → search → delete flow, plus
rejection cases (missing/invalid token, wrong login password, USER role
attempting a write); it compiles but **has not been run** — no Docker
daemon was available in the environment this was built in.
