// k6 load test targeting the assignment's stated SLA: p95 < 500ms, 1000+ concurrent
// searches/sec. Run with Docker Compose already up (`docker compose up -d`) and the
// index seeded (`scripts/seed-demo-data.sh`).
//
//   k6 run --vus 200 --duration 60s scripts/load-test.js
//
// Auth: logs in once in setup() as the seeded user@acme demo account (see
// document-service/src/main/resources/db/migration/V4__add_users.sql) and reuses
// that JWT across every VU/iteration - tenantId/role come from the token now, not
// from X-Tenant-Id/X-User-Role headers. USER role is sufficient since search is
// read-only.
//
// This script is provided so the SLA claims in docs/SUBMISSION.md can be verified on a
// real machine; no results are included in the submission because this environment had
// no Docker daemon to run the stack against - see scripts/README.md.
import http from "k6/http";
import { check, sleep } from "k6";

const BASE_URL = __ENV.BASE_URL || "http://localhost:8080";
const LOGIN_USERNAME = __ENV.LOGIN_USERNAME || "user@acme";
const PASSWORD = __ENV.PASSWORD || "password123";
const QUERIES = ["revenue", "invoice", "contract", "roadmap", "incident", "security review"];

export const options = {
  thresholds: {
    http_req_duration: ["p(95)<500"],
    http_req_failed: ["rate<0.01"],
  },
};

export function setup() {
  const res = http.post(`${BASE_URL}/auth/login`, JSON.stringify({ username: LOGIN_USERNAME, password: PASSWORD }), {
    headers: { "Content-Type": "application/json" },
  });
  if (res.status !== 200) {
    throw new Error(`login failed (status ${res.status}) - is the stack up? (docker compose up)`);
  }
  return { token: JSON.parse(res.body).token };
}

export default function (data) {
  const q = QUERIES[Math.floor(Math.random() * QUERIES.length)];
  const res = http.get(`${BASE_URL}/search?q=${encodeURIComponent(q)}&size=10`, {
    headers: { Authorization: `Bearer ${data.token}` },
  });
  check(res, {
    "status is 200": (r) => r.status === 200,
    "has hits array": (r) => JSON.parse(r.body).hits !== undefined,
  });
  sleep(0.1);
}
