# 03 — Non-functional requirements

> Revision 2026-10-05. New or changed entries carry **(rev. 2026-10)**. IDs are not renumbered.

Every NFR is verifiable. **(rev. 2026-10)** Reference environment for the figures: a 1 vCPU / 1 GB RAM app instance and a 1 vCPU / 1 GB managed PostgreSQL in the same region. The previous environment (a 2 vCPU / 4 GB database) was incompatible with NFR-16: it costs 60.90 USD/month on DigitalOcean, against 15.15 USD for the 1 vCPU / 1 GB one ([prices checked on 2026-10-05](https://www.digitalocean.com/pricing/managed-databases)).

| ID | Category | Requirement | Priority |
|---|---|---|---|
| NFR-01 | Performance | Acceptance latency | Must |
| NFR-02 | Performance | Sending throughput | Should |
| NFR-03 | Reliability | Durability of accepted messages | Must |
| NFR-04 | Reliability | At least once, with deduplication at the provider (rev. 2026-10) | Must |
| NFR-05 | Availability | The API survives a provider outage | Must |
| NFR-06 | Scalability | Horizontal scaling without design changes | Should |
| NFR-07 | Security | See `08-security.md` (summary here) | Must |
| NFR-08 | Isolation | Strict multi-tenancy with RLS from the MVP (rev. 2026-10) | Must |
| NFR-09 | Operability | Start with one command and simple deployment | Must |
| NFR-10 | Simplicity | Complexity budget | Must |
| NFR-11 | Observability | Correlated metrics, logs and traces | Must |
| NFR-12 | Maintainability | Structure, style and tests | Must |
| NFR-13 | Portability | Local, VPS or managed container | Should |
| NFR-14 | Data | Retention, backup and restore | Should |
| NFR-15 | Privacy | Minimization of personal data | Must |
| NFR-16 | Cost | Economical operation | Should |
| NFR-17 | Error handling | Uniform API errors | Must |
| NFR-18 | Compatibility | Evolving the contract without breaking clients | Must |
| NFR-19 | Performance | Time to sending (new) | Must |
| NFR-20 | Compliance | Law 8968 and privacy obligations (new) | Must |
| NFR-21 | Reliability | Provider limits and reputation (new) | Must |

---

### NFR-01 — Acceptance latency (Must)

- `POST /v1/emails`: **p95 ≤ 150 ms**, p99 ≤ 400 ms, measured on the server (without the client's network) and with the provider **down** (it must not influence it).
- **(rev. 2026-10)** `POST /v1/emails/batch` with 100 items: p95 ≤ 500 ms.
- Queries (`GET /v1/emails/{id}`): p95 ≤ 100 ms.
- Paginated listings: p95 ≤ 500 ms with the reference dataset of AC-20.2.
- Incoming webhooks: p95 ≤ 300 ms, always < 2 s.
- **(rev. 2026-10)** Measured reference (ADR-0014): the full acceptance path (parsing, auth, validation, 2 rate-limit UPSERTs, idempotent INSERT and COMMIT) takes 1.38 ms p50 / 2.33 ms p95 on the server against a local PostgreSQL; the database accounts for 96 %. The 150 ms budget leaves room for the network to the managed database and for the JVM; if it is missed, the cause will be the database or the pool, not the protocol.

### NFR-02 — Sending throughput (Should) (rev. 2026-10)

- Throughput towards the provider is bounded by **its** limit, not by the service: Resend allows **10 requests/second per team** by default ([documentation, 2026-10-05](https://resend.com/docs/api-reference/introduction)). Goal: one worker sustains `WORKER_PROVIDER_RPS` (default 8) messages/s against a simulated provider with 100 ms of latency and never exceeds that rate (a WireMock test that counts requests per second).
- The API accepts **≥ 200 requests/second** on a reference instance (measured: 634 req/s with 16 concurrent clients on a 2 vCPU container that shared CPU with the database and the client).
- First-year business goal: < 100,000 emails/month (≈ 0.04 msg/s on average). At 8 msg/s there is a margin of about 200 times over the average and room for peaks; it is **not** optimized further (NFR-10).

### NFR-03 — Durability (Must)

- A `202` means the message is committed (`COMMIT`) in PostgreSQL. There is no in-memory queue or volatile buffer.
- Killing the process (`kill -9`) at any point loses no messages: `SENDING` messages with an expired lock go back to `QUEUED` automatically.
- No message stays `QUEUED` (eligible) or `SENDING` forever: there is an age metric and a sweep of expired locks every 60 s. **(rev. 2026-10)** Messages held by a suspended or paused tenant are measured separately (AC-02.6).

### NFR-04 — At least once, with deduplication at the provider (Must) (rev. 2026-10)

Replaces "exactly once from the user's point of view", which the previous design could not guarantee (ADR-0010).

- **No-loss guarantee:** every accepted message is tried until it reaches a terminal state (`SENT`→…, `FAILED`, `CANCELED`). We prefer an unlikely duplicate to a lost email.
- **No-duplicate guarantee:**
  - With the client's `Idempotency-Key`, N identical calls produce **one** message (FR-08).
  - Every attempt towards the provider carries `Idempotency-Key = message.id`. Resend deduplicates for **24 h** ([documentation](https://resend.com/docs/dashboard/emails/idempotency-keys)), and the design guarantees that all attempts of a message happen within < 23 h (AC-13.7).
  - Fencing through `lock_token` prevents two workers from closing the same message (AC-12.3).
- **Documented residual risk:**
  1. Switching to a provider without idempotency brings back the timeout duplicate window. `EmailSender` declares `supportsIdempotency()`, and startup logs a warning if it is `false`.
  2. A non-deterministic render invalidates the deduplication; the provider responds `409` and the case is detected (AC-13.8).
  3. Upstream duplicates (the product calls twice without `Idempotency-Key`) cannot be detected.
- **Verification:** a WireMock test that accepts the send and cuts the connection before responding; the retry carries the same `Idempotency-Key` and the simulator counts **one** send.

### NFR-05 — Resilience to the provider (Must)

- With the provider down: the API keeps accepting; messages pile up; when it recovers they drain on their own.
- **(rev. 2026-10)** Timeouts of 3 s (connect) and 10 s (read). A circuit breaker per AC-13.9: 5 consecutive transient failures open it for 60 s; implemented by hand (~50 lines), with no new dependencies.
- The health check reflects the degraded state, but does **not** return `DOWN` because of the provider (that would kill healthy instances).

### NFR-06 — Scalability (Should)

- Several API instances behind a load balancer, with no in-memory state (the rate limit and the queue live in PostgreSQL). The API key cache (≤ 60 s) and the per-IP limit on `401`s are per instance, on purpose.
- Several workers in parallel without duplicate sends (`FOR UPDATE SKIP LOCKED` + `lock_token`). **(rev. 2026-10)** With N workers, `WORKER_PROVIDER_RPS` is divided by N (NFR-21).
- **(rev. 2026-10)** Criterion to re-evaluate the queue (ADR-0010), replacing "> 50 messages/s", which was equivalent to ~130 M emails/month and would never have triggered:
  - the claim query has p95 > 50 ms for 3 days (measured today: 2.4–4.8 ms with 12,800 queued messages), **or**
  - queue age exceeds 60 s with a healthy provider for 3 days, **or**
  - the volume exceeds 3 M emails/month, **or**
  - fan-out to several independent consumers is needed.

  Before that, **no** broker is introduced.

### NFR-07 — Security (Must)

Summary (details in `08-security.md`):
- secrets only in the environment or a secrets manager;
- API keys stored as hashes, with optional scopes and CIDRs;
- TLS or an encrypted private network for all traffic;
- verified webhook signatures;
- limits and anomaly detection per tenant;
- strict input and template validation;
- auditing of sensitive actions;
- no secrets or email content in the logs.

### NFR-08 — Multi-tenant isolation (Must) (rev. 2026-10)

- **First barrier (code):** **every** query touching tenant data carries `tenant_id` in its `WHERE`; the repositories require it in their signature.
- **Second barrier (database), from the MVP (ADR-0008):** RLS with `ENABLE` and `FORCE ROW LEVEL SECURITY` on every table with `tenant_id`. The policy compares with `current_setting('app.tenant_id', true)::uuid`; if the variable is not set, the query returns no rows (fail-closed).
- **Invariants in the database:** composite foreign keys `(tenant_id, …)` prevent a message from referencing a template or version of another tenant.
- Identifiers are unguessable UUIDs; accessing another tenant's resource returns `404`.
- The paths that must cross tenants (claiming from the queue, sweeps, purging, webhook correlation, the API key lookup by prefix and global suppressions) use a second `DataSource` with the `email_system` role, confined by an architecture test to the packages `sending.worker`, `events`, `tenancy.auth`, `tenancy.admin`, `abuse` and `maintenance`.
- Mandatory tests:
  1. For every `/v1/**` endpoint, a case "tenant A does not see/affect tenant B's resources".
  2. A test that runs, with the app's role and **without** `app.tenant_id`, a `SELECT` on every table and expects 0 rows.
  3. A test that tries to insert an `email_message` with a `template_version_id` of another tenant and expects an FK violation.

### NFR-09 — Operability (Must)

- `cp .env.example .env && docker compose up --build` starts app + PostgreSQL + Mailpit, with migrations applied automatically, in **≤ 90 s with the layer or Gradle cache available; ≤ 150 s from a fully cold clone** (dominated by downloading dependencies). **(rev. 2026-10-10)** Measured in H4: 13 s with Docker's layer cache; ≈ 100 s fully cold (89 s of build, almost all Gradle downloads, + 11 s of startup with a new volume). The `Dockerfile` uses a BuildKit cache mount for `~/.gradle`, which survives even without the layer cache and is not part of any image layer. Checked on 2026-10-10:
  - with the layer cache invalidated (a change in `build.gradle.kts`) and the cache mount available, the build takes 39 s; the dependency step drops from 568 s to 19.5 s;
  - `docker build --no-cache` also discards the cache mount, so it measures the fully cold case. That day, on a slow network, it took 717–1150 s: the 150 s limit assumes a normal dependency download (≈ 70 s, as in H4).
- Production deployment: **one image** and roles through an environment variable (`APP_ROLE=api|worker|all|migrate`).
- **(rev. 2026-10)** Flyway migrations run as a step before the deployment with `APP_ROLE=migrate` and the `email_owner` database role; the `api`/`worker` instances run with roles without DDL privileges and do **not** migrate at startup in production. Locally (`APP_ROLE=all`, `APP_ENV=local`) they migrate at startup for convenience.
- Migrations are backwards compatible (a new version of the app must coexist with the previous one during a rolling deployment).
- Configuration only through environment variables (12-factor). **(rev. 2026-10)** The configuration is validated at startup (`MAX_ATTEMPTS`/backoff consistency, window < 23 h, allowlist in `staging`, required secrets); if it fails, the app does not start.

### NFR-10 — Complexity budget (Must)

An explicit rule to stop over-engineering:

- **One** deployable service, **one** database, **zero** external brokers, **zero** external caches, **zero** object storage in the MVP.
- Infrastructure dependencies allowed in production: PostgreSQL and the email provider. Adding any other (Redis, RabbitMQ, Kafka, Elasticsearch, S3…) requires an ADR with a real metric that justifies it.
- The only indirections allowed "just in case" are `EmailSender`/`WebhookVerifier` (provider) and the template engine.
- If a feature needs more than ~200 lines of new infrastructure and is not in `02`, ask before implementing it.

### NFR-11 — Observability (Must)

- JSON logs to stdout with `timestamp`, `level`, `service`, `requestId`, `tenantSlug` and `messageId` when they apply. **(rev. 2026-10)** Retention in the log destination: 30 days; email addresses masked.
- `requestId`: taken from `X-Request-Id` if present (validated: ≤ 64 characters `[A-Za-z0-9-_]`); otherwise generated; always returned in the response.
- Micrometer metrics (see FR-24) reachable through Actuator; export to Prometheus by configuration.
- Distributed tracing: out of the MVP (`requestId` is enough); **(rev. 2026-10)** the OTLP exporter can be enabled by configuration without code changes.
- **(rev. 2026-10)** Minimal alerts: queue age > 300 s; `FAILED` rate > 5 % in 1 h; paused tenant (FR-34); circuit breaker open > 10 min; `email_lost_lock_total` > 0; `PROVIDER_IDEMPOTENCY_CONFLICT` > 0; complaint rate per tenant > 0.1 % over 7 days.
- **(rev. 2026-10-12)** Implemented in H7. The owner decided the channel; the rest are proposals pending their confirmation:
  - **Channel:** `ERROR` log lines with the `alert` field, deduplicated for 15 min. The values are in the README; `ops/prometheus/alerts.yml` has the reference rules.
  - **Added alerts:** `PROVIDER_CONFIGURATION` (H5) and `AUTH_FAILURE_SPIKE` (docs/08 §9).
  - **`FAILED_RATE`:** requires at least 20 messages with an outcome in the hour, so as not to alert on two sends.

### NFR-12 — Maintainability (Must)

- Packages by capability (`sending`, `templates`, `tenancy`, `events`, `provider`, `ratelimit`, `abuse`, `audit`, `maintenance`, `common`), not by technical layer.
- No heavy ORM frameworks or magic generation: explicit, readable data access.
- Coverage ≥ 75 % overall; ≥ 90 % in template rendering, the template linter, the retry policy, idempotency, authentication and scopes.
- Integration tests with Testcontainers (PostgreSQL with the same roles and RLS policies as production) and a simulated HTTP server for the provider; **no test sends real email**.
- Formatting and static analysis in CI; a reproducible build. **(rev. 2026-10)** Architecture tests (ArchUnit) for the dependency rules of `05` §2 and for the use of the system `DataSource`.

### NFR-13 — Portability (Should)

- `linux/amd64` and `linux/arm64` image.
- Works the same in local Docker Compose, on a VPS with Docker and on a managed container (Fly.io, Render, ECS…). No dependency on a specific cloud provider in the MVP.

### NFR-14 — Retention, backup and restore (Should)

- Retention configurable per tenant (default 90 days) with a daily purge (FR-23).
- **(rev. 2026-10)** Backup: the managed PostgreSQL's PITR (RPO ≤ 5 min) or an encrypted daily `pg_dump` (RPO ≤ 24 h). RTO ≤ 4 h. Backups encrypted at rest and with retention ≤ 35 days, so that what the retention purge and data-subject erasures remove also disappears from the backups within a bounded time.
- A restore procedure tested at least once before production and then every 6 months; documented in the README.

### NFR-15 — Privacy (Must)

- The minimum is stored: the recipient's address, the template variables and metadata. The rendered HTML is stored **only** if the tenant enables it (`storeRenderedContent`), and with the `x-sensitive` variables replaced by `[REDACTED]`.
- **(rev. 2026-10)** Variables are purged 7 days after the message reaches its final state, and `x-sensitive` ones on sending (AC-23.5). Event payloads are minimized (AC-17.1).
- Variables can contain personal data: they are never logged.
- Documented in the README: the email provider processes addresses and content as a data processor, in the US.
- **(rev. 2026-10)** Open and click tracking is disabled by default and forbidden in `SECURITY` templates (AC-04.11).

### NFR-16 — Cost (Should) (rev. 2026-10)

- Target operation: **≤ 25 USD/month** while the provider's free plan (3,000 emails/month, 100/day) and a 1 vCPU/1 GB managed database (15.15 USD on DigitalOcean) are enough; **≤ 50 USD/month** with Resend Pro (20 USD for 50,000 emails). Prices checked on 2026-10-05; reviewed before contracting.
- The design avoids components with an extra fixed cost (brokers, caches, clusters, object storage).
- Note: Resend's free plan limit is **100 emails/day**; a single spike (e.g. a notice to all of a product's customers) exhausts it and blocks password recoveries for the rest of the day. In production with more than one product, Resend Pro is assumed.

### NFR-17 — Uniform API errors (Must)

- All errors in `application/problem+json` (RFC 9457) with `type`, `title`, `status`, `detail`, `instance`, and the extensions `code` (an UPPERCASE constant) and `requestId`.
- Stack traces, SQL and raw provider responses are never exposed.
- The `code` values are a closed enum documented in `07-internal-api.md`. **(rev. 2026-10)** Adding a new `code` is **not** an incompatible change if the client follows the rule in `07` §1 (treat an unknown `code` by its HTTP class).

### NFR-18 — Evolving the contract (Must)

- Versioning in the path (`/v1`). Compatible changes: adding optional fields in responses, optional fields in requests or new error codes.
- Incompatible changes (renaming/removing fields, changing semantics, adding values to a **state** enum the client interprets): a new route version + ADR.
- Clients must ignore unknown fields; it is written down in the API documentation.
- **(rev. 2026-10)** While there is no version deployed in production (Phase 0 unfinished), changes to `/v1` are recorded in `CHANGELOG.md` with their migration note for the products, without opening `/v2`. From the first production deployment, the general rule applies with no exceptions.

### NFR-19 — Time to sending (Must, new)

- Acceptance → `SENT`: **p95 ≤ 5 s** and p99 ≤ 15 s for `SECURITY` and `TRANSACTIONAL` messages, with a healthy provider and no pending retries; measured by the metric `email_time_to_sent_seconds` per category.
- A `SECURITY` message never waits behind eligible `NOTICE` messages (AC-36.3).
- `WORKER_POLL_INTERVAL_MS` (default 1,000) contributes up to 1 s; LISTEN/NOTIFY is not added unless this NFR is missed.

### NFR-20 — Compliance (Law 8968 and privacy) (Must, new)

I am not a lawyer: what follows is the technical reading of obligations checked in secondary sources on 2026-10-05, to be validated with legal counsel.

- **International transfer:** Law 8968 has no adequacy regime; transferring data to a processor abroad (the email provider, in the US) requires the data subject's express, informed authorization (art. 14). It is the responsibility of **each product**: its privacy notice and its consent must cover it. The email-service documents in the README which data the provider receives.
- **ARCO rights:** handled within **5 business days**; the email-service offers FR-29 for data erasure.
- **Security breaches:** the Regulation (Decree 37554-JP, arts. 38–39) requires notifying the affected people and PRODHAB within **5 business days** of the incident; integrated in `08` §9.
- **Registration with PRODHAB:** mandatory for databases that are distributed, disseminated or sold; those for internal use are exempt. The email-service is considered internal use (**to be confirmed** with legal counsel).
- **(rev. 2026-10, owner) Colmena processes data of its customers' customers** (products, debts). In those emails, Colmena's customer business is the **controller**, Colmena is the **processor**, and the email-service and the provider are **sub-processors**. Implications:
  - Colmena's contract or terms with each business must cover sending emails to its customers and the transfer to the provider in the US;
  - debt reminders are especially sensitive data: they are marked `x-sensitive` (amounts, concepts) and tracking is not enabled;
  - if debt collection is regulated (hours, contact frequency), the rule lives in Colmena, not here: **to be confirmed** with legal counsel which collection regulation applies to email reminders.
- **(rev. 2026-10, owner) Geographic scope:** users in Costa Rica only. GDPR and CAN-SPAM do not apply as long as that does not change; Gmail's, Yahoo's and Microsoft's requirements do apply (they depend on the mailbox, not the country).
- **Status of the international transfer (open question 9):** **pending, it does not block development**; it is a requirement of each product's go-live checklist (`08` §10).
- **Pending reform:** bill 23.097 (a new data protection law) is not on record as approved; its 2026 status is **to be confirmed**.
- **Commercial email:** outside this service's scope (ADR-0013); its requirements are in `11-bulk-email.md`.

### NFR-21 — Provider limits and reputation (Must, new)

- The sum of `WORKER_PROVIDER_RPS` over all instances sharing a provider account ≤ 80 % of the contracted limit (Resend by default: 10 rps per team → 8 rps).
- **Staging and production use different provider teams (accounts)**: they share no rate limit, suppression list or reputation.
- Every sending domain has SPF, DKIM and DMARC (at least `p=none`, aligned) verified before sending; Gmail, Yahoo and Microsoft require it even for transactional email.
- Target complaint rate < 0.1 % per tenant and never ≥ 0.3 % (Gmail's thresholds). FR-34 pauses before reaching 0.3 %.
- An accepted, documented risk: all products share **one** provider account (common suppression, limit and account review). See the open question about accounts per product in `docs/audit/open-questions.md`.
