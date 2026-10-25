# 02 — Functional requirements and acceptance criteria

> Revision 2026-10-05 (audit). New or modified criteria carry the mark **(rev. 2026-10)**. No ID has been renumbered; removed items are marked `DEPRECATED` and kept.

## 0. Conventions

- Stable IDs `FR-xx`. **They are not renumbered**; a removed requirement or criterion is marked `DEPRECATED` and kept.
- MoSCoW priority: **Must** (no MVP without it), **Should** (expected, can be cut), **Could** (if time allows), **Won't** (out of the MVP, documented so nobody implements it on their own).
- Acceptance criteria in **Given / When / Then** format, verifiable with an automated test.
- The enums and error codes used here are the same as in `06-data-model.md` and `07-internal-api.md`. No new values are invented without updating all three documents.
- The bulk email requirements (FR-40 to FR-54) live in `11-bulk-email.md` and are all `Won't` (ADR-0013).

## 1. Summary

| ID | Requirement | Priority |
|---|---|---|
| FR-01 | Product authentication by API key | Must |
| FR-02 | Tenant administration | Must |
| FR-03 | Issuing, rotating and revoking API keys | Must |
| FR-04 | Template and draft version management | Must |
| FR-05 | Publication and immutability of versions | Must |
| FR-06 | Preview (render without sending) | Should |
| FR-07 | Transactional send request | Must |
| FR-08 | Idempotency of the send request | Must |
| FR-09 | Sender and recipient validation | Must |
| FR-10 | Blocking by suppression list | Must |
| FR-11 | Persistent enqueuing and asynchronous response | Must |
| FR-12 | Sending worker | Must |
| FR-13 | Retries with backoff and error classification | Must |
| FR-14 | Provider integration through `EmailSender` | Must |
| FR-15 | Local development mode with SMTP (Mailpit) | Must |
| FR-16 | Receiving provider webhooks | Must |
| FR-17 | State update and event history | Must |
| FR-18 | Suppression API | Should |
| FR-19 | Querying the state of a send | Must |
| FR-20 | Listing and filtering sends | Should |
| FR-21 | Rate limits and quotas per tenant | Must |
| FR-22 | Auditing of sensitive actions | Should |
| FR-23 | Data retention and purging | **Must** (rev. 2026-10, previously Should) |
| FR-24 | Observability: health, metrics and correlated logs | Must |
| FR-25 | OpenAPI/Swagger documentation | Must |
| FR-26 | Cancelling a message that is still queued | Could |
| FR-27 | Sending inline content without a template | Won't (MVP) |
| FR-28 | ~~Attachments, scheduled sends, outgoing webhooks~~ | **DEPRECATED** (rev. 2026-10) → split into FR-30, FR-31 and FR-32 |
| FR-29 | Erasure of a data subject's data (right to erasure) | Must (new) |
| FR-30 | Bounded attachments (PDF/XML) | **Won't (MVP)** (owner 2026-10-05: no product sends attachments; ADR-0015 Rejected) |
| FR-31 | Scheduled sending (`sendAt`) | **Should** (new; owner 2026-10-05) |
| FR-32 | Outgoing webhooks towards the products | Won't (MVP) (new) |
| FR-33 | Scopes and origin restriction of API keys | Must (new) |
| FR-34 | Anomaly detection and automatic sending pause | Must (new) |
| FR-35 | Batch sending (`POST /v1/emails/batch`) | Should (new) |
| FR-36 | Template category and queue priority | Must (new) |
| FR-37 | Templates per locale (`es-CR` by default, `en` available) | Should (new; ADR-0018) |

## 2. State machine of a message (rev. 2026-10)

```
                     ┌──────────┐  cancel (FR-26)     ┌───────────┐
                     │  QUEUED  │────────────────────▶│ CANCELED  │ (terminal)
                     └────┬─────┘                     └───────────┘
     worker claims the job│  ▲ transient error (attempts < MAX and within the window)
                          ▼  │
                    ┌──────────┐   permanent error / attempts exhausted / window exhausted   ┌────────┐
                    │ SENDING  │────────────────────────────────────────────────────────────▶│ FAILED │ (terminal)
                    └────┬─────┘                                                             └────────┘
 provider accepts (id)   │                                                                        ▲
                         ▼                                    email.failed / email.suppressed     │
                    ┌──────────┐──────────────────────────────────────────────────────────────────┘
                    │   SENT   │
                    └────┬─────┘
                         │ email.delivered
                         ▼
                    ┌───────────┐  email.bounced (async)      ┌───────────┐
                    │ DELIVERED │────────────────────────────▶│  BOUNCED  │ (terminal)
                    │ (stable)  │  email.complained           ├───────────┤
                    └───────────┘────────────────────────────▶│ COMPLAINED│ (terminal)
                                                              └───────────┘
   From SENT a message can also go directly to BOUNCED or COMPLAINED.
```

Rules:

- **Order of precedence** (a transition only moves forward): `QUEUED (0) < SENDING (1) < SENT (2) < DELIVERED (3) < BOUNCED = COMPLAINED (4)`. `FAILED` and `CANCELED` are terminal and are reached only from `QUEUED`/`SENDING`, or from `SENT` through `email.failed`/`email.suppressed`.
- **Terminal:** `BOUNCED`, `COMPLAINED`, `FAILED`, `CANCELED`. **Stable:** `DELIVERED` (it can still move to `BOUNCED` or `COMPLAINED`, because a complaint always arrives after delivery).
- `SENT` with no event during `SENT_FINAL_AFTER_HOURS` (default 72 h) is considered final for metrics and variable purging; the state does not change.
- `opened` and `clicked` are **not** states: they are recorded as events and as timestamps (`first_opened_at`, `first_clicked_at`).
- An event that does not move the state forward (it arrives late or out of order) **is stored anyway** and **its side effects are applied anyway** (e.g. creating the suppression on a complaint). Changing the state and creating the suppression are independent effects.
- A suspended or paused tenant (FR-02, FR-34) does **not** change the state of its messages: the `QUEUED` ones are held.
- **(rev. 2026-10-11, owner)** `QUEUED` and `SENDING` belong to the worker: an event that arrives before the worker closes the message (for example, after a lost response) is stored **without** changing the state. The worker applies it right after its own close to `SENT`, so the fencing by `lock_token` does not change. Example: `email.delivered` arrives with the message in `SENDING`; on closing, the message ends `DELIVERED` with `delivered_at`.
- **(rev. 2026-10-11, owner)** If the worker closed the message as `FAILED` (e.g. `RETRY_WINDOW_EXCEEDED`) and the provider reports it did process it (`sent`, `delivered`, `bounced`…), the state does **not** change. A WARN and the metric `email_event_after_failure_total` are recorded, because it is the rare case of "the provider did send it".
- **(rev. 2026-10-11)** Correlating an event: first by the `message_id` tag (our UUID, present even before a `provider_message_id` exists), then by the provider's id (`data.email_id`). If both exist and point to different messages, the event is stored as an orphan with a WARN.

