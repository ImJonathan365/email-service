# 01 — Vision, goals and scope

> Revision 2026-10-05: real products, adjusted MVP scope (key scopes, anomalies, RLS, `sendAt`, locales); the owner's answers applied, goals corrected.

## 1. Problem

Every product I launch needs to send transactional email: account verification, password recovery, receipts, payment confirmations, alerts and process notifications. If every product implements its own sending:

- the same integration, retry and error-handling code is duplicated in each repository;
- the provider's credentials end up copied in several places (more leak surface, rotation impossible in practice);
- there is no unified history: when a user says "the email never arrived", you have to investigate product by product;
- bounces and complaints are not shared: a product keeps sending to an address that already bounced in another one (and the provider, which does share its suppression list across domains, skips them without the product noticing);
- every product reinvents its HTML templates and their preview.

## 2. Solution

A **centralized transactional email microservice**, with an internal API consumed by my products. The service takes care of: authenticating the source product, validating the request, rendering the product's template, enqueuing, sending through a transactional provider, retrying, recording the state and exposing the history.

```
PipeMend  ─┐
Colmena   ─┤
Chinamo   ─┼──▶  email-service  ──▶  transactional provider (Resend)  ──▶  user's inbox
Payverica ─┘         │    ▲                    │
                     ▼    └────── webhooks ────┘
                PostgreSQL (history, templates, suppressions; RLS per tenant)
```

### 2.1 Consuming products (rev. 2026-10)

Sending profile based on each product's description and the owner's answers of 2026-10-05 (all in Spring Boot; frontends in TypeScript; users in Costa Rica only); the detail of each email is to be confirmed against their documents (see `docs/audit/2026-10-05-audit-report.md`, "What to check in the consuming products").

