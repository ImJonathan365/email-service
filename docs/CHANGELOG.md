# Project changelog

Record of progress and decisions. Format: done / pending / decisions.

## 2026-10-25 — Documentation in English

- **Done:**
  - **`/docs` translated from Spanish to English:** the numbered documents, the ADRs, the audit and this changelog. IDs (FR, NFR, AC, ADR, OBJ, UC), section numbers and decisions did not change, so references such as `docs/05 §4` still point to the same place.
  - **File names in English** (`05-arquitectura.md` → `05-architecture.md`, `auditoria/` → `audit/`, ADR slugs), with every link in the repository updated.
  - **Language rule:** `AGENTS.md` and `docs/10` now say that the documentation is in English. The only Spanish left is localized content (the `es-CR` templates) and proper names.
  - **README:** rewritten as a product-neutral description of the service (what it is, how it works), with an HTML diagram of the flow.
  - **API examples:** `fromName` example "Café Aurora via Acme" instead of a Spanish shop name; the subject example of a template version in English. The OpenAPI contract was regenerated.

## 2026-10-15 — `v0.8.1`, gitleaks pinned and session handoff

- **Done:**
  - **Tag `v0.8.1`** on the merge of `dev` into `main` (PR #9): the stable version for using the service from other projects locally.
  - **CI:** gitleaks pinned to `8.30.1` (`GITLEAKS_VERSION`) and the action to the `v3.0.0` commit. The action's default version ignored `[[allowlists]]`, which is what made the first CI run of `feat/local-dev-integration` fail.
  - **`docs/12` revised so that work can be picked up without the chat history:**
    - how to pick it up;
    - H9 part A, ordered: audit, a simulated product in `examples/`, an end-to-end guide with real Resend, manifests, backups, checklist and integration;
    - H9 part B, with the decision each item depends on;
    - the Phase 0 audit with evidence;
    - the `.env` step for `MAIL_WEBHOOK_SIGNING_SECRET`.
- **Finding:** of the 150 ACs of the **Must** FRs, 28 have no test that names them. They may be covered under another name, not be verifiable with a test, or be missing their test. Reviewing them is the first item of part A (`12`). Until then, the Phase 0 criterion "Must FRs covered" stays **to be audited**, not done.
- **Pending:** H9; the owner's decisions are listed in `12`.

## 2026-10-14 — Local use from other projects (H9 paused)

- **Done** (branch `feat/local-dev-integration`, not a milestone of `09`):
  - **A stable Docker network:** `email-service-net` with a fixed name and the alias `email-service` for the app; `restart: unless-stopped` on the app, PostgreSQL and Mailpit.
  - **An idempotent seed:**
    - if the tenant exists, it changes nothing and issues no keys, and says so;
    - `--new-keys` issues a new pair;
    - `SLUG` chooses the tenant;
    - documented that `down -v` deletes the database.
  - **ADR-0021 (owner):** with `APP_ENV=local` and only then, URL variables accept `http`/`https` to `localhost` and `*.localhost`. No switch of its own: impossible in `staging` and `production`. Tests of both cases (`UrlPolicyTest`, `EmailAcceptanceTest`).
  - **`scripts/simulate-event.sh`:**
    - a Resend event signed with Svix and the local secret from `.env.example` (`MAIL_WEBHOOK_SIGNING_SECRET`, which compose passes to the app);
    - the same path as a real webhook;
    - `SimulateEventScriptTest` runs the script against the test service: states, global suppressions, a foreign signature rejected.
  - **`publish-template.sh`** accepts the path of a template folder and works from any repository with `EMAIL_SERVICE_URL` (`BASE_URL` still works). `templates/demo` stays as the example.
  - **`docs/12-status-and-local-use.md`:** status (milestones, H9 in parts A and B, Phase 0, pending decisions) and a local usage guide.
  - **gitleaks:** `.gitleaks.toml` exempts only the exact value of the fictional local secret; any other secret is still detected.
- **Pending:** H9, waiting for the owner's infrastructure decisions (`12`, part 1).

## 2026-10-13 — H8, part 2: batch sending (H8 complete)

- **Done:** `POST /v1/emails/batch` (FR-35).
  - **Limits:** up to `MAX_BATCH_ITEMS` (100) and `MAX_BATCH_BYTES` (1 MiB, `413`). Each item is a `POST /v1/emails` body plus a required `idempotencyKey`.
  - **Response:** `200` with `results[]` in order.
  - **Transaction:** a single one, with a savepoint per item. A rejected item rolls back only itself, its quota increment included, and a database failure rolls the whole batch back.
  - **Idempotency and rate limit per item:** once the limit is exhausted, the rest get `429`, except idempotent repeats, which return their original result (owner).
  - **Paused tenant:** `403` for the whole batch.
  - **Priority:** each message inherits its category's; a `NOTICE` batch does not overtake `SECURITY`.
  - **Anomalies:** each accepted item counts towards the volume anomaly (a batch can trigger the pause).
  - **`sendAt` per item.**
- **Tests:** independent items in order, the 100/101 and size limits, repeats inside and across batches, the limit exhausted mid-batch, a repeat after the limit ran out, pause, priority, `sendAt` per item, a full rollback on a database failure and a batch that triggers the volume anomaly.

## 2026-10-13 — H8, part 1: scheduled sending and cancellation

- **Done:**
  - **`sendAt`** (FR-31):
    - ISO-8601 with a zone, up to `SEND_AT_MAX_DAYS` (30) ahead;
    - up to `SEND_AT_PAST_TOLERANCE_SECONDS` (60, configurable) in the past counts as now (owner); beyond that, `422 SEND_AT_OUT_OF_RANGE`;
    - validated with the `Clock`; it sets `send_at` and `next_attempt_at`;
    - the version, the suppression and the quota are resolved on acceptance; the 23 h window starts at the first attempt;
    - it appears in the response, in idempotent repeats and in `GET /v1/emails/{id}`.
  - **`POST /v1/emails/{id}/cancel`** (FR-26, brought forward to H8 because of AC-31.3):
    - a row lock and a conditional `UPDATE` on `QUEUED`: it never cancels a message the worker has already claimed;
    - `404` for a foreign or non-existent id (RLS), `409` in another state or when cancelling twice;
    - it works with the tenant paused;
    - it removes the `x-sensitive` variables.
  - **Tests:**
    - exact boundaries with a fixed clock (60/61 s in the past, 30 days/30 days + 1 s);
    - version pinned on acceptance;
    - a scheduled message of a paused tenant: it stays `QUEUED` and goes out when it resumes;
    - a deterministic race with the claim (the claim wins) and a concurrent one with 20 messages and a consistent final state.

## 2026-10-13 — Time-dependent tests: deterministic

- **Cause of the CI failure** (PR #5, `AuthIpLimitTest:77`):
  - The per-IP `401` limiter of the shared context kept its counters between tests. Each test chose the IP at random among 250 and moved the clock to the minute after the real time, so two consecutive tests could land on the same IP and the same window.
  - The second one found the IP already saturated and got `429` on its first attempt, where it expected `401`.
  - Reproduced deterministically by fixing the same IP in every test: 5 of 6 fail on that line with the same message. A minute change mid-test produces another failure (on line 97, not 77), so it was not the cause.
- **Test fix:** each test builds its own authenticator, windows, prefix set and alerts on a clock fixed at mid-minute and mid-hour, which advances explicitly. The IPs come from a counter and do not repeat. It now also checks the exact `Retry-After` and the audit of the next hour.
- **Production:** what read the system time in window decisions moves to the `Clock`:
  - `MessageProcessor`: the 23 h window and the next attempt;
  - `ResendEmailSender`: `Retry-After` with an HTTP date;
  - `WebhookController`: the fallback time;
  - `AcceptanceRateLimiter`: windows and `Retry-After`;
  - `RateLimitAudit`: hourly deduplication by the clock's hour stored in the row.
- **Other tests audited and fixed:**
  - `RateLimitTest`: no `Thread.sleep` and no real time; a fixed clock, exact `Retry-After`s and a provoked window change;
  - `ResendWorkerTest`: a frozen clock, because the breaker's 59 s against 60 s depended on the test's duration;
  - `ObservabilityTest`: its own metrics and alerts, with no shared deduplication;
  - `WebhookTest`: a ±600 s window (the exact boundary is in the unit test with a fixed clock);
  - `ProviderRateLimiterTest`: the concurrency test no longer measures `System.nanoTime`, it assigns slots with time stopped;
  - `ResendEmailSenderTest`: an exact `Retry-After` with a fixed clock.
- **`MutableClock.fixAt`** allows fixing the shared clock; `reset()` returns it to the system time.
- **Time source** (docs/05): a decision uses a single source at both ends.
  - The points where the `Clock` and PostgreSQL's `now()` meet are analysed against a skew of seconds. The 23 h window stays below Resend's 24 h of idempotency with ~59 min of margin.
  - The acceptance → `SENT` time (NFR-19) is now computed in SQL (`sent_at - created_at`), because mixing sources biased a 5 s target.
- **Webhooks:** `SvixWebhookVerifierTest` checks the exact boundary in both directions (300 s accepted, 301 s rejected, in the past and in the future). The accepted-in-the-future case was missing.

## 2026-10-12 — H7, part 3: maintenance and observability (H7 complete)

- **Done:**
  - **Purges (FR-23)** in idempotent batches with an advisory lock, as worker tasks:
    - variables 7 days after a terminal or time-based final state;
    - idempotency after 24 h;
    - messages and events according to the tenant's retention; tags go in cascade;
    - `rendered_*` and orphans after 30 days, counters after 2 days;
    - `audit_log` after 365 days through a `SECURITY DEFINER` function (V5);
    - never `QUEUED`/`SENDING` or suppressions.
  - **Data-subject erasure (FR-29):**
    - across all tenants and in one transaction: messages, events and tags in cascade, `cc`/`bcc` cleaned, suppressions with only the hash;
    - audited with the hash and the reference; idempotent;
    - 2.2 s with the reference dataset (AC-29.3, limit 60 s).
  - **Health:** the `queue` (`DEGRADED` with HTTP 200) and `provider` (last 5 min, no ping) indicators.
  - **The missing AC-24.2 metrics:** states, queue, held, acceptance and provider latencies, attempts, bounce and complaint rates per tenant, purged rows.
  - **NFR-11 alerts** as `ERROR` lines with the `alert` field, plus a reference `ops/prometheus/alerts.yml`. The values and what to do with each one are in the README.
  - **Logs** with `tenantSlug` and `messageId` in the worker and the webhooks. A test verifies that a full send leaves no addresses or variables in the logs.
  - **New configuration:** `VARIABLES_RETENTION_DAYS`, `IDEMPOTENCY_RETENTION_HOURS`, `SENT_FINAL_AFTER_HOURS` and `QUEUE_AGE_ALERT_SECONDS`, validated.
  - **Default values test:** outside the test profile, the per-IP `401` limit is 30 (the owner's request).
  - **Clock going backwards:** the prefix set and the alert deduplication tolerate it.
- **Proposals pending the owner's confirmation:**
  - `DELIVERED` counts as time-based final just like `SENT` (AC-23.9);
  - `FAILED_RATE` requires at least 20 messages in the hour;
  - added alerts: `PROVIDER_CONFIGURATION` and `AUTH_FAILURE_SPIKE`.

## 2026-10-12 — H7, part 2: protection

- **Done:**
  - **Rate limit and quota per tenant** (FR-21):
    - a counter in PostgreSQL in the `INSERT`'s transaction; UTC minute and the tenant's calendar day;
    - `429 RATE_LIMITED` with `Retry-After`; nothing consumed on rejections or idempotent repeats; a `FAILED/SUPPRESSED` does consume;
    - limits applied without redeploying;
    - an aggregated audit per tenant, window and hour.
  - **Repeated `401`s per IP** (docs/08 §4, the owner's scheme):
    - prefixes of active keys in memory, refreshed every 60 s and when issuing or revoking;
    - above `AUTH_FAILURES_PER_MINUTE_PER_IP` (30), `429` without a database query only for missing, malformed or unknown-prefix keys;
    - a known prefix goes through full verification, so revocation is immediate;
    - an `AUTHENTICATION_FAILURES_BY_IP` audit per IP and hour; the metric `email_auth_key_lookups_total` to verify there was no query.
  - **Anomalies and automatic pause** (FR-34):
    - the `detectAnomalies` task every 5 min (volume against the hourly p95 of 14 days, bounces, complaints), with the volume minimums as a floor;
    - `anomalyOverrides` per tenant in the administration `PATCH`;
    - an audited pause, with a metric and an alert.
  - **Structured alerts** (`alert`, `alertSubject`) deduplicated for 15 min. The H5 pause because of an unverified domain uses the same `TENANT_PAUSED` alert.
  - **Worker periodic tasks** (`PeriodicTasks`), the base for the purges of part 3.
  - **Tag trigger test** with a multi-row `INSERT` and several statements in a transaction, plus the cascade delete (requested by the owner for H8).
- **Pending (part 3):** purges, data-subject erasure, the `queue`/`provider` indicators, the remaining metrics and alerts, and documenting the alerts in the README.

## 2026-10-12 — H7, part 1: queries

- **Done:**
  - **`GET /v1/emails/{id}`** (FR-19):
    - state, template and version, `locale`, recipients, attempts, `providerMessageId`, timestamps, `lastError` and the event history;
    - `404` for another tenant.
  - **Rendered content:** with `storeRenderedContent`, the worker stores a second render with the `x-sensitive` variables as `[REDACTED]` (AC-19.3).
  - **`GET /v1/emails`** (FR-20): filters `status`, `templateKey`, `to`, `tag`, `createdAfter` and `createdBefore`, with an opaque cursor.
  - **AC-20.2 measured:** `./gradlew benchmarkOperations`, with the reference dataset defined by the owner (4 M messages, 2.8 M in one tenant and 400 k in each of the other three), through the API.
    - p95 between 6 and 13 ms in the 14 cases, including a non-existent tag and an address without messages.
    - Finding: with RLS, PostgreSQL only uses an index for leakproof conditions. `lower()` and `@>` are not, and the `to` filter did a sequential scan (p95 990 ms).
    - V4 solves it with the generated column `to_email_lower`, the `email_message_tag` table (filled by a trigger and with RLS `FORCE`) and indexes with a tie-break by `id`.
  - **New tests:**
    - a catalogue test requires RLS `FORCE` on every table with `tenant_id`;
    - the `migrate` role test counts the migration files instead of a fixed number.
  - **Parameter errors:** a malformed query parameter responds `422`. A malformed path segment still responds `404`.
  - **`WebhookDatabaseFailureTest`** becomes `@Isolated`.
  - **docs/02 AC-20.2:** the dataset definition corrected.

## 2026-10-11 — Tests: a single shared Spring context

- **Done:**
  - **Spring Test contexts:** from 7 to 4 created; at the end of the suite only 1 stays open (Spring Test cache log).
    - `WorkerTest`, `ResendWorkerTest` and `WebhookDatabaseFailureTest` now use the common context. The shared test doubles (`SharedTestBeans`: a spy provider and a controllable clock) are programmed and restored; beans are no longer declared per class.
    - The `503` is tested with real database errors (a revoked privilege, a renamed table), without `@MockitoSpyBean`.
    - The ones that change global configuration stay separate, with `@DirtiesContext`: `SendingPolicyConfigTest` and the startups with `APP_ROLE=worker` and `APP_ENV=production`.
  - **Test pools:** 5 tenant and 3 system. Production stays at 10 and 4.
  - **Connections:** the test container goes back to the default 100.
- **Measurements:** the suite takes the same time (46 s before; 42–47 s after). What drops is the number of connections open at the same time.

## 2026-10-11 — H6: events and suppressions

- **Done:**
  - **`POST /webhooks/{provider}`** (FR-16):
    - `WebhookVerifier` in `provider`, with our own `SvixWebhookVerifier`: raw body, constant time, one or two secrets, a ±5 min window, verified with the example published by Svix;
    - responses `204`, `401`, `400`, `404`, `413`, and `503` only on a database error;
    - dedupe by `svix-id`;
    - rejections are counted and logged at most once per IP and minute.
  - **Events** (FR-17):
    - the full mapping of docs/02 §2.1 and states by precedence, including `DELIVERED → BOUNCED/COMPLAINED`, `email.failed` and `email.suppressed`;
    - a payload minimized by whitelist;
    - orphans stored with `204`.
  - **Suppressions** (FR-10, ADR-0012):
    - global ones from bounces, complaints and `email.suppressed`, in the event's transaction and independent of the state;
    - a mirror of `suppression.added/removed`.
  - **Suppression API** (FR-18) and `GET/DELETE /admin/v1/suppressions`, with an opaque cursor.
  - **ArchUnit:** no cycles between top-level modules; `events` does not depend on `sending`; `sending` and `events` only use `provider`'s ports.
  - **README:** registering the endpoint in Resend, rotating the secret and a temporary test with a tunnel that only exposes `/webhooks/*`.
- **The owner's decisions (2026-10-11):**
  - **Events arriving with the message in `QUEUED`/`SENDING`:** they are stored without touching the state and the worker applies them after its close to `SENT`.
  - **A message we closed as `FAILED` that the provider did process:** it stays `FAILED`, with a WARN and `email_event_after_failure_total`.
  - **A bounce or complaint with `cc`/`bcc`:** the `to` is not suppressed; the state is applied and the suppression arrives through `suppression.added`.
  - **Correlation:** first the `message_id` tag, then `data.email_id`; if they point to different messages, the event is left as an orphan.
  - **Repeated `POST` → `200`; non-existent `DELETE` → `404`.**
  - **`EmailAddresses` and `SuppressionHashes` move to `events`.**
- **Other changes:**
  - the tenant transaction manager moves from `common` to `tenancy`, to break a cycle that already existed;
  - the test container accepts 300 connections;
  - `.gitignore` ignores `.env.*` (except `.env.example`).
- **Pending:**
  - purging orphans after 30 days (a maintenance task);
  - the event history in `GET /v1/emails/{id}` (H7);
  - registering the real endpoint once the staging account exists (owner).

## 2026-10-10 — H5: real provider (Resend)

- **Done:**
  - **`ResendEmailSender`** (the JDK's HTTP client, no SDK):
    - `Idempotency-Key = message.id` on every attempt;
    - tags `message_id=<uuid>` and `{name: tag, value: "true"}`;
    - of the error, only its `name` is kept;
    - `RESEND_API_KEY` required with `MAIL_PROVIDER=resend`;
    - `RESEND_API_URL` must be `https` outside `local`.
  - **Classification** (docs/05 §4), with a test per code:
    - `2xx` → `SENT`;
    - `400`/`422` → permanent;
    - `409 invalid_idempotent_request` → `PROVIDER_IDEMPOTENCY_CONFLICT`;
    - the other `409`s, `429` (with `Retry-After`), `5xx`, timeout and network → transient;
    - new classes **Configuration** and **Tenant configuration**.
  - **`supportsIdempotency()`** in the `EmailSender` port: a warning at startup if it is `false` (SMTP).
  - **Provider protection:**
    - `WORKER_PROVIDER_RPS` per instance, no burst;
    - our own circuit breaker (AC-13.9): 5 failures → 60 s open → a single trial send;
    - while open, no messages are claimed, and those already claimed go back without an attempt.
  - **Configuration errors:**
    - **key** (`401`, a key-related `403`, `404`/`405`): they open the breaker immediately and use up no attempt; metric `email_provider_config_error_total{error}`;
    - **unverified domain** (`403 validation_error`): it pauses only that tenant (`pause_reason = PROVIDER_DOMAIN`, migration V3, audit `TENANT_PAUSED`).
  - **`POST /admin/v1/tenants/{slug}/resume-sending`** (brought forward from H7):
    - idempotent;
    - audited as `TENANT_RESUMED` with the previous reason and the note.
  - **Tests with WireMock,** with no real network or emails:
    - lost response (NFR-04): same key and same body, a single `SENT`;
    - limiter;
    - breaker;
    - a revoked key with 20 messages;
    - an unverified domain with another tenant that keeps sending.
  - **README:** staging steps (Resend team, domain with SPF/DKIM/DMARC, key, variables) and a manual test.
- **The owner's decisions (2026-10-10):**
  - the JDK's HTTP client;
  - only `invalid_idempotent_request` is permanent;
  - a configurable `RESEND_API_URL`;
  - `message_id` tags;
  - configuration errors without using up attempts, although the 23 h window still applies (`RETRY_WINDOW_EXCEEDED`);
  - a pause per tenant on an unverified domain.
- **Pending:** Resend webhooks (H6). The owner prepares the staging account and domain.

## 2026-10-10 — NFR-09: Gradle cache in the Docker build

- **Done:**
  - `Dockerfile`: a BuildKit cache mount for `/root/.gradle` in the dependency and `bootJar` steps. The final image does not change (563 MB).
  - NFR-09 adjusted: ≤ 90 s with the layer or Gradle cache available; ≤ 150 s from a fully cold clone (dominated by downloading dependencies).
- **Measurements:**
  - H4: 13 s with the layer cache; ≈ 100 s cold.
  - Now: 39 s with the layer cache invalidated and the cache mount available.
  - `--no-cache` also discards the cache mount, so it measures the fully cold case. With that day's slow network it took 717–1150 s.

## 2026-10-08 — H4: end-to-end sending

- **Done:**
  - **Validated configuration** for sending, the worker and retries (AC-13.3, AC-13.7, AC-09.4, AC-15.3).
  - **The `EmailSender` port** with `SmtpEmailSender` (Mailpit) and `NoopEmailSender`. The SMTP client only exists with `MAIL_PROVIDER=smtp`.
  - **Roles:** API and Swagger only with `APP_ROLE=api|all`; the worker only exposes Actuator.
  - **`POST /v1/emails`:**
    - idempotency before everything and safe under concurrency (AC-08.4);
    - the validations of FR-07 and FR-09;
    - a version pinned per locale, falling back to the tenant's;
    - the URL rule and the trial render;
    - global and per-tenant suppression;
    - the rate limit's insertion point (H7).
  - **Worker:**
    - a short claim by priority with `attempts++` and `lock_token`;
    - closing with fencing;
    - backoff with jitter and `Retry-After`;
    - a sweep of expired locks under an advisory lock;
    - purging `x-sensitive` on moving to `SENT` or a terminal state;
    - `email_time_to_sent_seconds` per category.
  - **`scripts/smoke-test.sh`:** an end-to-end send checked through Mailpit's API.
- **The owner's decisions (2026-10-07):**
  - the rate limit in H7, with the insertion point already in the transaction;
  - circuit breaker and `WORKER_PROVIDER_RPS` in H5;
  - `spring-boot-starter-mail` confined to `provider`;
  - `sendAt` → `422` until H8;
  - `GET /v1/emails/{id}` in H7;
  - purging `x-sensitive` in the `UPDATE` with fencing, and a time-to-`SENT` metric.
- **The agent's decisions, to be reviewed:**
  - `MAIL_PROVIDER=noop` does not start in `production`;
  - the optional `from` field (AC-09.2) is added to `07` §3, because it was missing from the table;
  - a message created directly as `FAILED`/`SUPPRESSED` stores no variables;
  - the attempt is given back if the tenant becomes held between claiming and sending;
  - the template endpoints have a body cap of 1 MiB.
- **Measured (NFR-09), clean clone with `cp .env.example .env && docker compose up --build`:**
  - with Docker's layer cache, 13 s until readiness;
  - without a cache, 89 s of build (almost all Gradle downloads) + 11 s of startup with a new volume, ≈ 100 s;
  - the seed and the smoke test pass.
  - **Pending the owner's decision:** the fully cold start exceeds the 90 s goal by ≈ 10 s.

## 2026-10-06 (night) — H3: templates

- **Done:**
  - **Hardened Handlebars.java 4.5.5 engine** (ADR-0011):
    - a whitelist scanner checks every `{{…}}` before compiling;
    - a closed helper registry and a loader that loads nothing;
    - only `MapValueResolver`;
    - rendering scans the source again;
    - context-aware escaping;
    - `formatDate`, `formatNumber` and `formatMoney` with the locale and the time zone. Nashorn is excluded.
  - **HTML linter with jsoup** (AC-04.7, AC-04.8), in XML mode so as not to lose misplaced tags.
  - **Our own validator** for the `variablesSchema` subset (`06` §3.3), with no JSON Schema dependency.
  - **The `/v1/templates` API:**
    - create, list, detail with content;
    - drafts per locale (create, edit, delete);
    - immutable publication that archives the previous version of the same locale;
    - preview with the URL rule of AC-07.8.
    - Isolation per tenant on every endpoint and an audited publication.
  - **Templates as code** (`05` §9): `templates/{tenant}/{templateKey}/`, `scripts/publish-template.sh`, `demo/password-reset` in `es-CR` and `en`, published by `seed-local.sh` and validated in CI by `TemplatesAsCodeTest`.
- **The owner's decisions (2026-10-06):**
  - `DELETE` of drafts;
  - `locale` and the URL rule in the preview;
  - `previewVariables` in `template.json`;
  - the script skips unchanged locales and the detail includes the content;
  - `jq`, declared in `mise.toml`.
- **Documentation adjustment:** the AC-37.5 example in `en` is `CRC1,500.00` (frozen output of JDK 25).
- **The owner's decision (2026-10-07):** joint publication (ADR-0020, AC-37.8): `POST /v1/templates/{key}/publish` publishes several locales in one transaction, all or nothing. AC-37.6 is validated on the resulting set. `publish-template.sh` uses it when several locales change. This removes the limitation of not being able to change `required`.
- **Outside H3** (they depend on sending, H4): pinning the version in the message, falling back to the tenant's locale on sending (AC-05.3/05.4, 37.2–37.4) and priority (AC-36.2/36.3).

## 2026-10-06 (afternoon) — H2: tenancy

- **Done:**
  - `application/problem+json` errors with the closed catalogue of `07` §11; `X-Request-Id` in every response; the source IP with `TRUSTED_PROXIES`.
  - Administration (FR-02): `X-Admin-Key` against `ADMIN_API_KEYS`; creating, listing, updating, suspending and reactivating tenants; the default values of AC-02.5.
  - API keys (FR-03, FR-33): issuing `esk_{env}_{prefix}_{secret}` (only the prefix and SHA-256 in the database), listing without secrets, idempotent revocation, scopes and CIDRs fixed at issue time.
  - Authentication of `/v1/**` (FR-01, FR-33): constant-time comparison, a uniform `401`, `403` by scope, CIDR or suspended tenant, `last_used_at` per minute and a tenant context (RLS) for the rest of the request. `@RequiresScope` is mandatory on every `/v1` handler.
  - Auditing (FR-22) in the same transaction as the action.
  - OpenAPI in two groups (AC-25.3): `contracts/email-service.openapi.json` (products) and `contracts/email-service-admin.openapi.json`.
  - `APP_ROLE=migrate` starts a minimal context (configuration and Flyway only).
  - `scripts/seed-local.sh`: the `demo` tenant and two keys.
- **Measured: the cost of RLS (ADR-0008)** with `./gradlew benchmarkRls`. PostgreSQL 18 in local Docker, 100,000 messages across 20 tenants, 5,000 transactions per case, two runs. The plan still uses `ix_msg_tenant_created`; the policy remains a single `One-Time Filter`.

  | Query (p50, µs per transaction) | `email_app` + `set_config` + RLS | `email_system` + `set_config` | `email_system` query only |
  |---|---|---|---|
  | The tenant's last 20 | 144–158 | 133–137 | 91–92 |
  | By tenant + id | 127–128 | 119–122 | 82–91 |

  - The extra `set_config` round trip costs ~30–45 µs (+40–45 % on such small queries).
  - Evaluating the policy alone costs +5–15 % in p50.
  - p95/p99 are noisy in this environment.
  - In total, +40–65 µs per transaction: ~3–5 % of the measured acceptance path (1.38 ms, `04` §2).
- **The owner's decisions (2026-10-06, after H2):**
  - ADR-0019 (Accepted): no API key cache (revocation on the next request, revised AC-03.4); the RLS cost criterion of ADR-0008 is measured over the whole transaction and is **not** triggered; `set_config` is not optimized.
  - Error catalogue: `405 METHOD_NOT_ALLOWED` and `415 UNSUPPORTED_MEDIA_TYPE` are added.
  - `401`s with unknown keys are counted as a metric (`email_auth_unauthenticated_total`), without writing to the database; an aggregated audit and a `401` rate limit in H7.
  - Two OpenAPI contracts (revised AC-25.2).
  - `ADMIN_API_KEYS` only for `APP_ROLE=api|all`, ≥ 32 characters (`05` §6).
  - Branches: `main` always deployable, `dev` for integration, `feat/*` per milestone (`AGENTS.md`).

## 2026-10-06 — H1: schema, roles and RLS

- **Decisions (owner, 2026-10-06):**
  - `email_system`'s `BYPASSRLS` is granted in `scripts/init-db-roles.sql` (it requires a superuser), not in `V2__rls.sql`. If the managed PostgreSQL does not allow it, the alternative of ADR-0008 will be used (a `TO email_system USING (true)` policy).
  - `tenant` gets RLS with `FORCE` (policy `id = app.tenant_id`). `email_app` only has `SELECT` on its own row; creating, modifying and deleting tenants is done only with `email_system`.
  - `email_system` has `SELECT/INSERT/UPDATE/DELETE` on every table, except `UPDATE`/`DELETE` on `audit_log`.
  - In `suppression`, `email_app` cannot read `source_tenant_id` or `source_message_id` (column-level permissions): a tenant does not learn which other tenant caused a global suppression.
  - The policies use `nullif(current_setting('app.tenant_id', true), '')::uuid`: on a reused connection, after a transaction with `set_config(..., true)`, the variable is `''` and not `NULL`, and `''::uuid` would fail instead of returning 0 rows.
- **Done:**
  - `docs/06` §1 and §5 updated with the above.
  - H1 (skeleton):
    - `APP_ROLE` with configuration validation at startup;
    - the two `DataSource`s and `set_config` per tenant transaction;
    - V1/V2 and the roles script;
    - health (liveness/readiness), Swagger and `contracts/email-service.openapi.json` with a diff test;
    - ArchUnit, Docker Compose and CI (build, tests, gitleaks).
  - **RSS measured (ADR-0009):** 300 MiB (306,896 KiB, ≈ 314 MB) idle with `-Xmx256m`, `APP_ROLE=all`, image `eclipse-temurin:25-jre`. It meets the ≤ 350 MB criterion.
- **Pending:** the rest of the configuration validations and the `provider`/`queue` indicators arrive with the milestone that uses them (H2–H7).

## 2026-10-05 (afternoon) — The owner's answers: H0 closed

- **Decisions:**
  - ADR-0008 to 0014, 0016 and 0017 → Accepted; ADR-0002 to 0007 → Superseded.
  - ADR-0015 (attachments) → **Rejected**: no product sends receipts. FR-30 → Won't; `email_attachment` is not created; UC-13 DEPRECATED.
  - FR-31 `sendAt` → Should (H8).
  - A new ADR-0018 (Accepted) and FR-37: templates per locale, `es-CR` by default and `en` available.
  - AC-09.5: `fromName` per request (Colmena sends on behalf of each business).
  - AC-10.1: `suppressionReason` in the response, so that the product uses an alternative address.
  - NFR-20: Costa Rica only (GDPR and CAN-SPAM do not apply); Colmena as the processor of its businesses' customers' data (products, debts).
  - International transfer: pending, it does not block development.
  - ADR-0009: confirmed that all services are Spring Boot and the frontends TypeScript.
- **Pending (does not block H1):**
  - hosting (CIDR, `BYPASSRLS`);
  - each product's privacy legal texts and Colmena's terms;
  - a legal consultation on the collection regulation for debt reminders.
- **Next:** Phase 0, milestone H1.

## 2026-10-05 — Independent audit and documentation review

- **Done:**
  - A full audit of `/docs`, the ADRs and `AGENTS.md`: 4 blocking findings, 17 serious, 22 minor and 3 improvements (`audit/2026-10-05-audit-report.md`).
  - Versions, prices, provider limits and regulations checked with cited sources.
  - The acceptance path measured (REST/JSON against binary, keep-alive, batch), and the queue in PostgreSQL.
  - Documents 01–10, the README and `AGENTS.md` rewritten with the fixes (marked **(rev. 2026-10)**).
  - A new document, `11-bulk-email.md`.
  - New ADRs 0008–0017 (status `Proposed`).
  - ADRs 0002–0007: only their *Status* line changes ("being superseded").
- **Proposed decisions** (details in `audit/decision-summary.md`):
  - RLS from the MVP with composite FKs (ADR-0008, supersedes 0007).
  - The stack ratified with a measurable justification (ADR-0009, supersedes 0006).
  - A queue with fencing, priority and idempotency towards the provider; at-least-once semantics (ADR-0010, supersedes 0002).
  - Hardened templates (ADR-0011, supersedes 0005).
  - Global/tenant suppression (ADR-0012).
  - Bulk email out for now, with a trigger (ADR-0013).
  - REST + keep-alive + batch (ADR-0014).
  - Bounded attachments in the MVP (ADR-0015).
  - Resend ratified with verified data (ADR-0016, supersedes 0003).
  - API keys with scopes and CIDR (ADR-0017, supersedes 0004).
- **Requirements:**
  - New: FR-29 to FR-36 and FR-40 to FR-54 (the latter Won't, in `11`); NFR-19 to NFR-21.
  - FR-28 becomes DEPRECATED (split into FR-30/31/32).
  - FR-23 moves up from Should to Must.
  - No ID renumbered.
- **Enum changes before the first migration** (there is no data):
  - `email_event.type`: `BOUNCED_HARD` → `BOUNCED`, `BOUNCED_SOFT` → `DELIVERY_DELAYED`; new `SENT`, `SUPPRESSED`, `OTHER`.
  - `failure_code`: `TENANT_SUSPENDED` and `TEMPLATE_NOT_PUBLISHED` become DEPRECATED; new `PROVIDER_FAILED`, `PROVIDER_SUPPRESSED`, `PROVIDER_IDEMPOTENCY_CONFLICT`, `RETRY_WINDOW_EXCEEDED` and `LOCK_EXPIRED`.
  - The `scheduled_lock` table removed from the design.
- **Contract with the products:** changes and migration notes in `07` §12.1. Since there is no version in production, they apply to `/v1` (NFR-18).
- **Pending:**
  - H0: closed in the afternoon entry (above).

## 2026-09-20 — Base documentation

- **Done:** the complete `/docs` (vision and scope, functional requirements with acceptance criteria, non-functional ones, stack, architecture, data model, internal API, security, roadmap, guide for AI agents) and ADR-0001 to ADR-0007.
- **Closed decisions:**
  - a single deployable artifact;
  - a queue in PostgreSQL with SKIP LOCKED;
  - Resend as the MVP provider behind `EmailSender`;
  - secrets outside the database;
  - immutable, logic-less versioned templates;
  - Java 25 + Spring Boot 4.1 + PostgreSQL 18;
  - multi-tenancy by a `tenant_id` column.
- **Pending:** start Phase 0 (milestone H1: skeleton + Docker Compose + CI).
