# 06 — Data model

> Revision 2026-10-05: RLS from the MVP (ADR-0008), composite foreign keys, a queue with `lock_token` and `priority` (ADR-0010), global/tenant suppression (ADR-0012), key scopes (ADR-0017), templates per locale (ADR-0018). Attachments out of the MVP (ADR-0015 Rejected): the `email_attachment` table stays documented but is **not created**. No migration has been applied yet (Phase 0 not started), so this document describes V1 directly.

## 1. Principles

- A database **owned exclusively** by the email-service. No product connects to it; everything goes through the API.
- Migrations with **Flyway**; an applied migration is **never** edited.
- Enums as `text` + `CHECK` (they evolve better than PostgreSQL `ENUM` types).
- Timestamps in `timestamptz` (UTC).
- Variably shaped data in `jsonb` (variables, payloads, metadata).
- `tenant_id` in **every** business table; it is part of the access indexes, the (composite) foreign keys and the RLS policies.
- **No secret in clear**: of API keys only the prefix and the hash are stored; the provider's credentials are not in the database.
- **(rev. 2026-10)** Three database roles (ADR-0008):

| Role | Use | Privileges |
|---|---|---|
| `email_owner` | Migrations (`APP_ROLE=migrate`) | Owns the schema; DDL. Never used by the running app |
| `email_app` | Tenant requests (API) | `SELECT/INSERT/UPDATE/DELETE` on business tables; **(rev. 2026-10-06)** only `SELECT` on `tenant` (its own row); on `suppression`, column-level `SELECT`/`INSERT` without `source_tenant_id` or `source_message_id`, and `DELETE`; only `SELECT`/`INSERT` on `audit_log`; **subject to RLS**; no DDL |
| `email_system` | Worker, sweeps, purging, webhooks, API key lookup, administration | `BYPASSRLS`; no DDL. **(rev. 2026-10-06)** `SELECT/INSERT/UPDATE/DELETE` on every table (including `tenant`), except `UPDATE`/`DELETE` on `audit_log`. Reachable only from the allowed packages (architecture test) |

**(rev. 2026-10-06)** The three roles are created by `scripts/init-db-roles.sql` (locally, as superuser when the container starts). `BYPASSRLS` is granted there and not in a migration, because `email_owner` is not a superuser. If the managed PostgreSQL does not allow `BYPASSRLS`, the alternative from ADR-0008 will be used: a `TO email_system USING (true)` policy per table.

## 2. Diagram

```
┌────────────────────────┐
│        tenant          │  (one product: pipemend, colmena, chinamo, payverica)
└───┬─────────┬──────┬───┘
    │1:N      │1:N   │1:N
┌───▼────┐ ┌──▼──────────┐ ┌───────▼────────┐
│api_key │ │  template   │ │  suppression   │  (scope TENANT with tenant_id;
└────────┘ └──┬──────────┘ └────────────────┘   scope GLOBAL without tenant_id)
              │1:N
     ┌────────▼─────────┐
     │ template_version │ (PUBLISHED = immutable)
     └────────┬─────────┘
              │ 1:N (version pinned on the send; composite FK with tenant_id)
     ┌────────▼───────────────────────────────┐
     │            email_message               │ ← it IS also the job queue
     │  status, priority, attempts,           │
     │  next_attempt_at, lock_token,          │
     │  lock_expires_at, provider_message_id  │
     └───┬───────────────────┬────────────────┘
         │1:N                │1:N
┌────────▼─────────┐  ┌──────▼───────────┐   ┌───────────────┐   ┌────────────────────┐
│   email_event    │  │ email_attachment │   │   audit_log   │   │ rate_limit_counter │
└──────────────────┘  └──────────────────┘   └───────────────┘   └────────────────────┘
```

Nine tables in the MVP: `tenant`, `api_key`, `template`, `template_version`, `email_message`, `email_event`, `suppression`, `audit_log` and `rate_limit_counter` (`email_attachment` only as a reference design). **(rev. 2026-10)** `scheduled_lock` is removed: scheduled tasks use `pg_try_advisory_xact_lock` (see `05` §5).

## 3. Tables

### 3.1 `tenant` — source product

| Column | Type | Notes |
|---|---|---|
| `id` | `uuid` PK | |
| `slug` | `text` UNIQUE | `colmena` |
| `name` | `text` | |
| `status` | `text` CHECK (`ACTIVE`,`SUSPENDED`) | |
| `from_email` / `from_name` | `text` | Default sender |
| `reply_to` | `text` NULL | |
| `allowed_from_domains` | `text[]` | Domains valid as sender |
| `allowed_link_hosts` | `text[]` | **(rev. 2026-10)** Hosts valid in URL variables (AC-07.8) |
| `locale` | `text` | **(rev. 2026-10)** BCP-47, default `es-CR` |
| `timezone` | `text` | **(rev. 2026-10)** IANA, default `America/Costa_Rica` |
| `rate_limit_per_minute` | `int` | Default 60 |
| `daily_quota` | `int` | **(rev. 2026-10)** Default 1,000 |
| `retention_days` | `int` | Default 90 |
| `store_rendered_content` | `boolean` | Default `false` (privacy) |
| `sending_paused_at` | `timestamptz` NULL | **(rev. 2026-10)** Pause because of an anomaly (FR-34) |
| `pause_reason` | `text` NULL CHECK (`VOLUME_ANOMALY`,`BOUNCE_RATE`,`COMPLAINT_RATE`,`MANUAL`,`PROVIDER_DOMAIN`) | (rev. 2026-10-10) `PROVIDER_DOMAIN` (V3): the provider rejected the sending domain as unverified; set by the worker and lifted by an admin with `resume-sending` |
| `anomaly_overrides` | `jsonb` NULL | **(rev. 2026-10)** The tenant's own thresholds (AC-34.5) |
| `created_at` / `updated_at` | `timestamptz` | |

