# ADR-0007 — Multi-tenancy by a `tenant_id` column in a shared database

- **Status:** Superseded by ADR-0008 (2026-10-05)
- **Date:** 2026-09-20
- **Related requirements:** NFR-08, FR-01, `08-security.md` §5

## Context
Several products of my own use the service at the same time, and their data must be isolated. The classic options are: a database per tenant, a schema per tenant, or a discriminator column.

## Decision
A shared database with a **`tenant_id` column** in every business table.

- The tenant is derived **exclusively** from the authenticated API key; a `tenantId` in the body or the URL is ignored or rejected.
- Every repository requires `tenantId` in its signature; there are no "global" queries outside administration and maintenance.
- Identifiers are UUIDs; accessing another tenant's resource returns `404`, not `403`.
- A mandatory isolation test for every `/v1/**` endpoint.
- Row Level Security in PostgreSQL is left as a second barrier for phase 2.

## Alternatives considered
| Alternative | For | Against | Why discarded |
|---|---|---|---|
| A database per tenant | Strong isolation | N migrations, N pools, N backups; adding a product becomes an operation | Out of proportion for products of my own |
| A schema per tenant | Intermediate isolation | Multiplied migrations; awkward aggregate queries | Discarded |
| RLS from the MVP | A barrier at the engine level | Adds session/role complexity from day 1 | Postponed to phase 2, with the tests as a safety net meanwhile |

## Consequences
- Positive: one migration, one pool, one backup; onboarding a product = one row; trivial aggregate metrics.
- Negative: isolation depends on code discipline (mitigated by repositories that require `tenantId`, tests per endpoint and, later, RLS).

## Review criterion
The service being used beyond products of my own (external clients, compliance or data-residency requirements) or a real cross-tenant leak incident.

---
> **Status note (2026-10-05):** only the *Status* line of this ADR was changed; its accepted text is not edited. The owner accepted ADR-0008, which supersedes it, on 2026-10-05.
