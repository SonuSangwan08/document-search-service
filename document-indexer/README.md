# document-indexer

The async write side of search: consumes `DocumentEvent`s from Kafka
(`document-index-events`), indexes or deletes the corresponding document in
Elasticsearch, and writes the resulting status (`INDEXED` / `INDEX_FAILED`)
back to Postgres. See the root [`README.md`](../README.md) for the two-service
picture and [`docs/SUBMISSION.md`](../docs/SUBMISSION.md) for full
architecture/production-readiness reasoning.

## Package layout

Layered, not feature-grouped, same convention as `document-service` — `service/`
(`ElasticsearchDocumentIndex`), `repository/` (the trimmed read/status-update `DocumentRepository`),
`model/` (entities), `messaging/` (`DocumentEvent`, `DocumentIndexConsumer` — kept together
deliberately, see below), `config/`, `health/`. No `controller/` package here — this service
has no HTTP API beyond actuator.

## What this service owns

- The `@KafkaListener` on `document-index-events` (`DocumentIndexConsumer`),
  consumer group `document-indexer`, concurrency 3.
- Writing to Elasticsearch: `index()` on an `INDEX` event, `delete()` on a
  `DELETE` event (`ElasticsearchDocumentIndex` — write-only here; query
  building for search lives in document-service's copy of this class).
- Updating `documents.status`/`updated_at` in Postgres after a successful or
  failed index attempt.

## What it explicitly does NOT own

- **The `documents` table schema.** All Flyway migrations live in
  `document-service/src/main/resources/db/migration` — this service runs
  with `spring.jpa.hibernate.ddl-auto: none` and no Flyway dependency at
  all, and assumes the table already exists by the time it starts. In
  `docker-compose.yml`, `document-indexer` depends on `document-service`
  being **healthy** (not just Postgres), specifically to get this ordering
  right — document-service's Flyway migration runs on its own startup.
- **Search.** This service never queries Elasticsearch, only writes to it.
- **The public REST API, tenant/role enforcement, rate limiting, caching.**
  All of that is document-service.

## Why this is a separate service

Indexing throughput and API request throughput have very different
scaling profiles — indexing is bursty, CPU/IO-bound Elasticsearch writes;
the API is latency-sensitive, cache-heavy read traffic. Splitting them
means you can scale consumer parallelism independently:

```bash
docker compose up --scale document-indexer=3
```

adds more members to the same Kafka consumer group, up to the
`document-index-events` topic's partition count (parallelism beyond that
just sits idle — partition count is the real ceiling, not instance count).
None of that requires spinning up more copies of the REST API tier, and
vice versa.

## The `DocumentEvent` duplication

`messaging/DocumentEvent.java` here is a **deliberate duplicate** of
document-service's own `DocumentEvent`, not a shared dependency — these are
two independently-deployable services, and each owns its copy of the wire
contract rather than taking a compile-time dependency on the other (which
would defeat independent deployability). The JSON shape (field names, enum
constant strings `INDEX`/`DELETE`) has to stay in sync by convention; in a
real production system this would more likely be a versioned schema in a
registry (e.g. Avro + Schema Registry) with compatibility checks enforced
at publish time, rather than a hand-copied POJO. Called out explicitly here
because it's a real trade-off, not an oversight.

The same applies to `document/DocumentEntity.java` — this service's copy is
a trimmed, read/update-only view of the row document-service owns, mapping
only the columns `DocumentIndexConsumer` actually touches.

## Configuration

| Env var | Default | Notes |
|---|---|---|
| `DB_HOST` / `DB_PORT` / `DB_NAME` / `DB_USER` / `DB_PASSWORD` | `localhost` / `5432` / `docsearch` / `docsearch` / `docsearch` | Same Postgres instance/database as document-service |
| `ES_URIS` | `http://localhost:9200` | |
| `ES_INDEX` | `documents` | Must match document-service's index name |
| `KAFKA_BOOTSTRAP_SERVERS` | `localhost:9092` | |
| `KAFKA_DOCUMENT_TOPIC` | `document-index-events` | Must match document-service's `app.kafka.document-events-topic` |
| `SERVER_PORT` | `8082` | Actuator only — this service has no public REST API, so unlike document-service there's no reason to split an app port from a management port. **Not published to the host in `docker-compose.yml`** — confirmed live that a fixed host port mapping here blocks `docker compose up --scale document-indexer=N` outright (two containers can't bind the same host port), which directly defeats the point of this service being independently scalable. Reachable from other containers on the compose network at `document-indexer:8082`. |

## Running in isolation

Needs Postgres (with document-service's migrations already applied),
Elasticsearch, and Kafka reachable. There's no standalone Testcontainers
launcher for this module (unlike document-service's
`TestDocumentServiceApplication`) — run it against `docker compose up`'s
infra services, or point the env vars above at your own instances.

## Tests

```bash
./mvnw test -Dtest=DocumentIndexConsumerTest   # fast, Mockito-based, no Docker required
```

Covers: an `INDEX` event for a found, non-deleted document indexes it and
sets status `INDEXED`; an `INDEX` event for a missing or already-deleted
document is a no-op; a `DELETE` event calls the Elasticsearch delete.
**Not verified**: this service has never actually run against a live
Kafka/Postgres/Elasticsearch stack in this build environment (no Docker
daemon available) — the consumer logic is unit-tested in isolation, but the
end-to-end flow (real Kafka message → real Postgres row → real ES document)
has not been exercised.
