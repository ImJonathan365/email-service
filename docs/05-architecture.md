# 05 — Architecture

> Revision 2026-10-05: acceptance flow reordered (idempotency before the rate limit), worker with fencing, priority and idempotency towards the provider (ADR-0010), two `DataSource`s and RLS (ADR-0008), anomalies (FR-34), complete configuration.

## 1. Overview

```
   PRODUCTS (tenants)                         EMAIL-SERVICE (a single artifact)                    OUTSIDE
┌──────────────────────┐        ┌──────────────────────────────────────────────────┐
│  PipeMend            │        │                                                  │
│  Colmena             │        │   ┌────────────────────────────────────────────┐ │
│  Chinamo             │───────▶│   │  API  (APP_ROLE=api)                       │ │
│  Payverica           │ HTTPS  │   │  • API key auth + scopes + CIDR            │ │
│  Bearer esk_live_... │keep-   │   │  • Idempotency → validation → render       │ │
└──────────────────────┘alive   │   │    → rate limit → INSERT (one transaction) │ │
                                │   │  • Templates (CRUD + linter + preview)     │ │
                                │   │  • Queries on sends and events             │ │
                                │   │  • /admin/v1 (private network only)        │ │
                                │   └───────────────┬────────────────────────────┘ │
                                │  email_app (RLS)  │ INSERT (commit)               │
                                │                   ▼                               │
                                │   ┌────────────────────────────────────────────┐ │
                                │   │      PostgreSQL 18 (exclusive, RLS)        │ │
                                │   │  tenant · api_key · template ·             │ │
                                │   │  template_version · email_message (QUEUE) ·│ │
                                │   │  email_attachment · email_event ·          │ │
                                │   │  suppression · audit_log ·                 │ │
                                │   │  rate_limit_counter                        │ │
                                │   └───────────────▲──────────────┬─────────────┘ │
                                │  email_system     │ short claim  │              │ │
                                │  (BYPASSRLS)      │ SKIP LOCKED  ▼              │ │
                                │   ┌───────────────┴────────────────────────────┐ │      ┌───────────────┐
                                │   │  WORKER  (APP_ROLE=worker)                 │ │      │  Provider     │
                                │   │  • Claim by priority (free slots)          │ │─────▶│  (Resend)     │
                                │   │  • Render (pinned version)                 │ │ HTTPS│ Idempotency-  │
                                │   │  • EmailSender.send(idempotencyKey=id)     │ │◀─────│ Key = msg.id  │
                                │   │  • Close with lock_token (fencing)         │ │      └───────┬───────┘
                                │   │  • Sweep, purge, anomalies, metrics        │ │              │ webhook
                                │   └────────────────────────────────────────────┘ │              │ (Svix)
                                │   ┌────────────────────────────────────────────┐ │              │
                                │   │  /webhooks/{provider}  (in the API role)   │◀┼──────────────┘
                                │   │  • WebhookVerifier: raw body + window      │ │
                                │   │  • Dedupe by svix-id                       │ │       ┌──────────────┐
                                │   │  • State + suppressions (email_system)     │ │       │  Mailpit     │
                                │   └────────────────────────────────────────────┘ │  dev  │ (local SMTP) │
                                └──────────────────────────────────────────────────┘◀──────└──────────────┘
```

**One artifact, four roles** selected by `APP_ROLE`:

| Role | Contains | Use |
|---|---|---|
| `api` | REST controllers, incoming webhooks, administration | Instances behind the load balancer |
| `worker` | Sending, retries, sweeps, purging, anomalies | 1 instance by default (N with `WORKER_PROVIDER_RPS` split) |
| `all` | API + worker | Local development and small deployments (default) |
| `migrate` | **(rev. 2026-10)** Runs Flyway with `email_owner` and exits | A step before every deployment |

There are no extra microservices, broker, external cache or object storage (NFR-10).

## 2. Internal modules

```
com.emailservice
├── tenancy        # Tenant, ApiKey (scopes, CIDR), authentication, tenant context, administration
├── templates      # Template, TemplateVersion, hardened Handlebars engine, linter, validation and preview
├── sending        # Sending and batch API, idempotency, sendAt, queue, worker, retry policy, priority
├── provider       # EmailSender + WebhookVerifier (ports) and their Resend / Smtp / Noop implementations
├── events         # Incoming webhooks, events, suppressions (global/tenant)
├── ratelimit      # Counters per tenant and quotas
├── abuse          # (rev. 2026-10) Anomaly detection and automatic pause (FR-34)
├── audit          # Record of sensitive actions
├── maintenance    # (rev. 2026-10) Purging, data-subject erasure, sweeps
└── common         # problem+json errors, configuration and its validation, logging/MDC, metrics
```