### 2.1 Mapping of provider events (Resend) (rev. 2026-10)

Types checked in Resend's documentation on 2026-10-05.

| Provider event | `email_event.type` | Effect on the state | Other effects |
|---|---|---|---|
| `email.sent` | `SENT` | None (already marked `SENT` when the API accepted it) | — |
| `email.delivered` | `DELIVERED` | `SENT → DELIVERED` | `delivered_at` |
| `email.delivery_delayed` | `DELIVERY_DELAYED` | None | Delay metric per tenant |
| `email.bounced` | `BOUNCED` | `SENT/DELIVERED → BOUNCED` | **Global** `HARD_BOUNCE` suppression (ADR-0012) |
| `email.complained` | `COMPLAINED` | `SENT/DELIVERED → COMPLAINED` | **Global** `COMPLAINT` suppression (ADR-0012); alert |
| `email.failed` | `FAILED` | `SENT → FAILED` (`PROVIDER_FAILED`) | — |
| `email.suppressed` | `SUPPRESSED` | `SENT → FAILED` (`PROVIDER_SUPPRESSED`) | **Global** `PROVIDER` mirror suppression |
| `email.opened` | `OPENED` | None | `first_opened_at`, `open_count` (only if tracking is enabled) |
| `email.clicked` | `CLICKED` | None | `first_clicked_at`, `click_count` (same) |
| `suppression.added` / `suppression.removed` | `OTHER` | None | Syncs the `PROVIDER` mirror suppression |
| Anything else (`email.scheduled`, `email.received`, `contact.*`, `domain.*`…) | `OTHER` | None | Stored and ignored |

---

## 3. Detailed requirements

### FR-01 — Product authentication by API key (Must)

Every request to `/v1/**` is authenticated with `Authorization: Bearer <api_key>`. The key identifies the tenant.

- **AC-01.1** — *Given* an active API key, *when* any `/v1/**` endpoint is called, *then* the request is resolved in that tenant's context and sees only its data. **(rev. 2026-10)** The context is set in the database with `set_config('app.tenant_id', …, true)` at the start of each transaction, and the RLS policies enforce it (ADR-0008).
- **AC-01.2** — *Given* a request without an `Authorization` header or with an invalid format, *then* the response is `401` with `code = UNAUTHENTICATED`, and it does not reveal whether the key exists.
- **AC-01.3** — *Given* a revoked or expired key, or one of a `SUSPENDED` tenant, *then* the response is `401` (key) or `403 TENANT_SUSPENDED`, and the attempt is recorded in the audit log.
- **AC-01.4** — The secret is compared on its **hash** (never in clear) and in constant time.
- **AC-01.5** — *When* a key is used successfully, *then* `last_used_at` is updated (with minute granularity, so as not to write on every request).
- **AC-01.6** — The API key never appears in logs, traces, error messages or responses (except at the moment it is created, FR-03).
- **AC-01.7** — **(rev. 2026-10)** *Given* a key with scopes (FR-33), *when* it calls an endpoint that requires a scope it lacks, *then* `403 INSUFFICIENT_SCOPE`.

### FR-02 — Tenant administration (Must)

`/admin/v1/**` endpoints protected by an administrator credential distinct from the tenant API keys.

- **AC-02.1** — *Given* a valid admin credential (`ADMIN_API_KEYS`), *when* a tenant is created with `slug`, `name`, `fromEmail`, `fromName`, `allowedFromDomains`, `allowedLinkHosts`, `locale`, `timezone` and limits, *then* it responds `201` with the created tenant.
- **AC-02.2** — *Given* an existing `slug`, *then* `409 TENANT_SLUG_TAKEN`.
- **AC-02.3** — **(rev. 2026-10)** A tenant can be suspended and reactivated. While suspended, its new sends are rejected with `403 TENANT_SUSPENDED`, and those already queued **remain `QUEUED`** without being sent (the worker does not claim messages of suspended or paused tenants). When it is reactivated, the queue drains on its own. No message moves to `FAILED` because of the suspension.
- **AC-02.4** — A `/admin/v1/**` request with a tenant API key responds `403 ADMIN_REQUIRED`.
- **AC-02.5** — The default values of limits, retention, `locale` (`es-CR`) and `timezone` (`America/Costa_Rica`) apply if they are not specified.
- **AC-02.6** — **(rev. 2026-10)** The queue age alert (AC-24.4) excludes messages of suspended or paused tenants; they are reported in a separate metric (`email_queue_held_messages`).

### FR-03 — Issuing, rotating and revoking API keys (Must)

- **AC-03.1** — **(rev. 2026-10)** *When* a key is issued for a tenant, *then* the `201` response includes the secret **only once**, in the format `esk_{env}_{prefix}_{secret}`: an 8-character Base62 `prefix` (regenerated if it collides) and a 43-character Base62 `secret` (≥ 256 bits from a CSPRNG). Only the prefix and the hash remain in the database.
- **AC-03.2** — *Given* a tenant, *when* its keys are queried, *then* `prefix`, `name`, `status`, `scopes`, `allowedCidrs`, `createdAt`, `expiresAt` and `lastUsedAt` are listed, never the secret.
- **AC-03.3** — A tenant can have **several active keys** at the same time (rotation with overlap).
- **AC-03.4** — **(rev. 2026-10-06, ADR-0019)** *When* a key is revoked, *then* it stops authenticating on the next request (there is no key cache) and becomes `REVOKED` with `revoked_at`.
- **AC-03.5** — A key with an `expiresAt` in the past does not authenticate.
- **AC-03.6** — Issuing, revoking and rotating are recorded in the audit log (FR-22) with the administrator as the actor.
- **AC-03.7** — **(rev. 2026-10)** A key's scopes and CIDRs are fixed when it is issued and are not modified; to change them, another key is issued (FR-33).

### FR-04 — Template and draft version management (Must)

Requires the `templates:write` scope (FR-33).