### 3.2 `api_key` — a tenant's credential

| Column | Type | Notes |
|---|---|---|
| `id` | `uuid` PK | |
| `tenant_id` | `uuid` FK | |
| `name` | `text` | "production", "rotation 2026-09" |
| `key_prefix` | `text` UNIQUE | **(rev. 2026-10)** `esk_{env}_{8 Base62}`; allows lookups without exposing the secret |
| `key_hash` | `bytea` | SHA-256 of the secret (32 bytes) |
| `scopes` | `text[]` | **(rev. 2026-10)** ⊆ {`emails:send`,`emails:read`,`templates:write`,`suppressions:write`} |
| `allowed_cidrs` | `cidr[]` | **(rev. 2026-10)** Empty = any origin |
| `status` | `text` CHECK (`ACTIVE`,`REVOKED`) | |
| `expires_at` | `timestamptz` NULL | |
| `last_used_at` | `timestamptz` NULL | Minute precision |
| `created_at` / `revoked_at` | `timestamptz` | |

### 3.3 `template` and `template_version`

`template`: `id`, `tenant_id`, `key` (kebab-case slug), `name`, `description`, **`category`** (`SECURITY`,`TRANSACTIONAL`,`NOTICE`), **`tracking_enabled`** (`boolean`, default `false`; only `true` if `category = NOTICE`), `status` (`ACTIVE`,`ARCHIVED`), `created_at`, `updated_at`. `UNIQUE (tenant_id, key)` and `UNIQUE (tenant_id, id)` (for the composite FKs).

`template_version`:

| Column | Type | Notes |
|---|---|---|
| `id` | `uuid` PK | |
| `template_id` | `uuid` | **(rev. 2026-10)** Composite FK `(tenant_id, template_id) → template(tenant_id, id)` |
| `tenant_id` | `uuid` | No longer an unchecked copy: the composite FK ties it to the template's |
| `version` | `int` | Sequential from 1. `UNIQUE (template_id, version)` |
| `subject_template` | `text` | Handlebars, no HTML escaping |
| `html_template` | `text` | Handlebars, HTML escaping; ≤ 256 KB |
| `text_template` | `text` NULL | Plain-text alternative, no HTML escaping |
| `variables_schema` | `jsonb` NULL | Required to publish; subset below |
| `locale` | `text` | **(rev. 2026-10, ADR-0018)** ∈ `SUPPORTED_LOCALES` (`es-CR`, `en`). Partial unique index `(template_id, locale) WHERE status = 'PUBLISHED'` |
| `status` | `text` CHECK (`DRAFT`,`PUBLISHED`,`ARCHIVED`) | `PUBLISHED` is immutable (a trigger rejects content `UPDATE`s) |
| `created_at` / `published_at` | `timestamptz` | |

**(rev. 2026-10)** JSON Schema subset accepted in `variables_schema`: a root object with `required` (array) and `properties`. Each property accepts `type` (`string`, `integer`, `number`, `boolean`, `array`, `object`), `format` (`uri`, `email`, `date-time`), `maxLength` (≤ 2,000; default 500), `maxItems` (≤ 500; default 100), `items` and `properties` (nesting ≤ 3 levels) and `x-sensitive` (`boolean`). Any other keyword → `422 VALIDATION_ERROR` on save.

### 3.4 `email_message` — a send **and** a queued job