Dependency rules (verifiable with ArchUnit):

- `sending` and `events` depend on `provider` **only** through `EmailSender` and `WebhookVerifier`.
- No module imports classes of the provider's SDK outside `provider`.
- `templates` does not know `sending`; it receives text and variables and returns rendered text.
- Every tenant query goes through a repository that requires `tenantId` and uses `tenantDataSource` (role `email_app`, RLS).
- **(rev. 2026-10)** `systemDataSource` (role `email_system`) is injected only in `sending.worker`, `events`, `tenancy.auth`, `tenancy.admin`, `abuse` and `maintenance`.
- **(rev. 2026-10-11, owner)** Rules added in H6:
  - there are no cycles between the top-level modules (ArchUnit);
  - `sending` depends on `events` (suppressions, stored events) and `events` never on `sending`: if `events` updates `email_message`, it does so with its own repository over the system DataSource;
  - `EmailAddresses` (normalization and validation) and `SuppressionHashes` live in `events`, the owner of suppressions;
  - the tenant transaction manager is in `tenancy` (previously in `common`, which formed a cycle).

## 3. Sending flow (happy path) (rev. 2026-10)

```
1.  POST /v1/emails            (product; reused connection)
2.  API key authentication → tenant context; emails:send scope; CIDR                 [FR-01, FR-33]
3.  Tenant ACTIVE and not paused                                                     [FR-02, FR-34]
4.  BEGIN; set_config('app.tenant_id', …, true)                                       [ADR-0008]
5.  Idempotency: if (tenant_id, key) exists → same hash ⇒ 200 original; different ⇒ 409   [FR-08]
6.  Validation of payload, addresses, headers, fromName, sendAt                      [FR-07, FR-09, FR-31]
7.  Template resolution by locale → template_version_id pinned; category → priority [FR-05, FR-36, FR-37]
8.  Variable validation (schema + https URL in allowedLinkHosts)                     [FR-07]
9.  Trial render (result discarded)                                                  [AC-07.5]
10. Suppression (to/cc/bcc; global + tenant)                                         [FR-10]
11. Rate limit (minute/day UPSERT) — after validating                                [FR-21]
12. INSERT email_message (QUEUED, next_attempt_at = now() or sendAt) ON CONFLICT DO NOTHING   [FR-11, FR-31]
13. COMMIT → 202 Accepted { id, status }            ← the client can carry on

--- asynchronous (worker, role email_system) ---
14. Claim: SELECT … QUEUED, eligible, active tenant ORDER BY priority, next_attempt_at
    FOR UPDATE SKIP LOCKED LIMIT <free slots>; UPDATE SENDING, attempts+1, lock_token   [ADR-0010]
    (immediate COMMIT: no network call with locks held)
15. Recheck the suppression and the tenant's state
16. Render subject/html/text with the pinned version                                 [FR-12]
17. Rate limiter (WORKER_PROVIDER_RPS) + circuit breaker
18. EmailSender.send(..., idempotencyKey = message.id) → providerMessageId          [FR-14]
19. UPDATE … SET SENT WHERE id AND lock_token = :token AND status = 'SENDING'        [AC-12.3]
20. Immediate purge of x-sensitive variables                                         [AC-23.5]
--- later ---
21. Webhook (Svix): verify the signature on the raw body + window + dedupe by svix-id   [FR-16]
22. INSERT email_event (minimized payload); state moves forward by precedence       [FR-17]
23. If bounced/complained/suppressed → GLOBAL suppression (independent of the state)   [FR-10, ADR-0012]
```

## 4. Retry policy and error classification

