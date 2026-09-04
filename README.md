# Distributed Document Search Service

A prototype of a multi-tenant document search service built for the "Distributed Document
Search Service" technical assessment (`Software Engineer interview questions.pdf`).

**Full write-up** (architecture, production-readiness analysis, experience showcase, AI
usage note): [`docs/SUBMISSION.md`](docs/SUBMISSION.md). This README covers *running* the
prototype and the repo layout; the design reasoning lives in that document.

## Architecture at a glance

Two independently-deployable services plus four infrastructure dependencies:

- **[`document-service`](document-service/README.md)** — the REST API. Document CRUD,
  search, tenant/role enforcement, rate limiting, caching, and the transactional outbox
  (write side of async indexing).
- **[`document-indexer`](document-indexer/README.md)** — a Kafka consumer that indexes/deletes
  documents in Elasticsearch and writes status back to Postgres. Scales independently of the
  API tier (`docker compose up --scale document-indexer=3` adds consumer parallelism without
  touching the REST API).
- **PostgreSQL** (system of record) · **Elasticsearch** (search index) · **Redis** (cache +
  rate limits) · **Kafka** (async indexing, via a transactional outbox).

Full diagrams and the reasoning behind every one of these choices are in
[`docs/SUBMISSION.md`](docs/SUBMISSION.md) — this README intentionally doesn't duplicate them.

## Repo layout

```
document-service/     Standalone Maven project - the REST API (see its own README)
document-indexer/      Standalone Maven project - the Kafka consumer (see its own README)
docker-compose.yml      Postgres, Redis, Elasticsearch, Kafka, both app services
docs/SUBMISSION.md      Architecture + production-readiness + experience showcase
postman/                 Importable request collection
scripts/                 Demo data seeding, k6 load test, blue-green example
```