- **AC-04.1** — **(rev. 2026-10)** *When* a tenant creates a template with `key` (a kebab-case slug unique within the tenant), `name`, `description` and `category` (`SECURITY` \| `TRANSACTIONAL` \| `NOTICE`), *then* it responds `201`; if the `key` already exists, `409 TEMPLATE_KEY_TAKEN`.
- **AC-04.2** — **(rev. 2026-10)** *When* a version is created with `subjectTemplate`, `htmlTemplate`, `textTemplate` (optional) and `variablesSchema`, *then* it is created in the `DRAFT` state with a sequential number starting at 1. `variablesSchema` is optional in a draft and **required to publish** (AC-05.5).
- **AC-04.3** — A `DRAFT` version can be modified or deleted; a `PUBLISHED` one cannot (FR-05).
- **AC-04.4** — *Given* a template with invalid syntax, *then* creation fails with `422 TEMPLATE_SYNTAX_ERROR`, giving the line and detail.
- **AC-04.5** — **(rev. 2026-10)** Templates are **logic-less** (Handlebars) with HTML escaping by default in `htmlTemplate`. Rejected with `422 UNSAFE_TEMPLATE_CONSTRUCT`: unescaped interpolation in **any** of its forms (`{{{ }}}` and `{{& }}`), partials (`{{> }}`, `{{#> }}`) and any helper outside the whitelist (`if`, `unless`, `each`, `with`, `formatDate`, `formatNumber`, `formatMoney`).
- **AC-04.6** — A tenant only sees and modifies **its own** templates; trying to access another's returns `404` (not `403`, so as not to reveal that it exists).
- **AC-04.7** — **(rev. 2026-10)** An HTML context linter on save: any variable inside `<script>`, `<style>`, a `style` attribute, an event attribute (`on*`) or an unquoted attribute is rejected with `422 UNSAFE_TEMPLATE_CONSTRUCT`. Any `<script>`, `<iframe>`, `<object>`, `<embed>` or `<form>` tag in the template is rejected too.
- **AC-04.8** — **(rev. 2026-10)** Every variable used in an `href` or `src` attribute must be declared in `variablesSchema` with `"format": "uri"`; otherwise `422 UNSAFE_TEMPLATE_CONSTRUCT` on publication.
- **AC-04.9** — **(rev. 2026-10)** Context-aware escaping: `htmlTemplate` is rendered with HTML escaping; `subjectTemplate` and `textTemplate` are rendered **without** HTML escaping, with `\r` and `\n` removed from the subject. Test: the variable `"Ana & Juan"` produces `Ana &amp; Juan` in the HTML and `Ana & Juan` in the subject and the text.
- **AC-04.10** — **(rev. 2026-10)** Limits: `htmlTemplate` source ≤ 256 KB (`422 VALIDATION_ERROR`); if the preview's rendered HTML exceeds 100 KB, the response includes a warning `warnings: ["HTML_MAY_BE_CLIPPED"]`.
- **AC-04.11** — **(rev. 2026-10)** Open and click tracking (`trackingEnabled`) is disabled by default, can only be enabled in `NOTICE` templates, and is rejected with `422 VALIDATION_ERROR` in `SECURITY`.

### FR-05 — Publication and immutability of versions (Must)

- **AC-05.1** — *When* a `DRAFT` version is published, *then* it becomes `PUBLISHED` with `published_at`, and any later attempt to modify or delete it returns `409 VERSION_IMMUTABLE`.
- **AC-05.2** — Publishing a new version does not alter the previous ones: the previously published one becomes `ARCHIVED`, but **remains usable** if a send references it explicitly.
- **AC-05.3** — *Given* a send without `templateVersion`, *then* the latest `PUBLISHED` version is used and **pinned** (`template_version_id`) in the message, so that a later retry renders exactly the same thing.
- **AC-05.4** — *Given* a template without any published version, *when* a send uses it, *then* `422 TEMPLATE_NOT_PUBLISHED`.
- **AC-05.5** — **(rev. 2026-10)** Publishing requires a valid `variablesSchema` according to the subset in `06` §3.3; without it, `422 VALIDATION_ERROR`. Publishing is audited.

### FR-06 — Preview (render without sending) (Should)

- **AC-06.1** — *When* a preview of a template/version is requested with sample variables, *then* it responds `200` with the rendered `subject`, `html`, `text` and `warnings`, without creating any message or sending anything.
- **AC-06.2** — *Given* variables missing relative to `variablesSchema`, *then* it responds `422 TEMPLATE_VARIABLES_INVALID` with the list of the missing ones.
- **AC-06.3** — The preview render uses exactly the same component as the real send (same escaping, same helpers).

### FR-07 — Transactional send request (Must)

`POST /v1/emails` with `templateKey`, `to`, `variables` and optional fields (`templateVersion`, `locale` (FR-37), `fromName`, `replyTo`, `cc`, `bcc`, `tags`, `metadata`, `sendAt` (FR-31)). Requires the `emails:send` scope.

- **AC-07.1** — *Given* a valid request, *then* the response is `202` with `id`, `status = QUEUED` and `createdAt`, in **≤ 150 ms (p95)**, without having contacted the provider.
- **AC-07.2** — *Given* missing or malformed required fields, *then* `422 VALIDATION_ERROR` with the detail per field, and no message is created.
- **AC-07.3** — *Given* a `templateKey` that does not exist in the tenant, *then* `404 TEMPLATE_NOT_FOUND`.
- **AC-07.4** — *Given* variables that do not meet the version's `variablesSchema`, *then* `422 TEMPLATE_VARIABLES_INVALID`.
- **AC-07.5** — **(rev. 2026-10)** The API validates the variables **and** renders the template synchronously, discarding the result (a render error returns `422 TEMPLATE_VARIABLES_INVALID` with the detail). The worker renders again with the pinned version when sending. The result is not stored unless `storeRenderedContent`.
- **AC-07.6** — The request body cannot exceed `MAX_REQUEST_BYTES` (default 256 KB) → `413 PAYLOAD_TOO_LARGE`. **(rev. 2026-10)** An `attachments` field is rejected with `422 VALIDATION_ERROR` while FR-30 stays Won't.
- **AC-07.7** — `cc` and `bcc` accept up to 5 addresses each; `to` is **a single** address (one message = one main recipient, so that the state is unambiguous).
- **AC-07.8** — **(rev. 2026-10)** Every variable with `"format": "uri"` must use the `https` scheme and have a host that matches `tenant.allowedLinkHosts` (an exact match or a subdomain of an entry). Otherwise, `422 UNSAFE_URL` naming the variable. **(rev. 2026-10-14, ADR-0021)** With `APP_ENV=local` only, `http` or `https` to `localhost` and `*.localhost` are accepted too; in `staging` and `production` the rule does not change.
- **AC-07.9** — **(rev. 2026-10)** *Given* a paused tenant (FR-34), *then* `403 TENANT_SENDING_PAUSED` and no message is created.

### FR-08 — Idempotency (Must)