| Class | Examples | Action |
|---|---|---|
| **Transient** | timeout, network error, `429`, provider `500`–`504`, `409` `concurrent_idempotent_requests` / `resource_locked` (the same key is still in progress), expired lock | `QUEUED` with backoff (`attempts` was already incremented on claiming) |
| **Permanent** | provider `400`/`422`, invalid address, rejected content, `409` `invalid_idempotent_request` (same key, different body) | Immediate `FAILED` with `failure_code` |
| **Configuration** (rev. 2026-10-10) | `401`, an API-key `403` (`restricted_api_key`, `suspended_api_key`, `invalid_permission`), `404`/`405`: our account fails, not the message | Opens the circuit breaker immediately; the message goes back to `QUEUED` **without** using up an attempt (`PROVIDER_CONFIGURATION` in `attempt_log`); an error log and `email_provider_config_error_total{error}` (alert) |
| **Tenant configuration** (rev. 2026-10-10) | `403` `validation_error`: the tenant's sending domain is not verified | Pauses **only that tenant** (`pause_reason = PROVIDER_DOMAIN`, audit `TENANT_PAUSED`, same metric); the message goes back to `QUEUED` without using up an attempt. Other tenants keep sending. Lifted with `POST /admin/v1/tenants/{slug}/resume-sending` |
| **Local** | Render error | Immediate `FAILED` (`RENDER_ERROR`), without calling the provider |
| **Blocking** | Suppressed recipient | `FAILED` (`SUPPRESSED`) |
| **Held** (rev. 2026-10) | Suspended or paused tenant | **Not** claimed; it stays `QUEUED` until reactivated |

**(rev. 2026-10-07) Behaviour implemented in H4:**
- **Close with fencing:** every close (`SENT`, `FAILED`, back to `QUEUED`) carries `WHERE status = 'SENDING' AND lock_token = :mine`. With 0 rows, the worker only adds `LOST_LOCK` to `attempt_log` and increments `email_lost_lock_total`.
- **Variable purging (AC-23.5):**
  - the same `UPDATE` that moves to `SENT` removes the `x-sensitive` variables, but does not set `finalized_at`, because `SENT` is not terminal;
  - `FAILED`, including the one the sweep leaves, removes the `x-sensitive` ones and does set `finalized_at`.
- **Tenant suspended or paused** between claiming and sending: the message goes back to `QUEUED` and the attempt is given back (`attempts - 1`), because holding is not failing.
- **Unexpected error** (database down, a bug): the message is left in `SENDING`; the sweep recovers it when the lock expires, and the provider deduplicates by `message.id`.
- **Metrics:**
  - `email_time_to_sent_seconds{category}` (NFR-19), from `created_at` to `SENT`;
  - `email_lost_lock_total`;
  - `email_locale_fallback_total`;
  - `email_requests_without_idempotency_total`.

**(rev. 2026-10-10) Behaviour implemented in H5 (Resend):**
- **`ResendEmailSender`:** `POST /emails` with the JDK's HTTP client. Every attempt carries `Idempotency-Key = message.id` and the tag `message_id=<uuid>`; the product's tags go as `{name: tag, value: "true"}`. Of the provider's error only its `name` is kept (e.g. `Resend 422 validation_error`), never the message, which can repeat addresses.
- **`supportsIdempotency()`:** the port declares whether the provider deduplicates by key. If not (SMTP), the worker warns at startup: a lost response can duplicate the email.
- **`WORKER_PROVIDER_RPS`:** each instance spaces its calls by 1/RPS with no burst, so that no one-second window exceeds RPS. Instances do not coordinate: the sum is adjusted through configuration (NFR-21).
- **Circuit breaker (AC-13.9),** our own code, no Resilience4j:
  - it opens after `CIRCUIT_BREAKER_FAILURES` consecutive transient failures, or immediately on a configuration error;
  - while open, the worker claims no messages, and those already claimed in the same round go back to `QUEUED` without an attempt (`CIRCUIT_OPEN`);
  - after `CIRCUIT_BREAKER_OPEN_SECONDS` it lets **one** trial send through: if it succeeds it closes, if it fails it opens for another period;
  - gauge `email_circuit_breaker_open`.
- **23 h window:** configuration errors do not use up attempts, but the window is counted from `first_attempt_at`. If the account stays broken for more than 23 h, the message ends `FAILED` (`RETRY_WINDOW_EXCEEDED`) even though `attempts` did not go up.
- **New metrics:**
  - `email_provider_config_error_total{error}` (alert);
  - `email_provider_idempotency_conflict_total` (indicates a non-deterministic render, AC-12.7);
  - `email_circuit_breaker_open`.
- **Resend webhooks:** not implemented here, they arrive in H6.

Backoff: `1 min → 5 min → 15 min → 1 h → 6 h` with ±20 % jitter; `MAX_ATTEMPTS` = 6 (5 waits + 1). Total window < 23 h, validated at startup (AC-13.7). A `429` with `Retry-After` honours that value.

**(rev. 2026-10-11) Behaviour implemented in H6 (events):**
- **Webhook:**
  - `SvixWebhookVerifier` (our own, no SDK) behind `WebhookVerifier`: HMAC-SHA256 over `svix-id.svix-timestamp.<raw body>`, constant-time comparison, one or two `whsec_` secrets, a ±`WEBHOOK_TOLERANCE_SECONDS` window;
  - it is verified with the example published by Svix;
  - the endpoint only exists with `APP_ROLE=api|all`.