| Column | Type | Notes |
|---|---|---|
| `id` | `uuid` PK | Public identifier of the send (UUIDv7); also the `Idempotency-Key` towards the provider |
| `tenant_id` | `uuid` FK | |
| `idempotency_key` | `text` NULL | `UNIQUE (tenant_id, idempotency_key)`; set to `NULL` after 24 h |
| `request_hash` | `char(64)` NULL | SHA-256 of the canonical payload (AC-08.2); set to `NULL` together with the key |
| `template_id` / `template_version_id` | `uuid` | **(rev. 2026-10)** Composite FKs with `tenant_id`; version **pinned** on acceptance |
| `category` | `text` | **(rev. 2026-10)** Copy of the template's category |
| `locale` | `text` | **(rev. 2026-10, ADR-0018)** Effective locale of the pinned version |
| `priority` | `smallint` | **(rev. 2026-10)** 0 `SECURITY`, 1 `TRANSACTIONAL`, 2 `NOTICE` |
| `to_email` / `to_name` | `text` | One main recipient |
| `to_email_lower` | generated `text` (`lower(to_email)`, `STORED`) | **(rev. 2026-10-12, V4)** To filter by recipient with a text equality (see the V4 note below) |
| `cc` / `bcc` | `text[]` NULL | Max. 5 each |
| `from_email` / `from_name` / `reply_to` | `text` | Resolved on acceptance; `from_name` can come from the request (AC-09.5) |
| `variables` | `jsonb` | Needed to render and retry; purged per AC-23.5 |
| `variables_purged_at` | `timestamptz` NULL | **(rev. 2026-10)** |
| `rendered_subject` / `rendered_html` / `rendered_text` | `text` NULL | Only if `store_rendered_content`; with `x-sensitive` values redacted |
| `status` | `text` CHECK (`QUEUED`,`SENDING`,`SENT`,`DELIVERED`,`BOUNCED`,`COMPLAINED`,`FAILED`,`CANCELED`) | See `02` §2 |
| `attempts` | `int` | Default 0; **(rev. 2026-10)** incremented when the message is **claimed** |
| `first_attempt_at` | `timestamptz` NULL | **(rev. 2026-10)** For the window of AC-13.7 |
| `next_attempt_at` | `timestamptz` | The moment from which it is eligible (also `sendAt`) |
| `send_at` | `timestamptz` NULL | **(rev. 2026-10)** The value the client asked for (FR-31), informational only |
| `lock_token` | `uuid` NULL | **(rev. 2026-10)** Fencing token of the current claim |
| `lock_expires_at` | `timestamptz` NULL | Protection against a worker crash |
| `locked_by` | `text` NULL | Instance identifier (diagnostics) |
| `provider` | `text` NULL | `resend`, `smtp`, … |
| `provider_message_id` | `text` NULL | Correlation with webhooks |
| `failure_code` / `failure_detail` | `text` NULL | See §4; `failure_detail` ≤ 500 characters, with no personal data |
| `attempt_log` | `jsonb` NULL | Last 10 attempts: date, code, message |
| `tags` | `text[]` NULL | For filtering (`signup`, `invoice`); ASCII `[a-z0-9_-]`, ≤ 10, ≤ 64 characters |
| `metadata` | `jsonb` NULL | Opaque data from the product (max. 4 KB) |
| `tracking_enabled` | `boolean` | **(rev. 2026-10)** Copy of the template's |
| `first_opened_at` / `first_clicked_at` | `timestamptz` NULL | |
| `open_count` / `click_count` | `int` | Default 0 |
| `created_at` / `updated_at` / `sent_at` / `delivered_at` / `finalized_at` | `timestamptz` | **(rev. 2026-10)** `finalized_at` = the moment it reached a terminal state (for purging) |

Indexes:

```sql
CREATE INDEX ix_msg_queue ON email_message (priority, next_attempt_at)
    WHERE status = 'QUEUED';                              -- the queue (rev. 2026-10: with priority)
CREATE INDEX ix_msg_stuck ON email_message (lock_expires_at)
    WHERE status = 'SENDING';                             -- lock sweep
CREATE INDEX ix_msg_tenant_created ON email_message (tenant_id, created_at DESC);
CREATE INDEX ix_msg_tenant_status ON email_message (tenant_id, status, created_at DESC);
CREATE INDEX ix_msg_tenant_to ON email_message (tenant_id, lower(to_email), created_at DESC);   -- (rev. 2026-10-12) V4: (tenant_id, to_email_lower, created_at DESC, id DESC)
CREATE INDEX ix_msg_to_global ON email_message (lower(to_email));   -- (rev. 2026-10) data-subject erasure (FR-29); V4: (to_email_lower)
CREATE INDEX ix_msg_purge ON email_message (finalized_at)
    WHERE variables_purged_at IS NULL AND finalized_at IS NOT NULL; -- (rev. 2026-10) variable purging
CREATE UNIQUE INDEX uq_msg_provider_id ON email_message (provider, provider_message_id)
    WHERE provider_message_id IS NOT NULL;                -- correlation with webhooks
CREATE UNIQUE INDEX uq_msg_idempotency ON email_message (tenant_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;
```

**(rev. 2026-10-12) Listing indexes (V4, AC-20.2).**
- **Why they change:** with RLS, PostgreSQL only uses an index for leakproof conditions. Equalities and comparisons of `text`, `uuid` and `timestamptz` are; `lower()` and the array operator `@>` are not.
- **Measurement:** with the reference dataset, the `to` filter on the `lower(to_email)` index did a sequential scan (p95 990 ms). Both filters become text equalities:
  - **`to`:** the generated column `to_email_lower`;
  - **`tag`:** the `email_message_tag` table.
- **Tie-break by `id`:** the `(tenant_id, …, created_at DESC, id DESC)` indexes include `id`, which cursor pagination uses to break ties.
- **Rule for the future:** a new filter in the tenant API must be expressed with leakproof comparisons over indexed columns.