- **AC-08.1** — *Given* a request with `Idempotency-Key`, *when* it is repeated with the **same body** (SHA-256 hash of the canonical payload), *then* it responds `200` with the original message (same `id`), and **no** second email is created or sent.
- **AC-08.2** — *Given* the same `Idempotency-Key` with a different body, *then* `409 IDEMPOTENCY_KEY_REUSED`.
- **AC-08.3** — Uniqueness is per tenant: two tenants can use the same key without interfering.
- **AC-08.4** — *Given* two concurrent requests with the same key, *then* exactly one creates the message and the other receives the original (a `UNIQUE` constraint in the database with `INSERT … ON CONFLICT DO NOTHING`, not just a prior check).
- **AC-08.5** — **(rev. 2026-10)** Keys are kept for `IDEMPOTENCY_RETENTION_HOURS` (default 24 h); after that they are set to `NULL`. At most 256 printable ASCII characters; otherwise `422 VALIDATION_ERROR`.
- **AC-08.6** — If the client does not send `Idempotency-Key`, the request is accepted anyway and counted in the metric `email_requests_without_idempotency_total`. The integration contract (`07` §11) requires sending it.
- **AC-08.7** — **(rev. 2026-10)** The idempotency check happens **before** the rate limit: a repeat that returns the original (`200`) uses up neither rate limit nor quota.

### FR-09 — Sender and recipient validation (Must)

- **AC-09.1** — Addresses are validated syntactically (basic RFC 5322, no comments or quoted addresses) and normalized (trimmed, all lowercase for comparison); invalid ones → `422 INVALID_EMAIL_ADDRESS`.
- **AC-09.2** — The effective sender is the tenant's; if the request sends its own `from`, it must belong to the tenant's `allowedFromDomains`; otherwise `403 FROM_DOMAIN_NOT_ALLOWED`.
- **AC-09.3** — Any address, subject, name (including `fromName`) or `tags` field containing `\r` or `\n` is rejected with `422 HEADER_INJECTION_DETECTED`.
- **AC-09.4** — In non-production environments a recipient domain allowlist can be required (`ALLOWED_RECIPIENT_DOMAINS`); outside it, `403 RECIPIENT_NOT_ALLOWED_IN_ENV`. **(rev. 2026-10)** In `staging` the allowlist is mandatory: the app does not start with `APP_ENV=staging` and an empty `ALLOWED_RECIPIENT_DOMAINS`.
- **AC-09.5** — **(rev. 2026-10)** The request can override only the sender's display **name** (`fromName`, ≤ 80 characters, with no CR/LF and no `<`, `>`, `@`), so that a product like Colmena can show "Business X via Colmena"; the address stays subject to AC-09.2.

### FR-10 — Blocking by suppression list (Must)

- **AC-10.1** — **(rev. 2026-10)** *Given* a suppressed recipient (in `to`, `cc` or `bcc`) for that tenant or globally, *when* a send is requested, *then*:
  - if the suppressed address is in `to`, the API responds `202` and the message is created directly as `FAILED` with `failure_code = SUPPRESSED` and `suppressionReason` (`HARD_BOUNCE`, `COMPLAINT`, `PROVIDER` or `MANUAL`), without calling the provider. **(rev. 2026-10)** With that reason the product can use an alternative address of the user (e.g. to recover the account after a complaint); the email-service does not choose alternative addresses;
  - if there are only suppressed addresses in `cc`/`bcc`, they are removed from those lists, the message is queued and the response includes `droppedRecipients`.
  - A configurable alternative, `SUPPRESSION_REJECT_MODE=reject` → `422 RECIPIENT_SUPPRESSED`.
- **AC-10.2** — **(rev. 2026-10)** Scope of the suppression (ADR-0012): `HARD_BOUNCE`, `COMPLAINT` and `PROVIDER` are **global** (they apply to all tenants); `MANUAL` is **per tenant**. *Given* an address suppressed globally because of a hard bounce from tenant A, *when* tenant B sends to that address, *then* it also ends `FAILED`/`SUPPRESSED`.
- **AC-10.3** — A hard bounce (`email.bounced`) or a complaint (`email.complained`) received by webhook automatically creates the suppression, **even if the event does not change the message's state** (§2).
  - **(rev. 2026-10-11, owner)** The provider's event does not say which recipient bounced or complained. If the message had `cc` or `bcc`, the `to` is **not** suppressed, because it could be the user's main address. The message's state does move to `BOUNCED`/`COMPLAINED`, because it is a fact about the message.
  - In that case the suppression arrives with the provider's `suppression.added`, which names the exact address: a global `PROVIDER` mirror row.
  - Without copies, the `to` is suppressed. An orphan event suppresses the addresses the provider reports (`data.to`).
- **AC-10.4** — **(rev. 2026-10)** `email.delivery_delayed` (a temporary problem) does **not** suppress: it only records the `DELIVERY_DELAYED` event.
- **AC-10.5** — The check is case-insensitive in the domain and in the local part (a simple, explicit decision).
- **AC-10.6** — **(rev. 2026-10)** The worker checks the suppression again just before calling the provider (AC-12.2), so a suppression created between acceptance and sending is honoured too.

### FR-11 — Persistent enqueuing and asynchronous response (Must)

- **AC-11.1** — The message is persisted in the same transaction that enqueues it (the row **is** the queued job): there is no "accepted but not queued" state.
- **AC-11.2** — *Given* the provider is down, *then* `POST /v1/emails` keeps responding `202` normally.
- **AC-11.3** — **(rev. 2026-10)** *Given* a service restart with messages in flight, *then* no message is lost or stuck: `SENDING` messages with an expired lock (`lock_expires_at < now()`) go back to `QUEUED` **keeping the `attempts` already incremented when they were claimed** (ADR-0010). A message that exhausts `MAX_ATTEMPTS` through successive reclaims ends as `FAILED`/`MAX_ATTEMPTS_EXCEEDED`.
- **AC-11.4** — Claiming uses `SELECT ... FOR UPDATE SKIP LOCKED`, so several workers do not process the same message.
- **AC-11.5** — **(rev. 2026-10)** Claiming is a short transaction that ends (`COMMIT`) **before** any network call. No provider call happens with row locks held.

### FR-12 — Sending worker (Must)

- **AC-12.1** — **(rev. 2026-10)** The worker claims at most as many messages as it has free concurrency slots (`WORKER_CONCURRENCY`, default 4): `QUEUED` messages with `next_attempt_at <= now()` of `ACTIVE`, unpaused tenants, ordered by `priority` and then by `next_attempt_at`.
- **AC-12.2** — For each message: it marks it `SENDING` with `lock_token` and `lock_expires_at`, renders the pinned version, checks the suppression and the tenant's state again, and calls `EmailSender` with `idempotencyKey = message.id`.
- **AC-12.3** — **(rev. 2026-10)** *Given* a send accepted by the provider, *then* the message moves to `SENT` with `provider_message_id` and `sent_at` through `UPDATE … WHERE id = :id AND lock_token = :token AND status = 'SENDING'`. If the `UPDATE` affects 0 rows (the lock was lost), the result is recorded in `attempt_log`, the metric `email_lost_lock_total` is emitted and it is **not** sent again.
- **AC-12.4** — The worker runs in the same artifact as the API, enabled by profile/variable (`APP_ROLE=api|worker|all`), and can scale to N instances without duplicating sends.
- **AC-12.5** — A render error (a template broken with that data) marks `FAILED` with `failure_code = RENDER_ERROR` and is **not** retried.
- **AC-12.6** — **(rev. 2026-10)** Each worker instance respects a rate limit towards the provider of `WORKER_PROVIDER_RPS` (default 8). The sum of `WORKER_PROVIDER_RPS` over all instances sharing a provider account cannot exceed the contracted limit (Resend: 10 rps per team by default). See NFR-21.
- **AC-12.7** — **(rev. 2026-10)** The body sent to the provider is deterministic for a given message (same version, same variables, same header order), so the provider's idempotency recognizes the retry. Templates have no access to "the current time".