- **One transaction per event** (role `email_system`):
  - `INSERT … ON CONFLICT (provider, provider_event_id) DO NOTHING`: a duplicate has no effects;
  - then the state change by precedence (each `UPDATE` lists the states it can leave from) and the suppressions, independent of each other.
- **Early events and failure with a later event:** see `02` §2.
- **Suppressions from events:** `bounced` → `HARD_BOUNCE`, `complained` → `COMPLAINT`, `suppressed` and `suppression.added` → `PROVIDER`, all global; `suppression.removed` only removes `PROVIDER`. `cc`/`bcc` rule: AC-10.3.
- **Payload minimized by whitelist:**
  - kept: `type`, `created_at`, `data.email_id`, `data.created_at`, `tags.message_id`, `bounce.type/subType`, `suppressed.type`, the click and open timestamps, `click.link` without query or fragment, and in `suppression.*` the fields `id`, `origin` and `source_id`;
  - never kept: addresses, subject, headers, IP or user agent.
- **Metrics:**
  - `email_webhook_events_total{type,outcome}` (`outcome` = `processed`, `duplicate` or `orphan`);
  - `email_webhook_rejected_total{reason}`;
  - `email_webhook_correlation_conflict_total`;
  - `email_complaints_total{tenant}` (alert) and `email_delivery_delayed_total{tenant}`;
  - `email_event_after_failure_total`;
  - `email_suppressions_created_total{reason}`.
- **Outside H6:** purging orphans after 30 days (AC-16.5, task `purgeExpiredData`) and the event history in `GET /v1/emails/{id}` (H7).

**(rev. 2026-10-12) Behaviour implemented in H7, part 2:**
- **Rate limit and quota** (FR-21):
  - the `rate_limit_counter` counter is incremented in the `INSERT`'s transaction, after the validations;
  - the `MINUTE` window is UTC and the `DAY` one follows the tenant's calendar (`timezone`). **(rev. 2026-10-13)** Both are computed with the application's `Clock` (see "Time source");
  - when exceeded, `429 RATE_LIMITED` with `Retry-After` until the end of the window; the rollback undoes the increment, so the rejection consumes no quota;
  - limits are read on every request, so a `PATCH` applies without redeploying;
  - aggregated audit: one `TENANT_RATE_LIMITED` row per tenant, window and hour (owner), in its own transaction;
  - metric `email_rate_limited_total{tenant,window}`.
- **`detectAnomalies`** (FR-34), a worker task every 5 min under an advisory lock, on `created_at`:
  - **volume:** accepted in the last 60 min against `max(multiplier × hourly p95 of the previous 14 days, hourly minimum)`, counting hours without messages as zero;
  - **bounces:** `BOUNCED / (SENT + DELIVERED + BOUNCED + COMPLAINED)` over 24 h, with ≥ 200 of those outcomes;
  - **complaints:** `COMPLAINED / (DELIVERED + COMPLAINED)` over 7 days with ≥ 1,000 delivered, or 3 complaints in 24 h;
  - order of priority: complaints, bounces, volume;
  - when pausing: audit `TENANT_PAUSED` (SYSTEM, with what was observed and the threshold), metric `email_tenant_paused_total{reason}` and alert `TENANT_PAUSED`.
- **Worker periodic tasks:** `PeriodicTasks` runs them on virtual threads, each with its own interval. The lock sweep stays in the queue loop.
- **Alerts:** ERROR log lines with the `alert` and `alertSubject` fields, deduplicated for 15 min per instance (owner). The full list of alerts comes with part 3.

**(rev. 2026-10-12) Behaviour implemented in H7, part 3:**
- **Health:**
  - `queue` indicator (all roles): `DEGRADED` with HTTP 200 above `QUEUE_AGE_ALERT_SECONDS`; it does not count held tenants;
  - `provider` indicator (worker): results of the last 5 min, without pinging. `DOWN` with the breaker open or after a configuration error; `DEGRADED` if at least half of 5 or more calls failed transiently;
  - neither is part of the probes;
  - components are shown, not their details.
