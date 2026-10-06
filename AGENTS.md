# AGENTS.md — email-service

Internal, centralized **transactional** email microservice used by my own products
(PipeMend, Colmena, Chinamo, Payverica). Not a SaaS, not a marketing platform.

**Source of truth: `/docs` (Spanish).** Read `docs/10-guia-para-agentes-ia.md` before any change,
and the specific requirement in `docs/02-requisitos-funcionales.md`. If code and `/docs` disagree, `/docs` wins.
ADR-0008…0014, 0016, 0017 and 0018 are Accepted; ADR-0015 (attachments) is Rejected — no attachments in the MVP.

## Hard rules (no change without owner approval + ADR in docs/adr)

- **Stack:** Java 25 (LTS) + Spring Boot 4.1 (virtual threads on) + Spring Data JDBC + Flyway + PostgreSQL 18 + Gradle (Kotlin DSL). No JPA/Hibernate, no Lombok. Spring Boot 3.x APIs do not apply (ADR-0009).
- **Topology:** ONE deployable artifact (`APP_ROLE=api|worker|all|migrate`) + ONE PostgreSQL database. No extra microservices, no object storage (ADR-0001).
- **Isolation:** `tenant_id` in every business query AND Row Level Security (`FORCE`, fail-closed on `app.tenant_id`, set per transaction with `set_config(..., true)`). Composite FKs `(tenant_id, id)`. The `email_system` (BYPASSRLS) DataSource is allowed only in worker, events, auth, admin, abuse and maintenance packages (ADR-0008).
- **Queue:** the `email_message` table, `SELECT … FOR UPDATE SKIP LOCKED` in a SHORT transaction; `attempts++` at claim time; close with `WHERE lock_token = :mine`; order by `priority, next_attempt_at`. No RabbitMQ / Kafka / Redis (ADR-0010).
- **Provider:** HTTP API (Resend) behind `EmailSender` / `WebhookVerifier`, always with `Idempotency-Key = message.id`; retry window < 23 h. SMTP (Mailpit) is **development only**; the app refuses to boot with `APP_ENV=production` + `MAIL_PROVIDER=smtp` (ADR-0016).
- **Secrets:** environment variables / secrets manager only. The database stores only API key prefixes and SHA-256 hashes. Never log secrets (ADR-0017).
- **API keys:** scopes `emails:send`, `emails:read`, `templates:write`, `suppressions:write`; a product's deployed key never has the last two. Optional CIDR allowlist (ADR-0017).
- **Locales:** template versions per locale (`es-CR` default, `en` available); request `locale` falls back to the tenant default (ADR-0018).
- **Templates:** logic-less Handlebars, no partials, whitelisted helpers only, `{{{ }}}` and `{{& }}` forbidden, context-aware escaping (HTML only in the HTML body), HTML linter, URL variables `https` + tenant `allowedLinkHosts`; published versions immutable and pinned per message; no "current time" helper (ADR-0011).
- **Suppression:** hard bounce, complaint and provider suppressions are GLOBAL; manual ones are per tenant; check to/cc/bcc (ADR-0012).
- **Async:** the API accepts and enqueues (202); the worker sends. Never call the provider inside the HTTP request or with row locks held.
- **Idempotency:** `UNIQUE (tenant_id, idempotency_key)` in the database; checked BEFORE rate limiting.
- **Contract:** `/v1`; breaking changes need a new route version + ADR + regenerated `contracts/email-service.openapi.json`.

## Never do this

- SMTP with user/password as the production mechanism.
- Product-specific business logic inside this service (no `if (tenant == "colmena")`).
- Add a broker, cache, object storage or second service "to scale" — target volume is < 100k emails/month.
- Query business data without `tenant_id`, disable RLS, or use the system DataSource outside its allowed packages.
- Re-send a message that already has `provider_message_id`; close a message without checking `lock_token`; call the provider without the idempotency key.
- Mark messages `FAILED` because a tenant is suspended or paused (they stay `QUEUED`).
- Return 5xx from a webhook for an uncorrelatable event (infinite provider retries); verify signatures on re-serialized JSON.
- Log variables, full email addresses, event payloads or secrets.
- Implement roadmap items (web dashboard, outbound webhooks, attachments, multi-provider failover, campaigns / bulk marketing) — out of MVP scope on purpose (ADR-0013).
- Weaken or disable a failing test.

## Code style

- Code, identifiers, logs, commit messages and API messages in English.
- Comments in English, short, and only where the intent is not obvious from the code (explain why, not what). Do not comment every class, method or line. No decorative comment blocks, no restating method names.
- No emojis anywhere: code, comments, logs, commit messages, PR descriptions, docs.
- Prefer `record` over Lombok; explicit data access, no ORM magic.

## Workflow

Reference FR/NFR IDs in commits and PRs (Conventional Commits: `feat(sending): … (FR-08)`).
A test per acceptance criterion; integration tests use Testcontainers (with DB roles + RLS) + a stubbed provider; **no real emails, ever**.
ArchUnit tests guard module and DataSource boundaries.
Update `/docs` in the same PR when documented behaviour changes. If ambiguous: ask. If unattended:
choose the most conservative option and leave `// TODO(owner-decision): …` listed in the PR.

## Commands

- Run everything: `cp .env.example .env && docker compose up --build` (app + PostgreSQL with roles + Mailpit at http://localhost:8025)
- Migrations only: `APP_ROLE=migrate ./gradlew bootRun`
- Tests: `./gradlew test`
- Seed local tenant + API keys + template: `./scripts/seed-local.sh`
- End-to-end smoke test: `./scripts/smoke-test.sh`