### FR-13 — Retries with backoff and error classification (Must)

- **AC-13.1** — **Transient** errors (timeout, 429, provider 5xx, network error) return the message to `QUEUED` with `next_attempt_at` following exponential backoff with ±20 % jitter: 1 min, 5 min, 15 min, 1 h, 6 h (configurable in `RETRY_BACKOFF_SECONDS`).
- **AC-13.2** — **Permanent** errors (invalid address, non-429 4xx from the provider, rejected content) mark `FAILED` immediately with `failure_code` and `failure_detail`, without retries.
- **AC-13.3** — **(rev. 2026-10)** `MAX_ATTEMPTS` (default 6) = the number of `RETRY_BACKOFF_SECONDS` waits + 1; the application does not start if it does not match. Once the attempts are exhausted, the message ends `FAILED` with `failure_code = MAX_ATTEMPTS_EXCEEDED`.
- **AC-13.4** — Every attempt is recorded (counter and last error); the message keeps the error history in `attempt_log` (jsonb, last 10 attempts).
- **AC-13.5** — *Given* a `429` from the provider with `Retry-After`, *then* that value is honoured instead of the computed backoff.
- **AC-13.6** — **(rev. 2026-10)** A retry **never** produces a second email within the provider's idempotency window: every attempt carries `Idempotency-Key = message.id`; if there is already a `provider_message_id`, it is not called again.
- **AC-13.7** — **(rev. 2026-10)** The sum of `RETRY_BACKOFF_SECONDS` × 1.2 (jitter) + `MAX_ATTEMPTS` × `LOCK_TIMEOUT_SECONDS` must be < 23 h (Resend's idempotency window is 24 h); the app does not start otherwise. A message whose first attempt was more than 23 h ago is not tried again: it moves to `FAILED` with `RETRY_WINDOW_EXCEEDED`.
- **AC-13.8** — **(rev. 2026-10)** A `409` from the provider for an idempotency key reused with another body is classified as permanent (`PROVIDER_IDEMPOTENCY_CONFLICT`) and raises an alert, because it indicates a non-deterministic render.
- **AC-13.9** — **(rev. 2026-10)** *Circuit breaker:* after `CIRCUIT_BREAKER_FAILURES` (default 5) consecutive transient errors, the worker stops calling the provider for `CIRCUIT_BREAKER_OPEN_SECONDS` (default 60) and then tries a single send. Messages are not marked as failed while it is open; they are simply not claimed.

### FR-14 — Provider integration through `EmailSender` (Must)

- **AC-14.1** — There is an `EmailSender` interface with an operation `send(OutboundEmail) → SendResult` and the implementations `ResendEmailSender` (or the chosen provider), `SmtpEmailSender` and `NoopEmailSender` (tests).
- **AC-14.2** — The active implementation is chosen by `MAIL_PROVIDER` (`resend|smtp|noop`) with no code or architecture changes.
- **AC-14.3** — The rest of the system does **not** know the provider: no types, exceptions or SDK-specific headers cross that boundary (they are translated into our own `SendResult`/`SendException`).
- **AC-14.4** — **(rev. 2026-10)** Configurable connect and read timeouts (`PROVIDER_CONNECT_TIMEOUT_MS` = 3,000, `PROVIDER_READ_TIMEOUT_MS` = 10,000). Correlation uses `provider_message_id` and the provider tag `message_id=<uuid>`; `X-Entity-Ref-ID` is not used to correlate.
- **AC-14.5** — Adding a new provider means creating an implementation and registering it; it does not require touching the worker.
- **AC-14.6** — **(rev. 2026-10)** `OutboundEmail` includes `idempotencyKey`; the implementations that support it send it (Resend: the `Idempotency-Key` header).
- **AC-14.7** — **(rev. 2026-10)** The provider's webhook verification is exposed as the `WebhookVerifier` port in the `provider` module; the `events` module consumes it without knowing the provider.

### FR-15 — Local development mode with SMTP (Must)

- **AC-15.1** — *Given* `MAIL_PROVIDER=smtp` pointing to Mailpit, *when* an email is sent, *then* it shows up in Mailpit's interface with the rendered HTML.
- **AC-15.2** — `docker compose up` starts app + PostgreSQL + Mailpit and allows an end-to-end test send **without any provider credentials**.
- **AC-15.3** — SMTP mode is forbidden in production: with `APP_ENV=production` and `MAIL_PROVIDER=smtp`, the application **does not start** (fail-fast) unless `ALLOW_SMTP_IN_PRODUCTION=true` is set explicitly.
- **AC-15.4** — SMTP with username/password is **not** the production mechanism (ADR-0003, ratified by ADR-0016); in SMTP mode there are no webhooks and the state stays `SENT`.

### FR-16 — Receiving provider webhooks (Must)

`POST /webhooks/{provider}`, public, with no tenant API key.

- **AC-16.1** — **(rev. 2026-10)** *Given* a webhook with a valid signature (verified on the **raw body**, without re-serializing, with the endpoint's secret in `MAIL_WEBHOOK_SIGNING_SECRET`; in Resend, the `svix-id`, `svix-timestamp`, `svix-signature` headers), *then* it responds `204` and processes the event.
- **AC-16.2** — *Given* an invalid or missing signature, *then* `401`, the body is **not** processed and the attempt is recorded (aggregated by IP and window).
- **AC-16.3** — *Given* an event with a timestamp outside the tolerance window (default ±5 min), *then* it is rejected with `401` (replay protection).
- **AC-16.4** — **(rev. 2026-10)** *Given* an event already received (same `svix-id`, stored as `provider_event_id`), *then* it is ignored idempotently and responds `204`.
- **AC-16.5** — *Given* an event whose `provider_message_id` matches no known message, *then* it is stored as an orphan and responds `204` (never 5xx: it would cause infinite retries from the provider). Orphans are purged after 30 days.
- **AC-16.6** — Processing responds in ≤ 2 s; heavy work (if any) is done in the background.
- **AC-16.7** — **(rev. 2026-10)** On a database error (not a correlation one), the endpoint does respond `503`, so that the provider retries; this does not contradict AC-16.5.

### FR-17 — State update and event history (Must)

- **AC-17.1** — **(rev. 2026-10)** Every valid event is stored in `email_event` with its type, the provider's date, the reception date and a **minimized payload**: identifiers, type, timestamps and bounce/complaint data; `to`, `from`, `subject`, headers and the query string of clicked URLs are removed.
- **AC-17.2** — **(rev. 2026-10)** Events update the message's state following the order of precedence of §2. An event that does not move the state forward is stored anyway and applies its other effects (§2.1).
- **AC-17.3** — `opened`/`clicked` do not change the state; they update `first_opened_at`/`first_clicked_at` and their counters.
- **AC-17.4** — **(rev. 2026-10)** A hard bounce or a complaint creates the suppression (FR-10) in the same transaction in which the event is stored, **regardless** of whether the state changes.
- **AC-17.5** — **(rev. 2026-10)** *Given* a message in `DELIVERED`, *when* `email.complained` arrives, *then* the state moves to `COMPLAINED` and the global `COMPLAINT` suppression is created.
- **AC-17.6** — **(rev. 2026-10)** The `suppression.added`/`suppression.removed` and `email.suppressed` events keep the `PROVIDER` mirror suppression in sync (ADR-0012).

### FR-18 — Suppression API (Should)

- **AC-18.1** — `GET /v1/suppressions` lists the suppressions that affect the tenant (its own `MANUAL` ones and the global ones), with a reason filter and pagination. Global ones are shown with the address masked (`a***@domain.com`) and `scope = GLOBAL`. Requires `emails:read`.
- **AC-18.2** — `POST /v1/suppressions` adds one manually (`reason = MANUAL`, `scope = TENANT`). Requires `suppressions:write`.
- **AC-18.3** — **(rev. 2026-10)** `DELETE /v1/suppressions/{email}` deletes only the tenant's own `MANUAL` suppressions and is audited. Trying to delete a global suppression → `403 ADMIN_REQUIRED`; global ones are managed in `/admin/v1/suppressions`.
- **AC-18.4** — A tenant can neither see nor delete another tenant's `MANUAL` suppressions.

### FR-19 — Querying the state of a send (Must)

- **AC-19.1** — `GET /v1/emails/{id}` returns the state, the template and version used, the recipient, attempts, `providerMessageId`, timestamps, the last error, the effective `locale` and the list of events. Requires `emails:read`.
- **AC-19.2** — An `id` of another tenant returns `404`.
- **AC-19.3** — The rendered content is included only if the tenant has `storeRenderedContent = true` and it has not been purged yet; `x-sensitive` variables appear as `[REDACTED]`.

### FR-20 — Listing and filtering sends (Should)

- **AC-20.1** — **(rev. 2026-10)** `GET /v1/emails` accepts the filters `status`, `templateKey`, `to` (recipient), `tag`, `createdAfter` and `createdBefore` (dates), with cursor pagination; by default, the 20 most recent first.
- **AC-20.2** — **(rev. 2026-10-12, owner)** It responds in ≤ 500 ms (p95) with the **reference dataset**: 4 tenants and 4,000,000 messages in total, 2,800,000 (70 %) in one of them and 400,000 in each of the other three. Measured with `./gradlew benchmarkOperations` through the API (authentication and RLS included); report in `build/reports/operations-benchmark.txt`. The previous wording ("1,000,000 rows per tenant, 70 % in one") was contradictory.
- **AC-20.3** — It only returns messages of the authenticated tenant (verified by an isolation test and by RLS).

### FR-21 — Rate limits and quotas per tenant (Must)

- **AC-21.1** — **(rev. 2026-10)** Each tenant has `rateLimitPerMinute` (default 60) and `dailyQuota` (default **1,000**; set explicitly per tenant to ~3× its expected daily volume). Exceeding them returns `429 RATE_LIMITED` with `Retry-After`.
- **AC-21.2** — The counter is shared across API instances (it is kept in PostgreSQL, not in process memory).
- **AC-21.3** — Limits are configurable per tenant without redeploying.
- **AC-21.4** — Limit rejections are recorded as a metric and in an aggregated audit (not one row per rejection).
- **AC-21.5** — **(rev. 2026-10)** The counter is incremented in the **same transaction** that inserts the message and after all validations: a rejected send (by validation, suppression in `reject` mode or the limit) or an idempotent repeat does not consume quota. A message created as `FAILED/SUPPRESSED` does consume it.
- **AC-21.6** — **(rev. 2026-10)** In a batch (FR-35), each accepted item counts as one send.

### FR-22 — Auditing of sensitive actions (Should)

- **AC-22.1** — **(rev. 2026-10)** Audited: creating, suspending, pausing and resuming tenants; issuing and revoking keys; publishing templates; deleting suppressions; data-subject erasures (FR-29); repeated authentication failures; access to `/admin/v1/**`.
- **AC-22.2** — Each entry stores the actor (admin or `api_key_id`), action, resource, source IP, `requestId`, timestamp and metadata; **never** secrets or email content. If the resource is an email address, its `email_hash` is stored.
- **AC-22.3** — Audit entries are neither updated nor deleted by the application (only the retention purge, FR-23).

### FR-23 — Data retention and purging (Must, rev. 2026-10)

- **AC-23.1** — Each tenant has `retentionDays` (default 90); a daily task deletes the older messages and events.
- **AC-23.2** — The purge is idempotent, runs in batches and records the number of deleted rows.
- **AC-23.3** — Suppressions are **not** purged by age (they protect reputation).
- **AC-23.4** — The audit log has its own retention (default 365 days).
- **AC-23.5** — **(rev. 2026-10)** `variables` is replaced by `{}` (and `variables_purged_at` is recorded) `VARIABLES_RETENTION_DAYS` (default 7) days after the message reaches a terminal or time-based final state. Variables marked `x-sensitive` in the schema are removed **when the message moves to `SENT`** or a terminal state.
- **AC-23.6** — **(rev. 2026-10; applies only if FR-30 is enabled)** The attachments' content (`email_attachment.content`) is removed as soon as the message reaches `SENT` or a terminal state; the metadata (name, type, size, hash) is kept until the message is purged.
- **AC-23.7** — **(rev. 2026-10)** `rendered_*` is cleared after 30 days. Orphan events (null `tenant_id`) are purged after 30 days.
- **AC-23.8** — **(rev. 2026-10-12, owner)** Purges never delete `QUEUED` or `SENDING` messages.
- **AC-23.9** — **(rev. 2026-10-12, proposal pending the owner's confirmation)** For AC-23.5, `DELIVERED` counts as time-based final just like `SENT` (`SENT_FINAL_AFTER_HOURS` from `sent_at`). `DELIVERED` is stable, not terminal: without this, its variables would never be purged.

### FR-24 — Observability (Must)

- **AC-24.1** — **(rev. 2026-10)** `/actuator/health/liveness` depends on nothing external; `/actuator/health/readiness` depends only on the database. `/actuator/health` also aggregates the `provider` indicator, computed from the results of the last 5 minutes (without pinging the provider).
- **AC-24.2** — Exposed metrics: messages by state, acceptance latency, acceptance→`SENT` time, queue depth (eligible `QUEUED`), age of the oldest eligible message, messages held by paused or suspended tenants, attempts, events by type, rate-limit rejections, provider latency, circuit breaker state, bounce and complaint rate per tenant, and requests without an idempotency key.
- **AC-24.3** — **(rev. 2026-10)** All logs are JSON with `tenantSlug`, `messageId` and `requestId` when they apply; they **never** include the email content, variables or secrets, and addresses appear masked (`a***@domain.com`).
- **AC-24.4** — **(rev. 2026-10)** *Given* the oldest eligible message exceeds `QUEUE_AGE_ALERT_SECONDS` (default 300), *then* the `queue` indicator of `/actuator/health` reports the custom state `DEGRADED`, mapped to HTTP 200 so as not to kill healthy instances, and an alert is emitted.

### FR-25 — OpenAPI documentation (Must)

- **AC-25.1** — Swagger UI available at `/swagger-ui.html` and the spec at `/v3/api-docs`, with request and response examples per endpoint.
- **AC-25.2** — **(rev. 2026-10-06)** The spec is exported per group to `contracts/email-service.openapi.json` (products' API: `/v1/**` and `/webhooks/**`) and to `contracts/email-service-admin.openapi.json` (`/admin/v1/**`, AC-25.3); a CI test fails if either differs from the generated one.
- **AC-25.3** — The `/admin/v1/**` endpoints appear in a separate group.

### FR-26 — Cancelling a queued message (Could)

- **AC-26.1** — `POST /v1/emails/{id}/cancel` on a `QUEUED` message moves it to `CANCELED`; on any other state, `409 MESSAGE_NOT_CANCELABLE`. Requires `emails:send`.
  - **(rev. 2026-10-13, owner, H8)** It never cancels a message the worker has already claimed. The row is locked and the `UPDATE` is conditional (`status = 'QUEUED'`): if a claim is in progress, the cancellation waits for it, sees `SENDING` and responds `409`.
  - A non-existent id or one of another tenant → `404 MESSAGE_NOT_FOUND` (RLS). Cancelling twice → `409` the second time.
  - It works even if the tenant is paused.
  - The message ends `CANCELED` with `failureCode = CANCELED_BY_CLIENT`, without its `x-sensitive` variables (AC-23.5), and shows up that way in `GET /v1/emails/{id}`.

### FR-27 — Inline content without a template (Won't)

Documented here so that no contributor implements it without an ADR (see `09-roadmap.md`).

### FR-28 — DEPRECATED (rev. 2026-10)

It grouped attachments, scheduled sends and outgoing webhooks as `Won't`. It is split into FR-30, FR-31 and FR-32. After the owner's answers (2026-10-05): FR-30 (attachments) Won't, FR-31 (`sendAt`) Should and FR-32 (outgoing webhooks) Won't.

### FR-29 — Erasure of a data subject's data (Must, new)

`POST /admin/v1/data-subjects/erase` with `{ "email": "…", "reference": "ARCO request #…" }`.

- **AC-29.1** — *When* it runs, *then* across all tenants the messages whose `to_email` matches are deleted, together with their events; the address is removed from the `cc`/`bcc` of other messages; suppressions keep only `email_hash` (the clear-text address becomes `NULL`), so we still never write to them.
- **AC-29.2** — The response states how many rows were deleted per table; it is audited with `email_hash` and `reference`, never with the clear-text address.
- **AC-29.3** — The operation is idempotent and finishes in < 60 s with the reference dataset.
- **AC-29.4** — The documented procedure (`08` §6) includes asking the email provider to delete the data and answering the data subject within the legal deadline (5 business days under Law 8968; to be confirmed with legal counsel).

### FR-30 — Bounded attachments (Won't (MVP); ADR-0015 Rejected)

> **(rev. 2026-10-05, owner):** no product sends receipts or other attachments. The criteria are kept as a reference design. Trigger to reactivate it: a product with a real use case that a signed link does not solve.

- **AC-30.1** — `attachments` is an optional array of up to 3 objects `{ filename, contentType, contentBase64 }`. `contentType` ∈ {`application/pdf`, `application/xml`, `text/xml`}. The sum of decoded sizes is ≤ `MAX_ATTACHMENT_BYTES_TOTAL` (default 2 MB). Outside those limits → `422 ATTACHMENT_INVALID` or `413 PAYLOAD_TOO_LARGE`.
- **AC-30.2** — The magic bytes are checked (`%PDF-` for PDF; `<?xml` or `<` after the BOM for XML); if they do not match `contentType` → `422 ATTACHMENT_INVALID`. `filename` has ≤ 100 characters, with no `/`, `\`, CR or LF.
- **AC-30.3** — Attachments are stored in `email_attachment` (same transaction as the message), and their content is removed when the message reaches `SENT` or a terminal state (AC-23.6).
- **AC-30.4** — Requires the `emails:send` scope. The batch endpoint (FR-35) does not accept attachments.
- **AC-30.5** — Products attach only documents **they generate themselves** (receipts, invoices); they never forward files uploaded by users (there is no antivirus in the MVP).

### FR-31 — Scheduled sending with `sendAt` (Should, new)

- **AC-31.1** — `sendAt` (ISO-8601 with a zone) sets `next_attempt_at`; it must be between `now()` and `now() + SEND_AT_MAX_DAYS` (default 30); otherwise `422 SEND_AT_OUT_OF_RANGE`.
  - **(rev. 2026-10-13, owner)** Up to `SEND_AT_PAST_TOLERANCE_SECONDS` in the past (default 60, configurable from 0 to 600) is treated as an immediate send, so that a `sendAt` meaning "now" is not rejected because of latency or clock skew. Beyond that, `422 SEND_AT_OUT_OF_RANGE`.
  - The boundaries are measured with the application's `Clock` (`05`, "Time source").
  - A date without a zone, or something that is not a date → `422 VALIDATION_ERROR`.
- **AC-31.2** — The template version is pinned on **acceptance**, not on sending (documented in `07`).
- **AC-31.3** — A scheduled message can be cancelled with FR-26 until it is claimed.
- **AC-31.4** — The retry window of AC-13.7 starts counting at the first attempt, not at acceptance.

### FR-32 — Outgoing webhooks towards the products (Won't, new)

Products query the state through `GET /v1/emails/{id}`. Trigger to reconsider it: a product needs to react to `BOUNCED`/`COMPLAINED` in < 5 min and polling costs more than 1,000 requests/day.

### FR-33 — Scopes and origin restriction of API keys (Must, new; ADR-0017)

- **AC-33.1** — Each key has `scopes` ⊆ {`emails:send`, `emails:read`, `templates:write`, `suppressions:write`}. By default: `emails:send`, `emails:read`.
- **AC-33.2** — The map of endpoints to scopes is in `07` §2; a call without the required scope → `403 INSUFFICIENT_SCOPE`.
- **AC-33.3** — A key can have `allowedCidrs` (a list of CIDRs). If the list is not empty and the source IP (the socket's IP, or `X-Forwarded-For` only from configured trusted proxies) is not in it → `403 IP_NOT_ALLOWED`, and it is audited.
- **AC-33.4** — The key deployed in a product does **not** have `templates:write` or `suppressions:write`; those keys are used by the administrator from their machine or from CI.

### FR-34 — Anomaly detection and automatic pause (Must, new)

A worker task evaluates every 5 minutes, per tenant:

- **AC-34.1** — **Volume:** *given* the messages accepted in the last hour exceed `max(ANOMALY_VOLUME_MULTIPLIER × hourly p95 of the last 14 days, ANOMALY_MIN_HOURLY)` (defaults 3 and 50), *then* the tenant is paused (`sending_paused_at`, `pause_reason = VOLUME_ANOMALY`), an alert is emitted and it is audited.
- **AC-34.2** — **Bounces:** *given* ≥ 200 messages with an outcome in the last 24 h and a `BOUNCED` rate > `BOUNCE_PAUSE_RATE` (default 4 %), *then* pause with `BOUNCE_RATE`.
- **AC-34.3** — **Complaints:** *given* ≥ 1,000 messages delivered in the last 7 days and a `COMPLAINED` rate > `COMPLAINT_PAUSE_RATE` (default 0.1 %), or 3 complaints in 24 h at any volume, *then* pause with `COMPLAINT_RATE`.
- **AC-34.4** — While paused, the API responds `403 TENANT_SENDING_PAUSED` to new sends and the queue is held (AC-02.3). Only an administrator lifts it: `POST /admin/v1/tenants/{slug}/resume-sending`, which is audited.
- **AC-34.5** — The thresholds are configurable per tenant; the evaluation never pauses below the stated volume minimums (avoiding false positives with few sends).

### FR-35 — Batch sending (Should, new; ADR-0014)

`POST /v1/emails/batch` with `{ "items": [ … ] }`.

- **AC-35.1** — Up to 100 items; each has the same schema as `POST /v1/emails` (without `attachments`) plus a required `idempotencyKey` field. More than 100 → `422 VALIDATION_ERROR`. Body ≤ 1 MB.
- **AC-35.2** — A `200` response with `results[]` in the same order: `{ index, httpStatus, id?, status?, code? }`. Each item is validated separately: an invalid item does not invalidate the others.
- **AC-35.3** — Accepted items are inserted in **one** transaction; idempotency and the rate limit apply per item (AC-21.6). If the rate limit runs out mid-batch, the remaining items get `429 RATE_LIMITED`.
- **AC-35.4** — The messages of a batch inherit the priority of their category (FR-36); a batch never overtakes `SECURITY` messages.
- **AC-35.5** — **(rev. 2026-10-13, owner, H8)** Batch clarifications:
  - **Transaction:** each item goes in its own savepoint inside the single transaction. A rejected one rolls back only itself, its quota increment included. A database failure rolls the whole batch back (`5xx`), accepting none.
  - **Size:** a body over `MAX_BATCH_BYTES` → `413 PAYLOAD_TOO_LARGE`. An empty list, or one with more than `MAX_BATCH_ITEMS` → `422 VALIDATION_ERROR` for the whole batch.
  - **An item without `idempotencyKey`** → `422` on that item.
  - **Paused tenant:** the whole batch responds `403 TENANT_SENDING_PAUSED`, without processing any item (AC-34.4).
  - **Rate limit exhausted:** the remaining items get `429`, except idempotent repeats, which return their original result without consuming quota (AC-08.7).
  - **Anomalies:** each accepted item counts towards the volume anomaly (AC-34.1).

### FR-36 — Template category and queue priority (Must, new)

- **AC-36.1** — Every template has a `category`: `SECURITY` (verification, access recovery, codes), `TRANSACTIONAL` (receipts, invoices, payment confirmations) or `NOTICE` (operational notices, summaries).
- **AC-36.2** — The message inherits `priority` from the category on acceptance: `SECURITY` = 0, `TRANSACTIONAL` = 1, `NOTICE` = 2. Claiming orders by `priority, next_attempt_at`.
- **AC-36.3** — *Given* 2,000 eligible `NOTICE` messages, *when* a `SECURITY` message is accepted, *then* the worker claims it on the next round (integration test).
- **AC-36.4** — Changing a template's category requires creating a new template (it is not edited in place).

### FR-37 — Templates per locale (Should, new; ADR-0018)

- **AC-37.1** — Each template version has a `locale` ∈ `SUPPORTED_LOCALES` (default `es-CR,en`); any other value → `422 VALIDATION_ERROR`. There can be at most one `PUBLISHED` version per `(template, locale)` (a partial unique index); publishing archives only the previous one of the same locale.
- **AC-37.2** — *Given* `POST /v1/emails` with `locale = "en"` and a version published in `en`, *then* that version is pinned and the response carries `locale = "en"`.
- **AC-37.3** — *Given* `locale = "en"` without a version published in `en`, *then* `tenant.locale` (default `es-CR`) is used, the response states the effective `locale` and `email_locale_fallback_total` is incremented. With no published version in `tenant.locale` either → `422 TEMPLATE_NOT_PUBLISHED`.
- **AC-37.4** — Without a `locale` in the request, `tenant.locale` is used.
- **AC-37.5** — `formatDate`, `formatNumber` and `formatMoney` use the message's effective locale and `tenant.timezone`. Test: `formatMoney 1500 "CRC"` produces `₡1 500,00` in `es-CR` and `CRC1,500.00` in `en` (the exact format is set by the JDK's library; the test freezes it). **(rev. 2026-10-06)** Frozen with JDK 25: in `es-CR` the thousands separator is a no-break space (U+00A0), and in `en` there is no space between `CRC` and the number.
- **AC-37.6** — **(rev. 2026-10-07, ADR-0020)** All versions that remain published after a publication must declare the same `required` variables (all translations accept the same variables). It is checked on the resulting set; otherwise → `422 VALIDATION_ERROR`.
- **AC-37.7** — In the batch (FR-35) each item can carry its own `locale`.
- **AC-37.8** — **(rev. 2026-10-07, ADR-0020)** *When* `POST /v1/templates/{key}/publish` is called with `{"versions": {"es-CR": 5, "en": 3}}`, *then* those versions are published in a single transaction: each one is validated as in an individual publication, AC-37.6 is checked on the resulting set, the previous version of each locale is archived and one entry per version is audited. If any fails, none is published. Test: with `es-CR` and `en` published with `required: [a]`, jointly publishing two versions with `required: [a, b]` works; publishing them separately gives `422`.