| Tenant | Product | Typical emails | Categories | Particular needs |
|---|---|---|---|---|
| `pipemend` | AI-powered ETL pipeline | Pipeline failure alerts, summaries, notice that the monthly export is ready | `TRANSACTIONAL`, `NOTICE`, `SECURITY` (access) | Exports as a **signed link**, not an attachment |
| `colmena` | Business management SaaS | Invitations, verification, recovery, reminders (including those about products or debts of each business's customers) | `SECURITY`, `TRANSACTIONAL`, `NOTICE` | `sendAt` (reminders); `fromName` per business; **processes data of its customers' customers** (data processor, NFR-20); bulk notices excluded (ADR-0013) |
| `chinamo` | POS | Cash-register closings, logins, operation notifications | `TRANSACTIONAL`, `SECURITY` | No attachments (owner: it does not send receipts by email) |
| `payverica` | SINPE payment verification | Confirmation or rejection of a verified payment, codes, alerts | `SECURITY`, `TRANSACTIONAL` | Sensitive data in variables (`x-sensitive`); low latency (NFR-19) |

## 3. Goals

| ID | Goal | Success metric |
|---|---|---|
| OBJ-1 | Integrate a new product in under 30 minutes | Create tenant + API key + template and send the first email following only `07-internal-api.md` |
| OBJ-2 | Never block the sending product | p95 of `POST /v1/emails` ≤ 150 ms, regardless of the provider's state |
| OBJ-3 | Zero duplicate emails from retries (rev. 2026-10) | 100 % of requests with a repeated `Idempotency-Key` return the original message, and 0 duplicates in the provider's "lost response" test (NFR-04) |
| OBJ-4 | Zero loss of accepted emails (rev. 2026-10) | Every accepted message (`202`) reaches a terminal state (`BOUNCED`, `COMPLAINED`, `FAILED`, `CANCELED`), `DELIVERED`, or a time-based final `SENT` (72 h without events); nothing stays eligible in `QUEUED` or in `SENDING` without an alert |
| OBJ-5 | Full traceability | For any send: who requested it, with which template and version, what the provider answered and which events arrived |
| OBJ-6 | Isolation between products (rev. 2026-10) | A tenant can never read or affect another's data, templates or quotas: verified by tests **and** guaranteed by RLS and composite FKs in the database |
| OBJ-7 | Secrets outside the database and the code | Only API key hashes in the database; provider credentials only in the environment or a secrets manager |
| OBJ-8 | Local development without a provider account | `docker compose up` + Mailpit: email is sent and inspected without leaving the machine |
| OBJ-9 | Fast critical email (new) | Acceptance → `SENT` p95 ≤ 5 s for `SECURITY` and `TRANSACTIONAL` (NFR-19) |
| OBJ-10 | Contain the abuse of a leaked key (new) | A product key cannot publish templates or link to foreign hosts; an anomalous spike pauses the tenant in ≤ 5 min (FR-33, FR-34) |

## 4. Main use cases

| ID | Use case | Actor | Summary |
|---|---|---|---|
| UC-01 | Send a transactional email with a template | Source product | `POST /v1/emails` with `templateKey` + variables → `202` + `id` |
| UC-02 | Check the state of a send | Source product / me | `GET /v1/emails/{id}` with the event history |
| UC-03 | List and filter sends | Source product / me | By state, template, recipient, date range |
| UC-04 | Create and version a template | Me, with a `templates:write` key | Create a template (with category), create a draft version, preview, publish |
| UC-05 | Preview a template with sample data | Me | Render without sending, to see the resulting HTML |
| UC-06 | Receive provider events | Provider (webhook) | Signature verified → updates the state and stores the event |
| UC-07 | Manage suppressions | System / me | A hard bounce or complaint blocks the address globally; manual unsubscribes per tenant |
| UC-08 | Onboard a new product (tenant) and its API key | Me (global admin) | Create the tenant, issue a key with scopes (shown only once) |
| UC-09 | Rotate or revoke an API key | Me (global admin) | Issue a new key, overlap period, revoke the previous one |
| UC-10 | Automatic retry when the provider fails | System | Exponential backoff, error classification, attempt cap and a window < 23 h |
| UC-11 | Operate with the provider down | System | Messages pile up in the queue and are sent when it recovers; the API keeps accepting |
| UC-12 | Purge old data | System | Retention configurable per tenant; variables purged earlier |
| UC-13 | ~~Send a receipt with attachments~~ | — | DEPRECATED (owner 2026-10-05: there is no use case; FR-30 Won't) |
| UC-17 | Schedule a reminder (new) | Colmena | `POST /v1/emails` with `sendAt` (≤ 30 days) |
| UC-18 | Send in English (new) | Any product | `POST /v1/emails` with `locale = "en"`; without a translation it falls back to Spanish |
| UC-14 | Contain a leaked key (new) | System / me | Automatic pause on an anomaly; revoke the key; resume |
| UC-15 | Handle a data subject's erasure request (new) | Me (admin) | `POST /admin/v1/data-subjects/erase` within ≤ 5 business days |
| UC-16 | Send an operational notice to several users (new) | Source product | `POST /v1/emails/batch` (≤ 100 per call, `NOTICE` category, within the quota) |

## 5. MVP scope (in scope)

1. An internal REST API authenticated by API key, with optional **scopes** and CIDRs (one or several per tenant).
2. Transactional sending **only through a tenant template** (HTML + text), with variables validated by a schema.
3. Versioned templates with a category (`SECURITY`/`TRANSACTIONAL`/`NOTICE`): immutable published versions, a security linter and context-aware escaping.
4. Template preview/render without sending.
5. A persistent queue in PostgreSQL with priority + a worker with retries, backoff, fencing and idempotency towards the provider.
6. Idempotency through `Idempotency-Key`.
7. Integration with a transactional provider over its API (Resend) behind `EmailSender`/`WebhookVerifier`.
8. A local SMTP mode (Mailpit) selectable through an environment variable.
9. Incoming provider webhooks with signature verification and full event mapping (`02` §2.1).
10. A global suppression list (hard bounces, complaints, provider mirror) and a per-tenant one (manual unsubscribes), with automatic blocking.
11. History and querying of sends and events.
12. Per-tenant rate limits (per minute and per day), size quotas and **anomaly detection with automatic pause**.
13. An administration API (tenants, API keys, global suppressions, data-subject erasure) with a separate admin credential, reachable only from the private network.
14. Auditing of administrative actions and template changes.
15. Observability: health checks, metrics, structured logs with correlation, and minimal alerts.
16. Configurable retention and purging (Must).
17. Docker Compose for development (app + PostgreSQL + Mailpit) and deployment with the same image.
18. **(rev. 2026-10)** Row Level Security in PostgreSQL and composite FKs per tenant (ADR-0008).
19. ~~Bounded attachments~~ **(rev. 2026-10-05, owner)**: out of the MVP (FR-30 Won't).
21. **(rev. 2026-10)** Scheduled sending with `sendAt` (Should, FR-31).
22. **(rev. 2026-10)** Templates per locale: `es-CR` by default and `en` available (Should, FR-37, ADR-0018).
20. **(rev. 2026-10)** Batch endpoint (Should, ADR-0014).

## 6. Out of MVP scope

| Excluded | Reason / destination |
|---|---|
| Web dashboard / metrics UI | Phase 1. In the MVP: endpoints + Swagger + SQL |
| Sending arbitrary HTML content in the request (no template) | Enforces good practice and reduces the abuse surface. Phase 2 with a flag per tenant |
| Attachments | **(rev. 2026-10-05, owner)** No product needs them: Won't (FR-30, ADR-0015 Rejected). Exports are sent as signed links |
| ~~Scheduled sends (`sendAt`)~~ | **(rev. 2026-10)** They enter the MVP as Should (FR-31) |
| Campaigns, newsletters, lists and marketing `List-Unsubscribe` | Not for now (ADR-0013); design and trigger in `11-bulk-email.md` |
| Several simultaneous providers or automatic failover | Phase 3. The MVP leaves the interface ready, but configures one active provider |
| Outgoing webhooks towards the source products | Won't (FR-32). In the MVP the products query through the API |
| A published client SDK (Java/TS) | Phase 1; in the MVP the OpenAPI specification is enough |
| Templates with complex logic, shared partials, MJML, more than 2 languages | Phase 2 (es-CR and en enter the MVP, ADR-0018) |
| ~~Row Level Security in PostgreSQL~~ | **(rev. 2026-10)** Enters the MVP (ADR-0008) |
| SSO/OAuth2 for administration | Adds nothing with a single administrator |
| Receiving inbound email (inbound parsing) | Outside the purpose |
| Kubernetes, service mesh, Kafka, Redis, object storage | Unnecessary for the target volume (NFR-10) |
| gRPC, queue-based intake, inbound SMTP | Measured: they add nothing (ADR-0014) |

## 7. Assumptions and constraints

- **(rev. 2026-10)** Expected first-year volume: **< 100,000 emails/month** across all products (≈ 0.04 msg/s on average); peaks of up to ~20 msg/s for seconds (e.g. a batch of notices), not sustained.
- It is maintained by **one person**: operational simplicity weighs more than theoretical scalability.
- The consuming products are trusted (they are mine) and run on a network where they can reach the service; even so they are authenticated and limited as if they were not, because a key can leak.
- The service never knows a product's business logic: it receives `templateKey` + already resolved variables.
- One active provider per environment; **(rev. 2026-10)** separate provider accounts for staging and production.
- **(rev. 2026-10)** Default email language: Costa Rican Spanish (`es-CR`), time zone `America/Costa_Rica`; English (`en`) is available per message if the template has a translation (ADR-0018).
- **(rev. 2026-10, owner)** Users and services in Costa Rica only: GDPR and CAN-SPAM do not apply as long as that does not change.
- **(rev. 2026-10)** Reference regulation: Law 8968 (Costa Rica); details in NFR-20.

## 8. Design principles (non-negotiable)

1. **Simple before generic.** No abstractions for hypothetical needs; the only indirections are `EmailSender`/`WebhookVerifier` (provider) and the template engine.
2. **One deployable artifact.** The API and the worker are the same binary with a different profile.
3. **The database is the queue.** One component less to install, back up and monitor (ADR-0010).
4. **Accept fast, deliver with retries.** The client never waits for the provider.
5. **Secrets do not live in the database or the code.**
6. **Multi-tenant from the first line, with a double barrier**: every query carries `tenant_id`, and RLS enforces it in the database (ADR-0008).
7. **No foreign business logic.** If something is specific to a product, it belongs in the product.
8. **(rev. 2026-10)** **Sending reputation is a shared resource**: nothing that can generate mass complaints shares a provider account with critical email (ADR-0013).
