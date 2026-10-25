# ADR-0019 — API keys without a cache (immediate revocation) and an RLS cost criterion per transaction

- **Status:** Accepted (owner, 2026-10-06)
- **Date:** 2026-10-06
- **Author:** agent: Claude (H2)
- **Supersedes:** ADR-0017, only in its "cache ≤ 60 s" point; clarifies the review criterion of ADR-0008
- **Related requirements:** FR-01, FR-03 (AC-03.4), NFR-08, `08-security.md` §2

## Context

1. ADR-0017 kept from ADR-0004 an in-memory cache of validated API keys with a TTL ≤ 60 s, so as not to query the database on every request. Its price is that a revoked key keeps authenticating for up to 60 s, exactly when cutting it matters most: after a leak (`08` §3).
2. In H2, authentication was implemented without a cache: one lookup by `key_prefix` (a `UNIQUE` index) per request, through `email_system`. At < 100,000 emails/month the cost is negligible.
3. ADR-0008 sets as a review criterion "tenant query latency rising more than 20 % because of RLS (measured in H2)", without saying over what it is measured. Measured in H2 with `./gradlew benchmarkRls`:
   - PostgreSQL 18 in local Docker, 100,000 messages across 20 tenants, 5,000 transactions per case, two runs;
   - p50 per transaction, in µs.

   | Query | `email_app` + `set_config` + RLS | `email_system` + `set_config` | `email_system` query only |
   |---|---|---|---|
   | The tenant's last 20 | 144–158 | 133–137 | 91–92 |
   | By tenant + id | 127–128 | 119–122 | 82–91 |

   - The plan still uses `ix_msg_tenant_created`; the policy remains a single `One-Time Filter`.
   - The `set_config` round trip costs ~30–45 µs and evaluating the policy, +5–15 % in p50.
   - In total, +40–65 µs per transaction: more than 20 % over an isolated query, but 3–5 % of the acceptance path (1.38 ms p50, `04` §2).

## Decision

1. **No API key cache.** Every `/v1` request looks the key up by its prefix. Revocation takes effect on the next request (revised AC-03.4).
2. **The RLS cost criterion of ADR-0008 is measured over the whole transaction of the request**, not over an isolated query. With the numbers above (3–5 %), the criterion is **not** triggered. The `set_config` round trip is not optimized.
3. The measurement is repeated with `./gradlew benchmarkRls` (it is not part of `./gradlew test`). The report is written to `build/reports/rls-benchmark.txt`.

## Alternatives considered

| Alternative | For | Against | Why discarded |
|---|---|---|---|
| A cache with TTL ≤ 60 s (ADR-0017) | One read less per request | Up to 60 s of a revoked key still valid; invalidation across instances | The saving is negligible at this volume and immediate revocation is worth more |
| A cache with active invalidation | Immediate revocation and no read | Needs a channel between instances (LISTEN/NOTIFY or similar) | Complexity with no measurable benefit |
| Measuring the RLS criterion on the isolated query | More sensitive | It triggers because of the `set_config` round trip, which is a network cost, not an RLS cost | Does not represent what a request pays |
| Sending `set_config` in the same trip as the first query | −30–45 µs per transaction | More code in data access; JDBC does not allow it cleanly with parameters | Not worth it for 3–5 % |

## Consequences

- **Positive:** revocation is immediate; there is no in-memory authentication state to invalidate across instances; the RLS criterion is clear and repeatable.
- **Negative / accepted trade-offs:** one more indexed read per `/v1` request (≈ 0.1 ms locally).

## Review criterion

- Authentication exceeds 1 ms p95 in production, or the database saturates because of key lookups: consider a cache with active invalidation.
- `./gradlew benchmarkRls`, measured over the request's whole transaction, shows more than 20 % of cost attributable to RLS.