- **New metrics (AC-24.2):**
  - `email_messages{status}` (last 24 h);
  - `email_queue_depth`, `email_queue_oldest_seconds` and `email_queue_held{tenant}`;
  - `email_acceptance_seconds` and `email_provider_latency_seconds{provider,outcome}`;
  - `email_send_attempts` (attempts until `SENT` or `FAILED`);
  - `email_tenant_bounce_rate{tenant}` and `email_tenant_complaint_rate{tenant}`;
  - `email_purged_rows_total{data}`.
- **Alerts** (README, "Operations"): `QUEUE_AGE`, `FAILED_RATE`, `TENANT_PAUSED`, `CIRCUIT_BREAKER_OPEN`, `LOST_LOCK`, `IDEMPOTENCY_CONFLICT`, `COMPLAINT_RATE`, `PROVIDER_CONFIGURATION` and `AUTH_FAILURE_SPIKE`.
- **Logs (AC-24.3):** the worker and the webhooks add `tenantSlug` and `messageId` to the MDC. `EmailAddresses.masked` is the only allowed way to show an address; today no log line needs it.

**(rev. 2026-10-13) Time source: the application's `Clock` or PostgreSQL's `now()`.**

Rule: a decision uses a single source at both ends. If it compares with columns written by the database, the decision is taken in SQL with `now()`. If its state lives in memory or is written by the application, it uses the `Clock` bean, which tests can fix.

| Source | Decisions | Why |
|---|---|---|
| PostgreSQL's `now()` | Queue claiming (`next_attempt_at <= now()`), locks and the sweep, `first_attempt_at`, `sent_at`, `finalized_at`, purges, anomalies, queue metrics and acceptance → `SENT` time (NFR-19), API key expiry | The data and the cut-off are measured with the same clock, in the same statement |
| The application's `Clock` | Circuit breaker, `401` per IP, prefix refresh, alert deduplication, rate-limit windows and their hourly audit, `Retry-After`, the webhook signature window, the 23 h window and the worker's next attempt, `sendAt` validation | In-memory state or computed by the application; tests fix it and advance it without depending on when they run |

**Points where the two sources meet**, with a skew of *s* seconds between the application and PostgreSQL:

