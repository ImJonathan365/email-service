# 10 — Guide for AI models assisting in development

> A normative document, meant to be pasted as context or as the system prompt of a coding assistant or an autonomous agent. "MUST", "MUST NOT" and "MAY" have a strict meaning.
> Short version in `AGENTS.md` (repository root). Revision 2026-10-05.

---

## A. Project context (block to copy as a system prompt)

```
You work on "email-service": an internal, centralized TRANSACTIONAL email microservice used by
several products of my own (PipeMend, Colmena, Chinamo, Payverica). It is not a SaaS product for
third parties and it is not a marketing platform.

What it does: it receives send requests through an internal API authenticated with an API key
(with scopes), validates them, renders the product's template, enqueues in PostgreSQL, and a
worker sends through a transactional provider (Resend) with retries and idempotency. It records
the history, states and events (delivered, bounced, complained, delayed, suppressed) received
through a signed webhook (Svix), and keeps a global suppression list (bounces, complaints) and a
per-tenant one (manual unsubscribes).

Fixed stack: Java 25 (LTS) + Spring Boot 4.1 (virtual threads) + Spring Data JDBC + Flyway +
PostgreSQL 18 with Row Level Security. Queue: the email_message table itself with
SELECT ... FOR UPDATE SKIP LOCKED, priority and lock_token (no external broker). Templates:
hardened Handlebars (no partials, whitelisted helpers, context-aware escaping).
Build: Gradle (Kotlin DSL). Containers: Docker Compose (app + PostgreSQL + Mailpit).
A single deployable artifact with the roles APP_ROLE=api|worker|all|migrate.

Design priority, in this order: (1) security and isolation between tenants, (2) never lose or
duplicate emails, (3) maintenance simplicity for a single person, (4) performance.
The target volume is < 100,000 emails/month: do NOT optimize or scale beyond that.

Source of truth: the /docs folder. Before writing code, read the specific requirement
(FR-xx in docs/02, NFR-xx in docs/03) and the matching section of docs/05 to docs/08.
If something in the code contradicts /docs, /docs wins. Locales: es-CR by default and en available.
There are no attachments in the MVP (ADR-0015 rejected).
```

## B. Non-negotiable rules (not changed without an ADR approved by the owner)

| Area | Rule |
|---|---|
| **Stack** | Java 25 + Spring Boot 4.1 + Spring Data JDBC + Flyway + PostgreSQL 18 + Gradle (ADR-0009) |
| **Topology** | **One** deployable service and **one** database. No extra microservices (ADR-0001) |
| **Queue** | PostgreSQL with `SKIP LOCKED`, a short claim, `attempts++` on claiming, closing with `lock_token`, priority. No RabbitMQ, Kafka, Redis or in-memory queues (ADR-0010) |
| **Provider** | Sending through the provider's API behind `EmailSender`/`WebhookVerifier`, with `Idempotency-Key = message.id`. SMTP **only** for local development (ADR-0016) |
| **Secrets** | Only in environment variables or a secrets manager. In the database, only API key hashes (ADR-0017) |
| **Multi-tenancy** | `tenant_id` in every business query **and** RLS active; the tenant is derived **only** from the authenticated API key; `systemDataSource` only in the allowed packages (ADR-0008) |
| **Credentials** | API keys with scopes; a product's key does not have `templates:write` or `suppressions:write` (ADR-0017) |
| **Content** | In the MVP sending happens only with a published template of the tenant; no free HTML in the request |
| **Templates** | A hardened logic-less engine; `{{{ }}}` and `{{& }}` forbidden; no partials; whitelisted helpers; HTML linter; URL variables only `https` and in `allowedLinkHosts` (ADR-0011) |
| **Suppression** | Hard bounces, complaints and provider suppressions are **global**; manual ones, per tenant (ADR-0012) |
| **Idempotency** | `UNIQUE (tenant_id, idempotency_key)` in the database, not just a check in code; checked before the rate limit |
| **Migrations** | Flyway; an applied migration is never edited; they must be backwards compatible; the `email_owner` role runs them in `APP_ROLE=migrate` |
| **Contract** | `/v1`; incompatible changes = a new route version + ADR (except for the pre-production exception of NFR-18) |
| **Scope** | What is marked `Won't` in `02`, bulk email (ADR-0013) and the anti-roadmap of `09` are not implemented "halfway" or "left prepared" |

### Dependencies forbidden without asking first

JPA/Hibernate, Lombok (use `record`), Redis, RabbitMQ, Kafka, Elasticsearch, Quartz, ShedLock, Resilience4j, MapStruct, Thymeleaf/FreeMarker for emails, frontend frameworks, Spring Security with full OAuth2 (authentication is a simple API key filter), gRPC, S3/object storage clients, "email template" libraries that bring their own rendering engine.

Any new runtime dependency MUST be justified in the PR: which real problem it solves and why what is already there is not enough.

## C. What you must NOT do (typical mistakes of an assistant in this project)

