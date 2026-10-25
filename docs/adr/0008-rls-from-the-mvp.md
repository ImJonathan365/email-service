# ADR-0008 — Row Level Security from the MVP and composite foreign keys per tenant

- **Status:** Accepted (owner, 2026-10-05); the cost review criterion is clarified by ADR-0019 (measured over the whole transaction)
- **Date:** 2026-10-05
- **Author:** agent: Claude (documentation audit)
- **Supersedes:** ADR-0007
- **Related requirements:** NFR-08, FR-01, FR-29, `08-security.md` §5, `06-data-model.md` §1 and §5

## Context

ADR-0007 chose the `tenant_id` column in a shared database (a decision that stands) and postponed RLS to phase 2 with this argument: "adds session/role complexity from day 1". The audit of 2026-10-05 found that:

1. Your other projects require RLS from the MVP. This service stores personal data (recipients, variables with activation and recovery links, events) of **four** different products, including a payments one (Payverica). It has more reasons than the others for the second barrier, not fewer.
2. Without RLS, isolation depends on **every** query carrying `tenant_id`. A new repository, a listing query written in a hurry or an AI assistant that "simplifies" a query is enough to leak data between products, and the per-endpoint test only catches it if it covers that exact path.
3. The database itself does not guarantee cross-tenant invariants: `email_message.template_version_id` references `template_version(id)` without `tenant_id`, and `template_version.tenant_id` is "denormalized" with no constraint tying it to `template.tenant_id`.
4. The real cost of RLS is small and well known:
   - a `set_config('app.tenant_id', $1, true)` at the start of each transaction (the third argument, `true`, scopes it to the transaction, so a connection pool cannot leak it to another request);
   - one policy per table;
   - separating the role that migrates from the role that runs.

## Decision

1. **RLS active from migration V2**, with `ENABLE` + `FORCE ROW LEVEL SECURITY` on every table with `tenant_id`: `api_key`, `template`, `template_version`, `email_message`, `email_attachment`, `email_event`, `suppression`, `rate_limit_counter` and `audit_log`.
2. **A fail-closed policy:** `tenant_id = current_setting('app.tenant_id', true)::uuid`. If the variable is not set, `current_setting(..., true)` returns `NULL`, the comparison is not true and no row is seen or written.
3. **Three database roles:**
   - `email_owner`: owns the schema; used only by the migration step `APP_ROLE=migrate`.
   - `email_app`: tenant requests; subject to RLS; no DDL.
   - `email_system`: `BYPASSRLS`; no DDL; used by the worker (claiming from the queue across tenants), the sweeps, the purge, webhook correlation (which does not know the tenant until it finds the message), the API key lookup by prefix, administration and global suppressions.
4. **Two `DataSource`s:** `tenantDataSource` (`email_app`) and `systemDataSource` (`email_system`). An architecture test (ArchUnit) forbids using `systemDataSource` outside `sending.worker`, `events`, `tenancy.auth`, `tenancy.admin`, `abuse` and `maintenance`.
5. **Composite foreign keys** `(tenant_id, id)` on `template`, `template_version`, `email_message` and `email_attachment`, so the database rejects cross-tenant references.
6. **Mandatory tests** (NFR-08): isolation per endpoint; 0 rows without `app.tenant_id`; FK violation with another tenant's template version.

## Alternatives considered

| Alternative | For | Against | Why discarded |
|---|---|---|---|
| Keep ADR-0007 (RLS in phase 2) | Fewer pieces on day 1 | A single code mistake exposes the data of several products; inconsistent with the standard of your other projects | The complexity argument does not outweigh the risk for the personal data of 4 products |
| RLS with `SET ROLE` per tenant (one DB role per tenant) | Isolation by privileges | N roles to create and migrate; onboarding a tenant = a DB operation | Out of proportion; the session variable is enough |
| A schema per tenant | Intermediate isolation | Multiplied migrations; the cross-tenant queue gets complicated | Same as in ADR-0007 |
| Composite FKs only, without RLS | Guaranteed write invariants | Does not protect reads | Insufficient alone; adopted together with RLS |

## Consequences

- **Positive:**
  - a code mistake in a tenant path returns 0 rows instead of someone else's data;
  - the database prevents cross references;
  - the model is ready if there are ever third parties;
  - consistent with your other projects.
- **Negative / accepted trade-offs:**
  - two `DataSource`s and two pools (the system one is small, 2–4 connections);
  - the `email_system` role needs attention: confined by an architecture test and PR review;
  - migrations become a deployment step of their own (`APP_ROLE=migrate`);
  - creating `email_system` with `BYPASSRLS` requires a privileged role in the managed PostgreSQL (to be confirmed with the hosting provider; the alternative is an explicit policy `TO email_system USING (true)`);
  - the performance cost of RLS with an equality predicate on an indexed column: negligible at this volume (to be measured in H2).

## Review criterion

An incident where `email_system` is used outside its scope; the chosen managed PostgreSQL allowing neither `BYPASSRLS` nor per-role policies; or tenant query latency rising more than 20 % because of RLS (measured in H2).