`document-service` and `document-indexer` are **two separate, standalone Maven
projects** — not a Maven multi-module/reactor build. There's no root `pom.xml`; each
has its own `pom.xml`, `Dockerfile`, and Maven wrapper, and builds independently. This
matches how they're actually deployed (two separate containers, scaled independently) —
a multi-module reactor would imply a shared build/release lifecycle that doesn't reflect
that reality, and would tempt in a shared internal dependency between two services that
are supposed to be independently deployable. The trade-off is a small amount of
deliberate duplication between them (the `DocumentEvent` Kafka message shape, a trimmed
read-only `DocumentEntity`) — called out explicitly in
[`document-indexer/README.md`](document-indexer/README.md#the-documentevent-duplication)
rather than hidden.

## Prerequisites

- **Docker Desktop** (with Compose) — the only requirement for the quickstart below.
- Or, to run a module outside Docker: **Java 21** (Maven itself isn't required — each
  module ships its own wrapper, `./mvnw`).

## Quickstart

```bash
docker compose up -d --build
```

This builds and starts both app services plus Postgres, Redis, Elasticsearch, and Kafka.
Wait for everything to report healthy (`docker compose ps`), then seed a few sample
documents:

```bash
./scripts/seed-demo-data.sh
```

Indexing is asynchronous (`document-service` → outbox → Kafka → `document-indexer` →
Elasticsearch; see [`docs/SUBMISSION.md` §1.5](docs/SUBMISSION.md#15-consistency-model-and-trade-offs)),
so give it ~2 seconds, then search:

```bash
TOKEN=$(curl -s -X POST http://localhost:8080/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"user@acme","password":"password123"}' | grep -o '"token":"[^"]*"' | cut -d'"' -f4)

curl -H "Authorization: Bearer $TOKEN" "http://localhost:8080/search?q=revenue&fuzzy=true"
```

A ready-to-import Postman collection is at
[`postman/document-search-service.postman_collection.json`](postman/document-search-service.postman_collection.json).

## Authentication

Every `document-service` endpoint except `/auth/login` and `/actuator/**` requires a
bearer token, obtained by logging in first:

```bash
curl -X POST http://localhost:8080/auth/login \
  -H "Content-Type: application/json" \
  -d '{"username":"admin@acme","password":"password123"}'
# {"token":"eyJ...", "expiresAt":"...", "tenantId":"acme", "role":"ADMIN"}
```

Send the returned token as `Authorization: Bearer <token>` on every subsequent request.
`tenantId` and `role` are **verified claims signed into the token at login time** — not
request headers the client can set — so a request can no longer just declare which
tenant/role it wants to act as (see `docs/SUBMISSION.md` §1.8/§2 for how this replaced the
prototype's earlier trusted-header design). Tokens expire after 60 minutes
(`JWT_EXPIRATION_MINUTES`).

Three demo accounts are seeded by Flyway (`V4__add_users.sql`), all with password
`password123`:

| Username | Tenant | Role |
|---|---|---|
| `admin@acme` | `acme` | `ADMIN` |
| `user@acme` | `acme` | `USER` |
| `admin@globex` | `globex` | `ADMIN` |

`ADMIN` has full CRUD; `USER` is read-only (`GET /documents/{id}`, `GET /search`). An
optional `Idempotency-Key` header on `POST /documents` makes a retried create with the
same key return the original document instead of duplicating it.

## Endpoints

| Method | Path | Role | Notes |
|---|---|---|---|
| `POST` | `/auth/login` | — | Exchanges username/password for a signed JWT (see Authentication above) |
| `POST` | `/documents` | `ADMIN` | Returns `202 Accepted` — indexing is async, `status: PENDING` |
| `GET` | `/documents/{id}` | `ADMIN` \| `USER` | Always Postgres-backed, always consistent |
| `DELETE` | `/documents/{id}` | `ADMIN` | Soft-delete, async removal from the index |
| `GET` | `/search?q=&page=&size=&tags=&fuzzy=&department=&category=&docType=` | `ADMIN` \| `USER` | `department`/`category`/`docType` are fixed, dropdown-driven facet filters (not derived from arbitrary metadata — see `docs/SUBMISSION.md`); omit `q` for a facet-only browse |
| `GET` | `/actuator/health` | — | `document-service`: management port `8081` (not `8080`), reachable from the host. `document-indexer`: port `8082`, but **not published to the host** — deliberately, so `docker compose up --scale document-indexer=N` can actually run multiple replicas (a fixed host port mapping blocks that outright, confirmed live — see `docs/SUBMISSION.md` §2 Scalability). Reachable from other containers on the compose network at `document-indexer:8082`, or via `docker exec` into the container, if you need to check it directly. |

Full curl walkthrough, including the rejection cases (missing/invalid token, wrong role,
unknown/suspended tenant), is in
[`document-service/README.md`](document-service/README.md).

## Running without Docker Compose (local dev)

`document-service` ships `TestDocumentServiceApplication` (in its `src/test`), which
boots the app with Testcontainers standing in for Postgres, Redis, and Kafka
automatically — see [its README](document-service/README.md#running-in-isolation) for
details. `document-indexer` has no equivalent convenience launcher; run it against
`docker compose up`'s infra services or your own instances — see
[its README](document-indexer/README.md#running-in-isolation).

## Tests

Each module has its own fast, Mockito-based unit tests (no Docker required) plus,
for `document-service`, a full Testcontainers integration test that needs Docker:

```bash
cd document-service && ./mvnw test -Dtest=DocumentServiceTest,SearchServiceTest   # 9/9 passing
cd document-indexer  && ./mvnw test -Dtest=DocumentIndexConsumerTest               # 4/4 passing
cd document-service && ./mvnw test                                                 # full suite, requires Docker
```

## What's verified vs. not

Being explicit about this rather than letting a thorough README imply more than was
actually exercised. Unlike earlier in this project's history, Docker *has* been run here,
and most of the stack has been exercised live, end-to-end, against real infrastructure —
this section reflects that, not a hedge against never having tried.

- **Verified live, against the real `docker compose` stack**: `docker compose up -d --build`
  brings up all six containers (Postgres, Redis, Elasticsearch, Kafka, `document-service`,
  `document-indexer`) healthy, including the `document-indexer` → `document-service`
  startup ordering. Flyway migrations `V1`–`V4` (including the `users` table and its seeded
  demo accounts) run cleanly against live Postgres. `POST /auth/login` issues a real,
  BCrypt-verified JWT; the full CRUD + search flow (create → async index → get → search
  with fuzzy matching, highlighting, and department/category/docType facets → delete →
  re-search confirms removal) has been run end-to-end with real bearer tokens. Tenant
  isolation, `ADMIN`-vs-`USER` role enforcement, missing/invalid-token rejection,
  idempotency-key replay, rate limiting (429 + `Retry-After`), and the cache miss→hit path
  are all confirmed against the live server, both via manual curl and via the full Postman
  collection (`npx newman run postman/...` — 25/25 assertions passing). A k6 load test
  sustained 2,298 req/s at 250 VUs, p95 = 19.55ms (see `scripts/README.md` for the full
  numbers and methodology) — comfortably clearing the assignment's 1000+ QPS / p95<500ms
  targets on a single-node dev stack.
- **Two real bugs were found and fixed during this live verification** (not just written
  and assumed correct): the Elasticsearch client's Jackson mapper was serializing `Instant`
  fields as raw epoch numbers, which Elasticsearch's `date` mapping rejected — every
  indexing attempt was silently failing (`INDEX_FAILED`) until this was caught by actually
  watching a document fail to index; and `@PreAuthorize`-denied requests were returning a
  misleading `500` instead of `403`, because `AuthorizationDeniedException` is thrown from
  inside Spring MVC's dispatch (not the security filter chain), so it never reached the
  `AccessDeniedHandler` configured for it — only reachable by actually attempting a
  role-denied request and reading the stack trace. Both are fixed in the current code.
- **A resilience audit found and fixed four more issues by actually killing each dependency
  against the running stack**, not by reading the code and guessing (full detail in
  `docs/SUBMISSION.md` §2 Resilience/Scalability): (1) a Redis outage took the *entire* API
  down with `500`s — writes, reads, and search all depend on it despite Redis being purely a
  latency optimization — now fixed to fail open, re-verified live with Redis stopped;
  (2) a Kafka outage stalled the outbox relay for a full 60 seconds per failed attempt
  (blocking on the producer's default metadata-wait timeout) before retrying — no data was
  ever lost (confirmed live), but recovery was far slower than it should be — lowered to 5s;
  (3) the Kafka topic auto-created with a single partition, which silently capped indexing
  parallelism at 1 no matter how consumer concurrency or replica count were set — now
  explicitly provisioned with 3 partitions; (4) `docker-compose.yml` published
  `document-indexer`'s port straight to the host, which made `docker compose up --scale
  document-indexer=3` — a claim made throughout this document — **fail outright**. Fixed,
  and re-verified live: 3 replicas actually get 3 different Kafka partitions assigned to 3
  different container hosts, not 1 doing the work and 2 sitting idle.
- **Not verified**: `DocumentApiIntegrationTest` (the Testcontainers-based JUnit suite)
  compiles but has not been executed as an automated test — the equivalent behavior it
  covers was instead verified manually (curl + Postman, above), which is not the same as
  the test itself having run. Multi-node/production topology is obviously untested — this
  is a single-node Postgres/Redis/Elasticsearch/Kafka stack, explicitly not a production
  cluster (see `docs/SUBMISSION.md` §2 Scalability). See
  [`docs/SUBMISSION.md` §4](docs/SUBMISSION.md#4-ai-tool-usage-note) for the fuller account.

## Assumptions and known simplifications

Documented in detail in `docs/SUBMISSION.md`, summarized here:

- **JWT auth is self-issued, not federated** — `document-service` signs its own tokens
  with a locally-held HMAC secret (Spring Security + `jjwt`); tenantId/role are verified,
  signed claims now, not trusted headers. Still a prototype simplification one level up
  from that: a real production deployment would verify tokens minted by an external
  identity provider via JWKS, not sign them itself — see §2 Security of the write-up.
- **RBAC is two roles via `@PreAuthorize`, not a policy engine** — no per-tenant custom
  permission store; `USER`/`ADMIN` is baked into each account's `users` row.
- **`POST /documents` returns `202 Accepted`, not `201`** — indexing is asynchronous by
  design, and the response communicates that (`status: PENDING`) rather than implying the
  document is immediately searchable.
- **document-indexer owns no schema** — it depends entirely on document-service's Flyway
  migrations having already run; see its README for how `docker-compose.yml` orders
  startup around this.
- **The `DocumentEvent` Kafka contract is duplicated, not shared**, between the two
  services — a deliberate trade-off for independent deployability, not an oversight; see
  [`document-indexer/README.md`](document-indexer/README.md#the-documentevent-duplication).
- **Single-node Elasticsearch/Kafka/Postgres/Redis** in `docker-compose.yml` — fine for a
  prototype, explicitly not production topology (see §2 Scalability/Resilience).
- **Benchmark numbers are real, measured ones** — 2,298 req/s at 250 VUs, p95 = 19.55ms,
  99.76% success, on a single-node local `docker compose` stack with no horizontal scaling.
  Full methodology, including why the per-tenant rate limit was temporarily raised for the
  measurement (it's a fairness control, not a capacity ceiling), is in `scripts/README.md`.
