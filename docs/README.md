# email-service — Base documentation

An internal, centralized **transactional** email microservice for my products: PipeMend (AI-powered ETL pipeline), Colmena (business management SaaS), Chinamo (POS) and Payverica (SINPE payment verification). A single multi-tenant service, with each product's own templates, asynchronous sending, full traceability and isolation guaranteed by the database.

> This folder is the project's **source of truth**. Every contributor (human or AI model) must read it before writing code. If the code and these documents contradict each other, the documents win until a change is approved through an ADR.

> **Status (2026-10-05):** documentation reviewed after the audit (`audit/`). The owner accepted ADR-0008 to 0014, 0016, 0017 and 0018, rejected 0015 (attachments) and answered the open questions. **H0 closed: H1 can start.** The current status is in [12-status-and-local-use.md](12-status-and-local-use.md).

> **(rev. 2026-10-25)** The documentation was translated from Spanish to English; IDs, section numbers and decisions did not change.

## Index

| File | Content |
|---|---|
| [01-vision-and-scope.md](01-vision-and-scope.md) | Problem, consuming products, goals, use cases, MVP in-scope / out-of-scope |
| [02-functional-requirements.md](02-functional-requirements.md) | FR-xx with Given/When/Then acceptance criteria; state machine and event mapping |
| [03-non-functional-requirements.md](03-non-functional-requirements.md) | Measurable NFR-xx (performance, reliability, isolation, privacy, compliance) |
| [04-technology-stack.md](04-technology-stack.md) | Stack, email provider and intake protocol, with justification, comparisons and measurements |
| [05-architecture.md](05-architecture.md) | Components, sending flow, retries, tasks, configuration, deployment |
| [06-data-model.md](06-data-model.md) | Entities, reference DDL with RLS, queue queries, retention |
| [07-internal-api.md](07-internal-api.md) | API specification, error catalogue and integration contract for the products |
| [08-security.md](08-security.md) | Secrets, authentication, anti-abuse, isolation, privacy, Law 8968, incidents |
| [09-roadmap.md](09-roadmap.md) | Phases: MVP and later improvements with measurable triggers |
| [10-ai-agent-guide.md](10-ai-agent-guide.md) | Context and rules for AI models assisting in development |
| [11-bulk-email.md](11-bulk-email.md) | Bulk and marketing email: why not yet, trigger, mandatory separation and requirements |
| [12-status-and-local-use.md](12-status-and-local-use.md) | Project status (milestones, H9, Phase 0, pending decisions) and a guide for using the service from other projects locally |
| [adr/](adr/) | Architecture Decision Records (decisions and their reasons) |
| [audit/](audit/) | Audit report of 2026-10-05, decision summary and open questions |
| [CHANGELOG.md](CHANGELOG.md) | Record of progress and decisions |

## Summary in 13 lines

- **A single service** (API + worker in the same artifact, scalable separately) + its own **PostgreSQL**. No extra microservices or external broker in the MVP.
- **Multi-tenant with a double barrier**: each product is a *tenant* with its API keys (with scopes), templates, limits and sending domain; `tenant_id` in every query **and** Row Level Security in the database.
- **Asynchronous sending**: the API enqueues and answers `202` in milliseconds; a worker sends by priority (`SECURITY` > `TRANSACTIONAL` > `NOTICE`) with retries, backoff and fencing.
- **A transactional provider over its API** (**Resend**; alternatives evaluated: Postmark and Amazon SES) behind `EmailSender`/`WebhookVerifier`.
- **No duplicates in practice**: the client's `Idempotency-Key` + `Idempotency-Key = message.id` towards the provider (24 h); at-least-once semantics.
- **Local development with Mailpit** (SMTP) by changing one environment variable, without changing the architecture.
- **Idempotency required by contract**, with an `Idempotency-Key` derived from the product's domain event.
- **Signed incoming webhooks** (Svix) update the state: delivered, bounced, complained, delayed, failed, suppressed.
- **Global suppression** for hard bounces and complaints (as the provider does) and **per tenant** for manual unsubscribes.
- **Secure templates**: logic-less, context-aware escaping, an HTML linter, links only to the product's hosts.
- **Secrets only in environment variables / a secrets manager**; in the database only API key hashes.
- **No business logic of any product** inside the service, **no bulk email** (ADR-0013) and **no attachments** (signed links instead).
- **Locales:** Spanish (`es-CR`) by default and English available per message (ADR-0018).

## Hierarchy of authority

1. ADRs with status `Accepted` (`adr/`).
2. The numbered documents in this folder.
3. The repository's code and comments.
4. One-off instructions in a chat (if they contradict 1–2, **ask the owner** before acting).

~~Transitional rule (2026-10-05)~~: no longer applies; every ADR from the audit has a decided status.

## Glossary

| Term | Definition |
|---|---|
| **Tenant / source service** | A product that uses the email-service (e.g. `colmena`). The unit of isolation: API keys, templates, limits, manual suppressions and sends always belong to a tenant. |
| **Message (`email_message`)** | An individual send requested by a tenant. It has a state, a priority, attempts and traceability. It is also the queued job. |
| **Template / version** | A template identified by `key` within the tenant, with a category; its published versions are immutable. |
| **Category** | `SECURITY` (verification, recovery, codes), `TRANSACTIONAL` (receipts, invoices, payments) or `NOTICE` (operational notices). It determines the priority and whether tracking is allowed. |
| **Scope** | A permission of an API key: `emails:send`, `emails:read`, `templates:write`, `suppressions:write`. |
| **Provider** | The external service that delivers the email (Resend, Postmark, SES) or the local development SMTP. |
| **Event** | A fact reported by the provider through a webhook (sent, delivered, delivery_delayed, bounced, complained, failed, suppressed, opened, clicked). |
| **Suppression** | A blocked address: **global** after a hard bounce, a complaint or a provider suppression; **per tenant** if it is manual. |
| **Pause** | The state of a tenant whose sending stopped because of an anomaly (volume, bounces, complaints); its messages are held and only an administrator lifts it. |
| **Worker** | The process that claims queued messages from PostgreSQL and sends them. The same artifact as the API, a different profile. |
| **Idempotency-Key** | A key sent by the client so that a retry of the same request does not produce a second email; the service in turn uses the message's `id` as the idempotency key towards the provider. |
| **lock_token** | The fencing token of each claim of a message; it prevents a worker that lost the lock from closing or resending the message. |
| **RLS** | PostgreSQL's Row Level Security: the database filters by `app.tenant_id` even if the code forgets to. |

## Data the email provider receives (data processor)

For each send, Resend (US) receives: the recipient's address and name (`to`, `cc`, `bcc`), the sender and the `reply-to`, the rendered subject and body (which can contain personal data from the variables), the tags (`tags`) and the internal `message_id` as a tag. The provider keeps this data according to its plan (30 days according to its pricing page checked on 2026-10-05; to be confirmed per plan). Each product must cover this transfer in its privacy notice (Law 8968, art. 14).