1. **The 23 h window** (`first_attempt_at` is written by PostgreSQL and the worker compares it with the `Clock`). The effective window becomes 23 h ± *s*. The limit that matters is Resend's idempotency one, 24 h from the first attempt: there is 1 h of margin minus the duration of one attempt (≤ `LOCK_TIMEOUT_SECONDS`, 60 s), so it is safe while *s* < ~59 min. With NTP, *s* is milliseconds. The startup validation (AC-13.7) already requires backoff × 1.2 + attempts × lock < 23 h.
2. **Next attempt and `sendAt`** (`next_attempt_at` is written by the application and the queue claim compares it with `now()`). The retry or the scheduled send moves earlier or later by *s*. The minimum backoff is 60 s ±20 %, and a `Retry-After` brought forward a few seconds only produces another transient `429`. Safe.
3. **Rate-limit windows** (`window_start` is written by the application and the counter purge uses `now()` with a 2-day margin). Safe.
4. **Fallback time of a webhook without `created_at`** (stored in `occurred_at` next to the database's `received_at`). It only affects the order of events the provider did not date. Safe.
5. **Fixed here:** the acceptance → `SENT` time subtracted the database's `created_at` from the application's time, and a few seconds of skew biased a 5 s target (NFR-19). Now `markSent` returns it computed in SQL (`sent_at - created_at`).

**Rate limit with several API instances.** It moved from PostgreSQL's `now()` to the `Clock`:
- **Why:** so that all of the application's decisions use one source that tests can fix. Before, the tests depended on the second of the real minute and used `sleep`.
- **What does not change:** the counter is still a single row per tenant, window and period in PostgreSQL, shared by all instances (AC-21.2).
- **What changes:** each instance picks the window with its own clock. With a skew of *s* between instances, only the requests in the *s* seconds around the minute boundary can be counted in the neighbouring window. Fixed windows already allow up to 2 × the limit in 60 consecutive seconds that cross the minute boundary, so this effect does not change the guarantee.
- **Requirement:** instances must have their time synchronized (NTP), as webhook signature validation already requires (±5 min).

Outside the rule:
- `System.nanoTime` as a monotonic clock: the provider call limiter (injectable) and the worker loop's interval;
- the time inside UUID v7 values.

**Anti-duplicate safeguards (rev. 2026-10):**
1. `Idempotency-Key = message.id` towards the provider (24 h deduplication).
2. Closing with `lock_token`: a worker that lost the lock neither closes nor retries.
3. If the message already has a `provider_message_id`, the provider is never called again.

## 5. Scheduled tasks (inside the worker)

| Task | Frequency | What it does |
|---|---|---|
| `reclaimStuckMessages` | 60 s | `SENDING` with an expired lock → `QUEUED` (or `FAILED` if the attempts ran out) |
| `detectAnomalies` | 5 min | **(rev. 2026-10)** Volume, bounces and complaints per tenant → pause (FR-34) |
| `purgeVariables` | 15 min | **(rev. 2026-10)** AC-23.5 |
| `purgeExpiredData` | Daily | Retention per tenant (messages, events, rendered content, orphans) |
| `purgeIdempotencyKeys` | Hourly | Expired idempotency keys |
| `emitQueueMetrics` | 30 s | Depth, age and held messages |

**(rev. 2026-10-12)** H7 completes the table with `PeriodicTasks`:
- **`purgeVariables`** every 15 min and **`purgeIdempotencyKeys`** every hour.
- **`purgeExpiredData`** daily. It starts 10 min after startup, so that frequent deployments do not leave it unrun.
- **`emitQueueMetrics`** every 30 s: gauges, and the `QUEUE_AGE` and `FAILED_RATE` alerts.
- **`detectAnomalies`** every 5 min.
- **`providerAlerts`** every 30 s: breaker open for more than 10 min.
- **Purges in batches:** 5,000 rows per batch, each batch in its own transaction with the task's advisory lock.

**(rev. 2026-10-07)** In H4 `reclaimStuckMessages` exists; the other tasks arrive with their milestones. With several worker instances, each task runs inside `pg_try_advisory_xact_lock(hashtext('<task>'))`: if another instance holds it, that round is skipped. **(rev. 2026-10)** The `scheduled_lock` table is removed. Quartz and ShedLock are not added.

## 6. Configuration (environment variables) (rev. 2026-10)

| Variable | Default | Description |
|---|---|---|
| `APP_ENV` | `local` | `local` \| `staging` \| `production` |
| `APP_ROLE` | `all` | `api` \| `worker` \| `all` \| `migrate` |
| `DB_URL` | — | PostgreSQL |
| `DB_APP_USER` / `DB_APP_PASSWORD` | — | Role `email_app` (RLS) |
| `DB_SYSTEM_USER` / `DB_SYSTEM_PASSWORD` | — | Role `email_system` (worker, webhooks, admin) |
| `DB_OWNER_USER` / `DB_OWNER_PASSWORD` | — | Role `email_owner`, only with `APP_ROLE=migrate` |
| `ADMIN_API_KEYS` | — | Credentials for `/admin/v1/**`, comma-separated (required, no default). **(rev. 2026-10-06)** Required only with `APP_ROLE=api\|all` (the worker and `migrate` never receive it); each one ≥ 32 characters |
| `TRUSTED_PROXIES` | *(empty)* | CIDRs of the proxies whose `X-Forwarded-For` is accepted |
| `AUTH_FAILURES_PER_MINUTE_PER_IP` | `30` | **(rev. 2026-10-12)** Authentication failures per IP and minute before that IP's requests without a known key prefix are limited (`08` §4) |
| `MAIL_PROVIDER` | `smtp` | `resend` \| `smtp` \| `noop`. **(rev. 2026-10-07)** `noop` (sends nothing) does not start with `APP_ENV=production`; with `noop` no SMTP client is created |
| `RESEND_API_KEY` | — | Secret; **required** with `MAIL_PROVIDER=resend` (rev. 2026-10-10). Never logged |
| `RESEND_API_URL` | `https://api.resend.com` | (rev. 2026-10-10) Base of the API; must be `https` except with `APP_ENV=local` (tests against a stub) |
| `MAIL_WEBHOOK_SIGNING_SECRET` | — | Secret of the webhook endpoint (Svix); accepts two comma-separated values during a rotation. **(rev. 2026-10-11)** Required with `MAIL_PROVIDER=resend` and `APP_ROLE=api\|all`; each value `whsec_` + base64. Without it, `/webhooks/resend` does not exist. **(rev. 2026-10-14)** Locally, `docker-compose.yml` passes the fictional secret from `.env.example` for `scripts/simulate-event.sh` (docs/12) |
| `WEBHOOK_TOLERANCE_SECONDS` | `300` | Timestamp window (1–3600) |
| `SMTP_HOST` / `SMTP_PORT` | `mailpit` / `1025` | Development mode |
| `ALLOW_SMTP_IN_PRODUCTION` | `false` | Fail-fast with `production` + `smtp` |
| `PROVIDER_CONNECT_TIMEOUT_MS` / `PROVIDER_READ_TIMEOUT_MS` | `3000` / `10000` | Timeouts towards the provider |
| `WORKER_CONCURRENCY` | `4` | Simultaneous sends (and maximum per claim) |
| `WORKER_POLL_INTERVAL_MS` | `1000` | Queue polling |
| `WORKER_PROVIDER_RPS` | `8` | Rate limit per instance towards the provider (NFR-21) |
| `MAX_ATTEMPTS` | `6` | = number of waits + 1 (validated) |
| `RETRY_BACKOFF_SECONDS` | `60,300,900,3600,21600` | Backoff (total window < 23 h, validated) |
| `LOCK_TIMEOUT_SECONDS` | `60` | Lock expiry per message |
| `CIRCUIT_BREAKER_FAILURES` / `CIRCUIT_BREAKER_OPEN_SECONDS` | `5` / `60` | Circuit breaker (AC-13.9); both ≥ 1 |
| `DEFAULT_RATE_LIMIT_PER_MINUTE` | `60` | Per tenant |
| `DEFAULT_DAILY_QUOTA` | `1000` | Per tenant (set ~3× the expected volume) |
| `DEFAULT_RETENTION_DAYS` | `90` | Per tenant |
| `VARIABLES_RETENTION_DAYS` | `7` | After the final state (AC-23.5). **(rev. 2026-10-12)** From 1 to 90 |
| `IDEMPOTENCY_RETENTION_HOURS` | `24` | AC-08.5. **(rev. 2026-10-12)** At least 24: a repeat within the window must find its message |
| `SENT_FINAL_AFTER_HOURS` | `72` | `SENT` without events = time-based final |
| `MAX_REQUEST_BYTES` | `262144` | Payload size |
| `MAX_BATCH_ITEMS` / `MAX_BATCH_BYTES` | `100` / `1048576` | Batch endpoint. **(rev. 2026-10-13)** 1–1000 items; the size cannot be smaller than `MAX_REQUEST_BYTES` |
| `SEND_AT_MAX_DAYS` | `30` | FR-31 (1–365) |
| `SEND_AT_PAST_TOLERANCE_SECONDS` | `60` | **(rev. 2026-10-13)** How far in the past still counts as "now" (0–600) |
| `SUPPORTED_LOCALES` | `es-CR,en` | FR-37 |
| `SUPPRESSION_REJECT_MODE` | `accept` | `accept` (202 + `FAILED`) \| `reject` (422) |
| `SUPPRESSION_HASH_KEY` | — | HMAC key for `email_hash` (secret, required). **(rev. 2026-10-07)** ≥ 32 characters; required in every role except `migrate` |
| `ALLOWED_RECIPIENT_DOMAINS` | *(empty)* | Recipient allowlist; **required** in `staging` |
| `QUEUE_AGE_ALERT_SECONDS` | `300` | `DEGRADED` and alert threshold. **(rev. 2026-10-12)** At least 30 |
| `ANOMALY_VOLUME_MULTIPLIER` / `ANOMALY_MIN_HOURLY` | `3` / `50` | FR-34. **(rev. 2026-10-12)** Default values for each tenant. `anomalyOverrides` can change the multiplier and raise the hourly minimum, never lower it |
| `BOUNCE_PAUSE_RATE` / `COMPLAINT_PAUSE_RATE` | `0.04` / `0.001` | FR-34; adjustable per tenant with `anomalyOverrides`. Both between 0 and 1 |

No credential lives in the database or the repository (`08-security.md`). The configuration is validated at startup (NFR-09).

## 7. Deployment

**Local** (`docker compose up`): `app` (role `all`, migrates at startup with `APP_ENV=local`), `postgres:18-alpine` (with an init script that creates the three roles), `axllent/mailpit`.

**Production (recommended, simple):**

```
Internet ──TLS──▶ Load balancer / proxy ──▶ /webhooks/*          → app (APP_ROLE=api) ×1–2
                          │              (other routes: private network only)
Products ──encrypted private network/TLS──▶ app (APP_ROLE=api)
                                       app (APP_ROLE=worker) ×1
                                       job (APP_ROLE=migrate) on every deployment
                                               │
                                     Managed PostgreSQL (PITR, encrypted backups)
```

- The same image for every role; only an environment variable changes.
- Deployment sequence: `migrate` → rolling `api` → `worker`. Migrations must be backwards compatible (NFR-09).
- **(rev. 2026-10)** Only `/webhooks/*` is publicly reachable over HTTPS. `/v1/**` and `/admin/v1/**` stay on the private network; `/admin/v1/**` is also restricted by IP at the load balancer.
- Scaling: more `api` instances if acceptance latency rises; more `worker` instances only if queue age grows with a healthy provider and below its rate limit (split `WORKER_PROVIDER_RPS`).

## 8. Repository structure

```
email-service/
├── AGENTS.md                     # summarized rules for AI agents
├── README.md                     # includes: data the provider receives, tested restore, operations
├── docker-compose.yml
├── .env.example
├── .github/workflows/ci.yml
├── docs/                         # ← source of truth
│   ├── adr/
│   ├── assets/                   # diagrams used by the README
│   └── audit/
├── contracts/
│   └── email-service.openapi.json
├── src/main/java/com/emailservice/{tenancy,templates,sending,provider,events,ratelimit,abuse,audit,maintenance,common}
├── src/main/resources/
│   ├── db/migration/             # Flyway V1__core.sql, V2__rls.sql, …
│   └── application.yaml
├── src/test/java/…               # unit + integration (Testcontainers with roles/RLS, WireMock) + ArchUnit
├── templates/                    # (rev. 2026-10-06) templates as code: {tenant}/{templateKey}/ (§9)
└── scripts/
    ├── init-db-roles.sql         # creates email_owner, email_app, email_system (local)
    ├── seed-local.sh             # tenant (SLUG, demo by default) + API keys + its templates; idempotent, --new-keys
    ├── simulate-event.sh         # (rev. 2026-10-14) simulated, signed Resend event, development only (docs/12)
    ├── publish-template.sh       # (rev. 2026-10-06) publishes a template folder (§9)
    └── smoke-test.sh             # end-to-end test send against Mailpit
```

## 9. Templates as code (rev. 2026-10-06)

Templates are versioned as code, in this repository or **(rev. 2026-10-14)** in the product's own one, and published with a script through the API. Nobody edits HTML in production by hand, and every change goes through review and CI.

**Structure** — one folder per template; in this repository, `templates/{tenant}/{templateKey}/`:

| File | Content |
|---|---|
| `template.json` | `key` (in this repository, equal to the folder name), `name`, `description`, `category`, `trackingEnabled`, `subject` (one subject per locale), `variablesSchema` (subset of `06` §3.3) and `previewVariables` (fictional sample data for the preview) |
| `{locale}.html` | HTML body; one per locale in `subject` (required) |
| `{locale}.txt` | Plain-text alternative (optional) |

Example: `templates/demo/password-reset/` (`es-CR` and `en`).

**Publishing** — `EMAIL_SERVICE_TEMPLATES_KEY=esk_... [EMAIL_SERVICE_URL=...] scripts/publish-template.sh path/to/the/folder`, or `... publish-template.sh {tenant} {templateKey}` for `templates/` in this repository. **(rev. 2026-10-14)** It works from any directory and any repository: the path is relative to the current directory, `EMAIL_SERVICE_URL` (by default `http://localhost:8080`; `BASE_URL` is accepted for compatibility) says which service to publish to, and the key determines the tenant. See `12-status-and-local-use.md`.

1. The key must belong to the tenant and have `templates:write` and `emails:read`; it is the operator key, never a product's (FR-33).
2. If the template does not exist, it creates it. If it exists with another category, it fails: changing the category requires a new template (AC-36.4).
3. It compares each locale with its published version (subject, HTML, text and schema) and skips the unchanged ones. Running the script again without changes creates no versions.
4. It creates a draft for each changed locale and previews it with `previewVariables`, with the same rules as a real send (schema, escaping, `allowedLinkHosts`).
5. Only if every preview passes does it publish. With a single changed locale it uses the individual publication; with several, the joint publication (ADR-0020): all locales in one transaction, so the `required` set of a multi-locale template can change. If anything fails, nothing is left published: the script deletes the drafts it created and exits with an error showing the `problem+json`.

It needs `curl` and `jq` (`jq` is declared in `mise.toml`). `scripts/seed-local.sh` publishes every template in `templates/{slug}/` of the tenant it creates.

**In CI**, `TemplatesAsCodeTest` runs each `template.json` through the same checks as the service on save, publication and preview. A broken template fails the build before it reaches the script.

**CI:** the workflow uses neither mise nor `jq`. `setup-java` installs Java, no job runs the scripts, and `TemplatesAsCodeTest` validates the templates in Java. `jq` is needed only to run the scripts locally (and GitHub's runners already include it).