### 3.4.1 `email_message_tag` — tag index (rev. 2026-10-12, V4)

| Column | Type | Notes |
|---|---|---|
| `tenant_id`, `message_id` | `uuid` | Composite FK to `email_message (tenant_id, id)` `ON DELETE CASCADE`: it disappears with the message (purge, data-subject erasure) |
| `tag` | `text` | One of the message's `tags` |
| `created_at` | `timestamptz` | Copy of `email_message.created_at`, to sort from the index |

- **Key and index:** PK `(tenant_id, message_id, tag)` and index `(tenant_id, tag, created_at DESC, message_id DESC)`.
- **Writing:** an `AFTER INSERT` trigger on `email_message` writes the rows in the same transaction. Tags do not change after the message is accepted.
- **Security:** RLS `FORCE` with the same `tenant_isolation` policy; `email_app` and `email_system` have `SELECT`, `INSERT` and `DELETE`.
- **Guaranteed by a test:** a catalogue test requires RLS `FORCE` on every table with a `tenant_id` column.

### 3.5 `email_attachment` — NOT CREATED IN THE MVP (FR-30 Won't, ADR-0015 Rejected)

Reference design in case a real use case appears:

| Column | Type | Notes |
|---|---|---|
| `id` | `uuid` PK | |
| `tenant_id` | `uuid` | Composite FK `(tenant_id, message_id) → email_message(tenant_id, id)` `ON DELETE CASCADE` |
| `message_id` | `uuid` | |
| `position` | `smallint` | Order (determinism, AC-12.7) |
| `filename` | `text` | ≤ 100 characters |
| `content_type` | `text` CHECK (`application/pdf`,`application/xml`,`text/xml`) | |
| `size_bytes` | `int` | |
| `sha256` | `char(64)` | Part of the `request_hash` |
| `content` | `bytea` NULL | Set to `NULL` (with `content_purged_at`) when the message reaches `SENT` or a terminal state |
| `content_purged_at` | `timestamptz` NULL | |

The row (without content) is kept until the message is purged, so that `GET /v1/emails/{id}` keeps showing the name, type, size and `sha256`.

### 3.6 `email_event` — provider events

| Column | Type | Notes |
|---|---|---|
| `id` | `uuid` PK | |
| `tenant_id` | `uuid` NULL | `null` if the event is an orphan |
| `message_id` | `uuid` NULL | `null` if it could not be correlated; composite FK with `tenant_id` when both exist |
| `provider` | `text` | |
| `provider_event_id` | `text` | `svix-id` in Resend. `UNIQUE (provider, provider_event_id)` → dedupe |
| `type` | `text` CHECK (`SENT`,`DELIVERED`,`DELIVERY_DELAYED`,`BOUNCED`,`COMPLAINED`,`OPENED`,`CLICKED`,`FAILED`,`SUPPRESSED`,`OTHER`) | **(rev. 2026-10)** `BOUNCED_HARD` → `BOUNCED`; `BOUNCED_SOFT` → `DELIVERY_DELAYED` (renamed before the first migration; see `02` §2.1) |
| `provider_type` | `text` | **(rev. 2026-10)** Original type (`email.bounced`…) |
| `occurred_at` | `timestamptz` | According to the provider |
| `received_at` | `timestamptz` | Default `now()` |
| `payload` | `jsonb` | **(rev. 2026-10)** Minimized (AC-17.1). **(rev. 2026-10-11)** Field whitelist in `05` §4 (H6); never addresses, subject, headers, IP, user agent or the query of clicks |

### 3.7 `suppression` — blocked addresses (rev. 2026-10, ADR-0012)

| Column | Type | Notes |
|---|---|---|
| `id` | `uuid` PK | |
| `scope` | `text` CHECK (`GLOBAL`,`TENANT`) | |
| `tenant_id` | `uuid` NULL | Required if `scope = TENANT`; `NULL` if `GLOBAL` (CHECK) |
| `email` | `text` NULL | Normalized to lowercase; `NULL` after a data-subject erasure (FR-29) |
| `email_hash` | `bytea` | HMAC-SHA256(`SUPPRESSION_HASH_KEY`, normalized email); lookup key |
| `reason` | `text` CHECK (`HARD_BOUNCE`,`COMPLAINT`,`PROVIDER`,`MANUAL`) | `MANUAL` ⇒ `TENANT`; the rest ⇒ `GLOBAL` (CHECK) |
| `source_tenant_id` | `uuid` NULL | Tenant whose send caused it (diagnostics, visible only to an admin) |
| `source_message_id` | `uuid` NULL | |
| `note` | `text` NULL | |
| `created_at` | `timestamptz` | |

Uniqueness: `UNIQUE (email_hash) WHERE scope = 'GLOBAL'` and `UNIQUE (tenant_id, email_hash) WHERE scope = 'TENANT'`. RLS: the `email_app` role sees its tenant's `TENANT` rows and the `GLOBAL` ones (read-only; a separate policy for `SELECT`).

### 3.8 `audit_log`