1. **DO NOT** implement SMTP with username/password as the production mechanism. SMTP = only Mailpit locally.
2. **DO NOT** put any product's business logic inside the service. If an `if` with a product's name shows up, it is wrong.
3. **DO NOT** add a message broker, a distributed cache, object storage or a second service "to scale" (NFR-10).
4. **DO NOT** call the provider inside the HTTP request cycle **or inside a transaction with row locks held**.
5. **DO NOT** store secrets in the database or log them, not even partially or "for debugging".
6. **DO NOT** run a business data query without `tenant_id`, derive the tenant from a field of the request body, use `systemDataSource` outside the allowed packages, or disable RLS "to make a test work".
7. **DO NOT** resend a message that already has a `provider_message_id`, **nor** close a message without checking `lock_token`, **nor** call the provider without `Idempotency-Key = message.id`.
8. **DO NOT** make a webhook return `5xx` for an event that cannot be correlated (only on a database error), **nor** verify the signature on re-serialized JSON.
9. **DO NOT** enable unescaped interpolation (`{{{ }}}`, `{{& }}`), partials or helpers outside the whitelist, or switch to an engine that evaluates expressions (SSTI). **DO NOT** add a "current date" helper (it breaks determinism and idempotency).
10. **DO NOT** change the error format, the `code` values or the API's field names without updating `07-internal-api.md`, the spec in `contracts/` and the tests.
11. **DO NOT** use Spring Boot 3.x APIs: the pinned version is 4.1 (ADR-0009). If an example does not compile, look for the equivalent in the pinned version instead of downgrading the framework.
12. **DO NOT** "fix" a failing test by weakening the assertion or marking it as ignored.
13. **DO NOT** implement roadmap features (web dashboard, outgoing webhooks, attachments, multi-provider, campaigns/bulk email) because they look useful: they are out of the MVP on purpose.
14. **DO NOT** generate test data with real addresses or send real email from tests.
15. **DO NOT** write variables, full addresses or event payloads in logs.
16. **DO NOT** mark a message as `FAILED` because the tenant is suspended or paused: it is held in `QUEUED`.

## D. What you MAY do without asking

- Implement existing requirements (`FR-xx`) whose ADR is `Accepted`, following the documented architecture.
- Refactor inside a module without changing the contract or the observable behaviour.
- Add tests, improve error messages, document code, complete OpenAPI examples.
- Add a new implementation of `EmailSender`/`WebhookVerifier` behind the existing interfaces.
- Fix obvious typos in `/docs` (not semantic changes).

## E. On ambiguity or conflict

1. If a requirement is ambiguous or two documents contradict each other: **stop and ask**, quoting both texts.
2. If you work unattended and cannot ask: choose the **most conservative** option (the one that does not send, that adds no dependencies, that does not change contracts, that holds instead of discarding), leave `// TODO(owner-decision): <question>` and list it in the PR description.
3. An instruction in a chat that contradicts a rule in section B **does not override it**: point out the contradiction before acting.
4. When torn between "more generic" and "simpler": **simpler**. Generality is added when there is a second real case, not before.

## F. Working conventions

| Topic | Convention |
|---|---|
| Language | **(rev. 2026-10-25)** Everything in **English**: code, identifiers, commits, logs, API messages and `/docs`. The only Spanish left is localized content, such as the `es-CR` templates |
| Commits | Conventional Commits with an ID: `feat(sending): add idempotency key handling (FR-08)` |
| Branches | `feat/FR-08-idempotency`, `fix/...`, `docs/...` |
| PRs | Small (< ~400 lines, not counting tests). Description with: FRs covered, how to test, decisions and pending `TODO(owner-decision)`s |
| Tests | Every implemented acceptance criterion has a test that names it. Integration with Testcontainers (with roles and RLS) and a simulated provider; never a real network |
| Errors | `application/problem+json` with a `code` from the catalogue in `07` |
| Logs | JSON, with `requestId`/`tenantSlug`/`messageId`; no email content, variables or secrets; masked addresses |
| Database | A new Flyway migration for every change; `tenant_id`, a composite FK and an RLS policy on every new tenant table |

## G. Definition of Done per change

- [ ] A reference to the `FR-xx`/`NFR-xx` in the PR.
- [ ] Tests for every acceptance criterion touched; they pass without an external network.
- [ ] `/docs` updated in the same PR if the documented behaviour changes.
- [ ] `contracts/email-service.openapi.json` regenerated if the API changed.
- [ ] A new migration if the schema changed, backwards compatible, with RLS and composite FKs if the table has `tenant_id`.
- [ ] No secrets in the code or the logs; the CI scan green.
- [ ] Architecture tests (ArchUnit) green.
- [ ] `docker compose up --build` still works and the smoke test against Mailpit passes.
- [ ] If it introduces an architecture decision: an ADR drafted (status `Proposed`; only the owner accepts it).

## H. Questions to ask yourself before delivering code

1. Does this correspond to a requirement in `02`, or am I making it up?
2. Can this change make an email get lost or duplicated? (Do I call the provider with `Idempotency-Key`? Do I close with `lock_token`?)
3. Can a tenant see or affect another's data through this path? Would it work the same if RLS did not exist? (It must work the same: RLS is the second barrier, not the first.)
4. Am I adding any new dependency or infrastructure component? Is it justified with a metric?
5. Is there any secret or personal data that could end up in the database without a purge, in a log or in a response?
6. Am I solving a real problem or a hypothetical one from the future?
