#!/usr/bin/env bash
# Seeds a handful of demo documents for the "acme" tenant so /search has something
# to return immediately after `docker compose up`. Safe to re-run.
#
# Auth: logs in as the seeded admin@acme demo account (see
# document-service/src/main/resources/db/migration/V4__add_users.sql) and uses
# the returned JWT for every request - tenantId/role come from the token now,
# not from X-Tenant-Id/X-User-Role headers.
set -euo pipefail

BASE_URL="${BASE_URL:-http://localhost:8080}"
LOGIN_USERNAME="${LOGIN_USERNAME:-admin@acme}"
PASSWORD="${PASSWORD:-password123}"

TOKEN=$(curl -s -X POST "$BASE_URL/auth/login" \
  -H "Content-Type: application/json" \
  -d "{\"username\":\"$LOGIN_USERNAME\",\"password\":\"$PASSWORD\"}" \
  | grep -o '"token":"[^"]*"' | cut -d'"' -f4)

if [ -z "$TOKEN" ]; then
  echo "Login failed for $LOGIN_USERNAME - is the stack up? (docker compose up)" >&2
  exit 1
fi

docs=(
  '{"title":"Q3 Board Deck","content":"Revenue grew 42 percent year over year, driven by search platform adoption across enterprise accounts.","tags":["finance","board","q3"]}'
  '{"title":"Incident Postmortem: Search Latency Spike","content":"A misconfigured shard allocation caused p95 search latency to exceed 3 seconds for 40 minutes.","tags":["incident","engineering"]}'
  '{"title":"Vendor Security Review - Acme Cloud Storage","content":"Annual security review of the cloud storage vendor covering encryption at rest and access controls.","tags":["security","compliance"]}'
  '{"title":"Product Roadmap H2","content":"Fuzzy search, faceted navigation, and highlighting are the top requested search features for H2.","tags":["product","roadmap"]}'
  '{"title":"Customer Contract Renewal - Globex","content":"Contract renewal terms for the Globex enterprise account, including a 15 percent volume discount.","tags":["sales","contract"]}'
)

for doc in "${docs[@]}"; do
  curl -s -o /dev/null -w "%{http_code} " -X POST "$BASE_URL/documents" \
    -H "Authorization: Bearer $TOKEN" \
    -H "Content-Type: application/json" \
    -d "$doc"
done
echo
echo "Seeded ${#docs[@]} documents for tenant '$LOGIN_USERNAME' as ADMIN. Indexing is async - wait ~2s before searching."