`id`, `tenant_id` NULL, `actor_type` (`ADMIN`,`API_KEY`,`SYSTEM`), `actor_id` NULL (**(rev. 2026-10-06)** the API key's id, or `admin:<12 hex>` = the SHA-256 fingerprint of the admin credential used; never the credential), `action` (e.g. `API_KEY_REVOKED`, `TENANT_PAUSED`, `DATA_SUBJECT_ERASED`), `resource_type`, `resource_id`, `ip`, `request_id`, `metadata` `jsonb`, `created_at`. Index `(tenant_id, created_at DESC)`. Inserts only: `email_app` and `email_system` have `INSERT` and `SELECT`, not `UPDATE`/`DELETE`; the purge is run by a `SECURITY DEFINER` function owned by `email_owner`.

### 3.9 `rate_limit_counter`

`tenant_id`, `window_kind` (`MINUTE`,`DAY`), `window_start` (`timestamptz`), `count` (`int`). PK `(tenant_id, window_kind, window_start)`. Incremented with `INSERT … ON CONFLICT DO UPDATE SET count = count + 1 RETURNING count`, **(rev. 2026-10)** inside the transaction of the message's `INSERT` (AC-21.5); old windows are purged daily. The `DAY` window is computed in the tenant's time zone.

### 3.10 `scheduled_lock` — DEPRECATED (rev. 2026-10)

Replaced by `pg_try_advisory_xact_lock(hashtext('<task name>'))`. Not created.

## 4. Failure codes (`failure_code`)

| Code | Class | Retries | Status |
|---|---|---|---|
| `SUPPRESSED` | Blocking | No | Current |
| `TENANT_SUSPENDED` | — | — | **DEPRECATED** (rev. 2026-10): a suspended tenant holds its queue (AC-02.3); it remains only as an HTTP 403 error |
| `RENDER_ERROR` | Local | No | Current |
| `TEMPLATE_NOT_PUBLISHED` | — | — | **DEPRECATED** (rev. 2026-10): it cannot happen in the worker (the version is pinned on acceptance); it remains only as an HTTP 422 error |
| `PROVIDER_REJECTED` | Permanent | No | Current |
| `INVALID_RECIPIENT` | Permanent | No | Current |
| `PROVIDER_FAILED` | Permanent (`email.failed`) | No | **New** |
| `PROVIDER_SUPPRESSED` | Permanent (`email.suppressed`) | No | **New** |
| `PROVIDER_IDEMPOTENCY_CONFLICT` | Permanent (`409 invalid_idempotent_request`: same key, different body) | No | **New**. (rev. 2026-10-10) The other `409`s (`concurrent_idempotent_requests`, `resource_locked`) are transient and remain as `PROVIDER_UNAVAILABLE` |
| `PROVIDER_CONFIGURATION` | Configuration (`401`, an API-key `403`, `404`/`405`) | Yes, without using up an attempt | **New** (rev. 2026-10-10), only in `attempt_log`; opens the circuit breaker |
| `PROVIDER_DOMAIN_NOT_VERIFIED` | Tenant configuration (`403 validation_error`) | Yes, without using up an attempt, after `resume-sending` | **New** (rev. 2026-10-10), only in `attempt_log`; pauses the tenant (`PROVIDER_DOMAIN`) |
| `TENANT_HELD` / `CIRCUIT_OPEN` / `WORKER_STOPPED` | Held | Yes, without using up an attempt | **New** (rev. 2026-10-10), only in `attempt_log`: tenant held, breaker open or worker stopped between claiming and sending |
| `PROVIDER_UNAVAILABLE` | Transient | Yes | Current (in `attempt_log`) |
| `PROVIDER_RATE_LIMITED` | Transient | Yes | Current (in `attempt_log`) |
| `PROVIDER_TIMEOUT` | Transient | Yes | Current (in `attempt_log`) |
| `LOCK_EXPIRED` | Transient (reclaimed by the sweep) | Yes | **New** (in `attempt_log`) |
| `MAX_ATTEMPTS_EXCEEDED` | Final | No | Current |
| `RETRY_WINDOW_EXCEEDED` | Final | No | **New** (AC-13.7) |
| `CANCELED_BY_CLIENT` | Final | No | Current |

## 5. Reference DDL (excerpt: `V1__core.sql`, `V2__rls.sql`)

```sql
-- V1__core.sql (run by email_owner)
CREATE TABLE tenant (
    id                     uuid PRIMARY KEY,
    slug                   text        NOT NULL UNIQUE CHECK (slug ~ '^[a-z0-9-]{2,40}$'),
    name                   text        NOT NULL,
    status                 text        NOT NULL CHECK (status IN ('ACTIVE','SUSPENDED')),
    from_email             text        NOT NULL,
    from_name              text        NOT NULL,
    reply_to               text,
    allowed_from_domains   text[]      NOT NULL DEFAULT '{}',
    allowed_link_hosts     text[]      NOT NULL DEFAULT '{}',
    locale                 text        NOT NULL DEFAULT 'es-CR',
    timezone               text        NOT NULL DEFAULT 'America/Costa_Rica',
    rate_limit_per_minute  int         NOT NULL DEFAULT 60   CHECK (rate_limit_per_minute > 0),
    daily_quota            int         NOT NULL DEFAULT 1000 CHECK (daily_quota > 0),
    retention_days         int         NOT NULL DEFAULT 90   CHECK (retention_days BETWEEN 7 AND 400),
    store_rendered_content boolean     NOT NULL DEFAULT false,
    sending_paused_at      timestamptz,
    pause_reason           text CHECK (pause_reason IN ('VOLUME_ANOMALY','BOUNCE_RATE','COMPLAINT_RATE','MANUAL')),
                           -- V3 adds 'PROVIDER_DOMAIN' (rev. 2026-10-10)
    anomaly_overrides      jsonb,
    created_at             timestamptz NOT NULL DEFAULT now(),
    updated_at             timestamptz NOT NULL DEFAULT now(),
    CHECK ((sending_paused_at IS NULL) = (pause_reason IS NULL))
);

CREATE TABLE api_key (
    id            uuid PRIMARY KEY,
    tenant_id     uuid        NOT NULL REFERENCES tenant(id) ON DELETE CASCADE,
    name          text        NOT NULL,
    key_prefix    text        NOT NULL UNIQUE,
    key_hash      bytea       NOT NULL CHECK (length(key_hash) = 32),
    scopes        text[]      NOT NULL DEFAULT '{emails:send,emails:read}'
                  CHECK (scopes <@ ARRAY['emails:send','emails:read','templates:write','suppressions:write']),
    allowed_cidrs cidr[]      NOT NULL DEFAULT '{}',
    status        text        NOT NULL CHECK (status IN ('ACTIVE','REVOKED')),
    expires_at    timestamptz,
    last_used_at  timestamptz,
    created_at    timestamptz NOT NULL DEFAULT now(),
    revoked_at    timestamptz,
    CHECK ((status = 'REVOKED') = (revoked_at IS NOT NULL))
);
CREATE INDEX ix_api_key_tenant ON api_key (tenant_id, status);

CREATE TABLE template (
    id               uuid PRIMARY KEY,
    tenant_id        uuid NOT NULL REFERENCES tenant(id),
    key              text NOT NULL CHECK (key ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
    name             text NOT NULL,
    description      text,
    category         text NOT NULL CHECK (category IN ('SECURITY','TRANSACTIONAL','NOTICE')),
    tracking_enabled boolean NOT NULL DEFAULT false,
    status           text NOT NULL CHECK (status IN ('ACTIVE','ARCHIVED')),
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, key),
    UNIQUE (tenant_id, id),
    CHECK (NOT tracking_enabled OR category = 'NOTICE')
);

CREATE TABLE template_version (
    id                uuid PRIMARY KEY,
    tenant_id         uuid NOT NULL,
    template_id       uuid NOT NULL,
    version           int  NOT NULL CHECK (version >= 1),
    subject_template  text NOT NULL CHECK (length(subject_template) <= 500),
    html_template     text NOT NULL CHECK (octet_length(html_template) <= 262144),
    text_template     text,
    variables_schema  jsonb,
    locale            text NOT NULL DEFAULT 'es-CR' CHECK (locale IN ('es-CR','en')),
    status            text NOT NULL CHECK (status IN ('DRAFT','PUBLISHED','ARCHIVED')),
    created_at        timestamptz NOT NULL DEFAULT now(),
    published_at      timestamptz,
    UNIQUE (template_id, version),
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, template_id) REFERENCES template (tenant_id, id),
    CHECK (status = 'DRAFT' OR (variables_schema IS NOT NULL AND published_at IS NOT NULL))
);
CREATE UNIQUE INDEX uq_tv_published_locale ON template_version (template_id, locale)
    WHERE status = 'PUBLISHED';                           -- (rev. 2026-10) one published per locale

CREATE TABLE email_message (
    id                   uuid PRIMARY KEY,
    tenant_id            uuid        NOT NULL REFERENCES tenant(id),
    idempotency_key      text CHECK (length(idempotency_key) <= 256),
    request_hash         char(64),
    template_id          uuid        NOT NULL,
    template_version_id  uuid        NOT NULL,
    category             text        NOT NULL CHECK (category IN ('SECURITY','TRANSACTIONAL','NOTICE')),
    priority             smallint    NOT NULL CHECK (priority BETWEEN 0 AND 2),
    locale               text        NOT NULL DEFAULT 'es-CR',
    to_email             text        NOT NULL,
    to_name              text,
    cc                   text[] CHECK (cardinality(cc) <= 5),
    bcc                  text[] CHECK (cardinality(bcc) <= 5),
    from_email           text        NOT NULL,
    from_name            text        NOT NULL,
    reply_to             text,
    variables            jsonb       NOT NULL DEFAULT '{}'::jsonb,
    variables_purged_at  timestamptz,
    rendered_subject     text,
    rendered_html        text,
    rendered_text        text,
    status               text        NOT NULL CHECK (status IN
                            ('QUEUED','SENDING','SENT','DELIVERED','BOUNCED','COMPLAINED','FAILED','CANCELED')),
    attempts             int         NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    first_attempt_at     timestamptz,
    next_attempt_at      timestamptz NOT NULL DEFAULT now(),
    send_at              timestamptz,
    lock_token           uuid,
    lock_expires_at      timestamptz,
    locked_by            text,
    provider             text,
    provider_message_id  text,
    failure_code         text,
    failure_detail       text CHECK (length(failure_detail) <= 500),
    attempt_log          jsonb,
    tags                 text[] CHECK (cardinality(tags) <= 10),
    metadata             jsonb CHECK (pg_column_size(metadata) <= 4096),
    tracking_enabled     boolean     NOT NULL DEFAULT false,
    first_opened_at      timestamptz,
    first_clicked_at     timestamptz,
    open_count           int         NOT NULL DEFAULT 0,
    click_count          int         NOT NULL DEFAULT 0,
    created_at           timestamptz NOT NULL DEFAULT now(),
    updated_at           timestamptz NOT NULL DEFAULT now(),
    sent_at              timestamptz,
    delivered_at         timestamptz,
    finalized_at         timestamptz,
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, template_id)         REFERENCES template (tenant_id, id),
    FOREIGN KEY (tenant_id, template_version_id) REFERENCES template_version (tenant_id, id),
    -- Queue invariants (rev. 2026-10)
    CHECK ((status = 'SENDING') = (lock_token IS NOT NULL AND lock_expires_at IS NOT NULL)),
    CHECK (status NOT IN ('SENT','DELIVERED','BOUNCED','COMPLAINED') OR provider_message_id IS NOT NULL
           OR provider = 'smtp'),
    CHECK (status <> 'FAILED' OR failure_code IS NOT NULL)
);
```

```sql
-- V2__rls.sql (run by email_owner) — pattern for every table with tenant_id
-- (rev. 2026-10-06) nullif(): on a reused connection, after a transaction with
-- set_config(..., true), the variable is '' (not NULL) and ''::uuid would fail instead of returning 0 rows.
ALTER TABLE email_message ENABLE ROW LEVEL SECURITY;
ALTER TABLE email_message FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON email_message
    USING      (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
-- Same for api_key, template, template_version, email_event,
-- rate_limit_counter and audit_log.

-- (rev. 2026-10-06) tenant: each tenant sees only its own row; nobody writes it except email_system
ALTER TABLE tenant ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenant FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_self ON tenant FOR SELECT
    USING (id = nullif(current_setting('app.tenant_id', true), '')::uuid);

-- suppression: the tenant sees its own and the global ones; it writes only its own
ALTER TABLE suppression ENABLE ROW LEVEL SECURITY;
ALTER TABLE suppression FORCE  ROW LEVEL SECURITY;
CREATE POLICY supp_read ON suppression FOR SELECT
    USING (scope = 'GLOBAL' OR tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
CREATE POLICY supp_write ON suppression FOR INSERT
    WITH CHECK (scope = 'TENANT' AND tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
CREATE POLICY supp_delete ON suppression FOR DELETE
    USING (scope = 'TENANT' AND tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

-- Permissions (rev. 2026-10-06)
GRANT USAGE ON SCHEMA public TO email_app, email_system;
GRANT SELECT, INSERT, UPDATE, DELETE
    ON api_key, template, template_version, email_message, email_event, rate_limit_counter
    TO email_app, email_system;
GRANT SELECT, INSERT ON audit_log TO email_app, email_system;          -- inserts only
GRANT SELECT ON tenant TO email_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON tenant TO email_system;
-- A tenant must not learn which other tenant caused a global suppression
GRANT SELECT (id, scope, tenant_id, email, email_hash, reason, note, created_at),
      INSERT (id, scope, tenant_id, email, email_hash, reason, note, created_at),
      DELETE
    ON suppression TO email_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON suppression TO email_system;
-- email_system's BYPASSRLS: in scripts/init-db-roles.sql (requires a superuser), not here.
```

In every tenant transaction, the application first runs `SELECT set_config('app.tenant_id', :tenantId, true)` (the value lives only until the end of the transaction; a connection pool cannot leak it to another request).

## 6. Key worker queries (rev. 2026-10, ADR-0010)

Claiming work (a short transaction, autocommit):

```sql
WITH picked AS (
    SELECT m.id
    FROM email_message m
    JOIN tenant t ON t.id = m.tenant_id
    WHERE m.status = 'QUEUED'
      AND m.next_attempt_at <= now()
      AND t.status = 'ACTIVE' AND t.sending_paused_at IS NULL
    ORDER BY m.priority, m.next_attempt_at
    FOR UPDATE OF m SKIP LOCKED
    LIMIT :freeSlots                         -- free concurrency slots, not a fixed batch
)
UPDATE email_message m
SET status           = 'SENDING',
    attempts         = m.attempts + 1,      -- counted on claiming: the sweep cannot cause infinite loops
    first_attempt_at = coalesce(m.first_attempt_at, now()),
    lock_token       = gen_random_uuid(),
    lock_expires_at  = now() + make_interval(secs => :lockTimeout),
    locked_by        = :instanceId,
    updated_at       = now()
FROM picked
WHERE m.id = picked.id
RETURNING m.*;
```

Closing with fencing (no transaction open during the HTTP call):

```sql
UPDATE email_message
SET status = 'SENT', provider = :provider, provider_message_id = :pmid, sent_at = now(),
    lock_token = NULL, lock_expires_at = NULL, locked_by = NULL, updated_at = now()
WHERE id = :id AND status = 'SENDING' AND lock_token = :token;
-- 0 rows ⇒ the lock was lost: record it in attempt_log and the metric; do NOT resend.
```

Sweep of stuck messages (every 60 s, with `pg_try_advisory_xact_lock`):

```sql
UPDATE email_message
SET status = CASE WHEN attempts >= :maxAttempts THEN 'FAILED' ELSE 'QUEUED' END,
    failure_code = CASE WHEN attempts >= :maxAttempts THEN 'MAX_ATTEMPTS_EXCEEDED' END,
    finalized_at = CASE WHEN attempts >= :maxAttempts THEN now() END,
    next_attempt_at = now(),
    lock_token = NULL, lock_expires_at = NULL, locked_by = NULL, updated_at = now(),
    attempt_log = coalesce(attempt_log, '[]'::jsonb) || jsonb_build_object('at', now(), 'code', 'LOCK_EXPIRED')
WHERE status = 'SENDING' AND lock_expires_at < now();
```

Measured (2026-10-05, local PostgreSQL 16, 12,800 queued messages): claiming with `LIMIT 4` takes between 2.4 and 4.8 ms.

## 7. Operational queries

```sql
-- Depth and age of the eligible queue (metric and alert); excludes held tenants
SELECT count(*) AS pending,
       coalesce(extract(epoch FROM now() - min(m.next_attempt_at)), 0) AS oldest_seconds
FROM email_message m JOIN tenant t ON t.id = m.tenant_id
WHERE m.status = 'QUEUED' AND m.next_attempt_at <= now()
  AND t.status = 'ACTIVE' AND t.sending_paused_at IS NULL;

-- Sending health per tenant over the last 24 h
SELECT t.slug, m.status, count(*)
FROM email_message m JOIN tenant t ON t.id = m.tenant_id
WHERE m.created_at > now() - interval '24 hours'
GROUP BY 1, 2 ORDER BY 1, 2;

-- Bounce and complaint rates per tenant (FR-34)
SELECT tenant_id,
       count(*) FILTER (WHERE status = 'BOUNCED')::numeric
         / nullif(count(*) FILTER (WHERE status IN ('SENT','DELIVERED','BOUNCED','COMPLAINED')), 0) AS bounce_rate,
       count(*) FILTER (WHERE status = 'COMPLAINED')::numeric
         / nullif(count(*) FILTER (WHERE status IN ('DELIVERED','COMPLAINED')), 0) AS complaint_rate
FROM email_message
WHERE created_at > now() - interval '7 days'
GROUP BY 1;
```

## 8. Retention (rev. 2026-10)

| Data | Default retention |
|---|---|
| `email_message` + `email_event` | `tenant.retention_days` (90) |
| `variables` | Final state + 7 days (`VARIABLES_RETENTION_DAYS`); `x-sensitive` ones, on moving to `SENT`/terminal |
| `rendered_html` / `rendered_text` | 30 days (cleared before the row) |
| `idempotency_key` / `request_hash` | 24 h (set to `NULL`) |
| Orphan `email_event`s | 30 days |
| `audit_log` | 365 days |
| `suppression` | Indefinite (it protects reputation); after a data-subject erasure only `email_hash` remains |
| `rate_limit_counter` | 2 days |
| Logs (outside the database) | 30 days in the log destination |
| Backups | ≤ 35 days |

**(rev. 2026-10-12) Implemented in H7 (V5):**
- **`QUEUED`/`SENDING` messages:** purges never delete them, however old they are (owner). This covers, for example, the queue of a tenant paused for a long time.
- **Time-based final:** `SENT` and `DELIVERED` count as final `SENT_FINAL_AFTER_HOURS` after `sent_at`. `DELIVERED` is stable, not terminal: without this rule its variables would never be purged.
- **Deleting a message:** its events go with it, in the same statement, and its `email_message_tag` rows, through the cascade.
- **`audit_log`:**
  - the function `purge_expired_audit_log(batch)` is a `SECURITY DEFINER` of `email_owner`, with a fixed `search_path`;
  - only `email_system` can run it;
  - since `email_owner` is also subject to RLS `FORCE`, the `audit_retention` policy lets it see and delete only the rows older than 365 days;
  - the app's roles still have no `DELETE` on `audit_log`.
- **Partial indexes for each purge:** `ix_msg_purge_by_time`, `ix_msg_idempotency_age` and `ix_msg_rendered_age`, plus `ix_msg_created` for per-tenant retention and the metrics by state.
