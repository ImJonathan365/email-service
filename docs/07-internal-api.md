# 07 — Internal API specification

> Revision 2026-10-05. The changes from the 2026-09-20 version and their impact on the products are in §12 (migration notes). Since no version is deployed in production yet, they apply to `/v1` (NFR-18).

Base URL: `https://email.internal.example.com` (local: `http://localhost:8080`).
Swagger UI: `/swagger-ui.html` · OpenAPI: `/v3/api-docs/public` and `/v3/api-docs/admin` · Versioned specs: `contracts/email-service.openapi.json` (products) and `contracts/email-service-admin.openapi.json` (administration, AC-25.3).

## 1. Conventions

- JSON in `camelCase`; ISO-8601 dates in UTC; UUID identifiers (messages, UUIDv7).
- Versioning in the path: `/v1`. Clients **must ignore unknown fields** (NFR-18) and **must treat an unknown `code` by its HTTP class** (4xx = do not retry except 429; 5xx = retry).
- Product authentication: `Authorization: Bearer esk_{env}_{prefix}_{secret}`.
- Administration: `X-Admin-Key: <one of ADMIN_API_KEYS>` on `/admin/v1/**`; reachable only from the private network.
- Headers: `Idempotency-Key` (sends; required by the integration contract), `X-Request-Id` (correlation; always returned).
- Transport: HTTPS (or an encrypted private network). **(rev. 2026-10)** Clients **must** reuse connections (HTTP/1.1 keep-alive or HTTP/2): opening a TLS connection per request adds ~2.5 ms on loopback plus 2 network RTTs (ADR-0014).
- Errors: `application/problem+json` (RFC 9457).
- Cursor pagination: `?limit=20&cursor=<opaque>` → `{"data":[…],"nextCursor":"…"}`.

## 2. Endpoint map

| Method | Path | Description | Scope (FR-33) | FR |
|---|---|---|---|---|
| `POST` | `/v1/emails` | Send a transactional email | `emails:send` | FR-07, FR-08, FR-31, FR-37 |
| `POST` | `/v1/emails/batch` | Send up to 100 emails (rev. 2026-10) | `emails:send` | FR-35 |
| `GET` | `/v1/emails/{id}` | State and events of a send | `emails:read` | FR-19 |
| `GET` | `/v1/emails` | List/filter sends | `emails:read` | FR-20 |
| `POST` | `/v1/emails/{id}/cancel` | Cancel if still queued | `emails:send` | FR-26 |
| `GET` | `/v1/templates` | List templates | `emails:read` | FR-04 |
| `POST` | `/v1/templates` | Create a template | `templates:write` | FR-04 |
| `GET` | `/v1/templates/{key}` | Detail + versions | `emails:read` | FR-04 |
| `POST` | `/v1/templates/{key}/versions` | Create a version (draft) | `templates:write` | FR-04 |
| `PUT` | `/v1/templates/{key}/versions/{version}` | Edit a draft | `templates:write` | FR-04 |
| `DELETE` | `/v1/templates/{key}/versions/{version}` | Delete a draft (rev. 2026-10-06, AC-04.3) | `templates:write` | FR-04 |
| `POST` | `/v1/templates/{key}/versions/{version}/publish` | Publish (immutable) | `templates:write` | FR-05 |
| `POST` | `/v1/templates/{key}/publish` | Publish several locales at once, all or nothing (rev. 2026-10-07, ADR-0020) | `templates:write` | FR-05, FR-37 |
| `POST` | `/v1/templates/{key}/preview` | Render without sending | `templates:write` | FR-06 |
| `GET` | `/v1/suppressions` | List suppressions (own + masked global ones) | `emails:read` | FR-18 |
| `POST` | `/v1/suppressions` | Add a manual suppression | `suppressions:write` | FR-18 |
| `DELETE` | `/v1/suppressions/{email}` | Delete one of the tenant's manual suppressions | `suppressions:write` | FR-18 |
| `POST` | `/webhooks/{provider}` | Provider events (signature, no API key) | — | FR-16 |
| `POST` | `/admin/v1/tenants` | Create a tenant | admin | FR-02 |
| `GET` | `/admin/v1/tenants` | List tenants | admin | FR-02 |
| `PATCH` | `/admin/v1/tenants/{slug}` | Update limits/state/hosts | admin | FR-02 |
| `POST` | `/admin/v1/tenants/{slug}/resume-sending` | Lift a pause: anomaly, manual or domain not verified at the provider (rev. 2026-10-10). Idempotent: without a pause nothing changes | admin | FR-34 |
| `POST` | `/admin/v1/tenants/{slug}/api-keys` | Issue an API key | admin | FR-03, FR-33 |
| `GET` | `/admin/v1/tenants/{slug}/api-keys` | List keys (no secrets) | admin | FR-03 |
| `DELETE` | `/admin/v1/api-keys/{id}` | Revoke a key | admin | FR-03 |
| `GET` | `/admin/v1/suppressions` | List global suppressions (rev. 2026-10) | admin | FR-18 |
| `DELETE` | `/admin/v1/suppressions/{email}` | Delete a global suppression (rev. 2026-10) | admin | FR-18 |
| `POST` | `/admin/v1/data-subjects/erase` | Data-subject erasure (rev. 2026-10) | admin | FR-29 |
| `GET` | `/actuator/health` (+ `/liveness`, `/readiness`) | Health | — | FR-24 |

---

## 3. `POST /v1/emails` — send

```http
POST /v1/emails HTTP/1.1
Authorization: Bearer esk_live_7fA2kQ9z_…
Idempotency-Key: password-reset:3f9c2a71-5d1e-4b8a-9e44-2f7a1e5d8b90
Content-Type: application/json
```

```json
{
  "templateKey": "password-reset",
  "templateVersion": null,
  "to": { "email": "ana@example.com", "name": "Ana Pérez" },
  "cc": [],
  "bcc": [],
  "replyTo": "support@colmena.cr",
  "variables": {
    "firstName": "Ana",
    "resetUrl": "https://app.colmena.cr/reset?t=abc123",
    "expiresInMinutes": 30
  },
  "tags": ["password-reset"],
  "metadata": { "userId": "u_8817", "requestSource": "web" }
}
```

| Field | Type | Req. | Rules |
|---|---|---|---|
| `templateKey` | string | Yes | A template of the tenant; it must have a published version |
| `templateVersion` | int\|null | No | `null` = the latest published (pinned in the message on acceptance) |
| `to.email` / `to.name` | string | Yes / No | A single main address |
| `cc` / `bcc` | array | No | Max. 5 addresses each; suppressed ones are dropped (`droppedRecipients`) |
| `replyTo` | string | No | Default: the tenant's |
| `variables` | object | Yes (`{}` if none) | Validated against `variablesSchema`; `format: uri` ones must be `https` and belong to `allowedLinkHosts` |
| `tags` | string[] | No | Max. 10, `[a-z0-9_-]{1,64}` |
| `metadata` | object | No | Opaque to the service, max. 4 KB |
| `locale` | string | No | **(rev. 2026-10, FR-37)** `es-CR` (the tenant's default) \| `en`; without a published translation it falls back to `tenant.locale` |
| `fromName` | string | No | **(rev. 2026-10, AC-09.5)** The sender's display name, ≤ 80 characters (e.g. "Café Aurora via Acme") |
| `from` | string | No | **(rev. 2026-10-07, AC-09.2)** The sender address; its domain (or a subdomain) must be in the tenant's `allowedFromDomains`, otherwise `403 FROM_DOMAIN_NOT_ALLOWED`. By default, `tenant.fromEmail` |
| `sendAt` | string | No | **(rev. 2026-10, FR-31, Should)** ISO-8601 with a zone; between now and +30 days; the version is pinned on acceptance. **(rev. 2026-10-13)** Up to 60 s in the past (`SEND_AT_PAST_TOLERANCE_SECONDS`) counts as now; the response and `GET /v1/emails/{id}` return it as `sendAt` |
| `attachments` | — | — | **Not available** (FR-30 Won't): rejected with `422 VALIDATION_ERROR` |

**202 Accepted**

```json
{
  "id": "0199c2f1-6b21-7a3e-9c44-2f7a1e5d8b90",
  "status": "QUEUED",
  "templateKey": "password-reset",
  "templateVersion": 3,
  "locale": "es-CR",
  "to": "ana@example.com",
  "droppedRecipients": [],
  "createdAt": "2026-10-05T18:04:11Z"
}
```

- A repeat with the same `Idempotency-Key` and the same body → **200 OK** with the same object (it consumes no rate limit).
- A suppressed `to` recipient → **202** with `status = FAILED`, `failureCode = SUPPRESSED` and `suppressionReason` (`HARD_BOUNCE` \| `COMPLAINT` \| `PROVIDER` \| `MANUAL`); the product can retry with **another** address of the user if it has one (or `422 RECIPIENT_SUPPRESSED` if `SUPPRESSION_REJECT_MODE=reject`). **The client must not retry this case.**

**(rev. 2026-10-07) Behaviour implemented in H4:**
- **Order:**
  1. paused tenant (`403 TENANT_SENDING_PAUSED`);
  2. idempotency;
  3. validations;
  4. resolving and pinning the version;
  5. variables, the URL rule and the trial render;
  6. suppression;
  7. the rate-limit point (FR-21, H7);
  8. `INSERT`.

  Everything happens in one tenant transaction. A repeat with the same `Idempotency-Key` and the same body responds `200` before any validation.
- **`request_hash`:** the SHA-256 of the canonical body; key order and whitespace do not count. The `Idempotency-Key` accepts 1 to 256 printable ASCII characters.
- **`sendAt`** (rev. 2026-10-13, H8):
  - the message waits in `QUEUED` until that time;
  - the template version, the suppression and the quota are resolved on acceptance, and the suppression is checked again on sending;
  - the 23 h window starts at the first attempt (AC-31.4);
  - if the tenant is paused at that time, the message stays `QUEUED` and goes out when it resumes;
  - errors: out of range → `422 SEND_AT_OUT_OF_RANGE`; without a zone or unreadable → `422 VALIDATION_ERROR`.
- **`attachments`:** responds `422 VALIDATION_ERROR` (FR-30).
- **Suppressed `to` recipient:**
  - the message is created as `FAILED` with `failureCode = SUPPRESSED` and `suppressionReason`;
  - its `variables` are not stored, because it is never sent;
  - a repeat returns the same `suppressionReason`.
- **`droppedRecipients`:** lists the `cc`/`bcc` addresses dropped because of a suppression.
- **Size limit:** `MAX_REQUEST_BYTES` (`413`) applies to `/v1/emails`. The template endpoints have a fixed cap of 1 MiB, because a version can carry 256 KB of HTML plus the text and the schema.
- **Recipient allowlist:** `ALLOWED_RECIPIENT_DOMAINS` accepts the exact domain or a subdomain, like `allowedFromDomains` and `allowedLinkHosts`.

**Errors:** `400 MALFORMED_REQUEST`, `401 UNAUTHENTICATED`, `403 TENANT_SUSPENDED` / `TENANT_SENDING_PAUSED` / `FROM_DOMAIN_NOT_ALLOWED` / `RECIPIENT_NOT_ALLOWED_IN_ENV` / `INSUFFICIENT_SCOPE` / `IP_NOT_ALLOWED`, `404 TEMPLATE_NOT_FOUND`, `409 IDEMPOTENCY_KEY_REUSED`, `413 PAYLOAD_TOO_LARGE`, `422 VALIDATION_ERROR` / `TEMPLATE_VARIABLES_INVALID` / `TEMPLATE_NOT_PUBLISHED` / `INVALID_EMAIL_ADDRESS` / `HEADER_INJECTION_DETECTED` / `UNSAFE_URL` / `SEND_AT_OUT_OF_RANGE` / `RECIPIENT_SUPPRESSED`, `429 RATE_LIMITED`, `503 SERVICE_UNAVAILABLE`.

## 4. `POST /v1/emails/batch` — send in a batch (rev. 2026-10, FR-35)

```json
{
  "items": [
    { "idempotencyKey": "notice:maint-2026-10-12:u_1", "templateKey": "maintenance-notice",
      "to": { "email": "a@example.com" }, "variables": { "date": "2026-10-12T02:00:00-06:00" } },
    { "idempotencyKey": "notice:maint-2026-10-12:u_2", "templateKey": "maintenance-notice",
      "to": { "email": "not-an-email" }, "variables": { "date": "2026-10-12T02:00:00-06:00" } }
  ]
}
```

**200 OK**

```json
{
  "results": [
    { "index": 0, "httpStatus": 202, "id": "0199c2f1-…", "status": "QUEUED" },
    { "index": 1, "httpStatus": 422, "code": "INVALID_EMAIL_ADDRESS" }
  ]
}
```

- At most 100 items and 1 MB; each item accepts `locale` and `sendAt`. `idempotencyKey` is required per item (the `Idempotency-Key` header is ignored on this endpoint).
- **(rev. 2026-10-13, H8)** Batch details (AC-35.5 in `02`):
  - **Errors for the whole batch:**
    - a body over `MAX_BATCH_BYTES` → `413 PAYLOAD_TOO_LARGE`;
    - an empty list, or one with more than `MAX_BATCH_ITEMS` → `422 VALIDATION_ERROR`;
    - paused tenant → `403 TENANT_SENDING_PAUSED`;
    - database failure → `5xx`, accepting no item.
  - **Result per item:**
    - `202` if it was created, `200` if it is an idempotent repeat, or the error code `POST /v1/emails` would give (`422`, `409`, `429`…);
    - an item without `idempotencyKey`, or one that is not an object, → `422` on that item.
  - **Optional result fields:** `failureCode` and `suppressionReason` (suppressed recipient) and `droppedRecipients`.
  - **Rate limit exhausted mid-batch:** the rest get `429`, except idempotent repeats, which return their original result.
- Accepted items are inserted in one transaction (measured: ~0.25 ms/message against ~0.75 ms with one transaction per message).
- It is not a campaign tool: `NOTICE` sends remain subject to the quota, anomaly detection and the lowest priority (ADR-0013).

## 4.1 `POST /v1/emails/{id}/cancel` — cancel (rev. 2026-10-13, FR-26, H8)

- **What it does:** cancels a message that is still `QUEUED`, including one scheduled with `sendAt` or one of a paused tenant.
- **Response:** `200` with the same body as `GET /v1/emails/{id}`: `status = CANCELED`, `failureCode = CANCELED_BY_CLIENT`.
- **Errors:**
  - in any other state, including a message the worker has just claimed, `409 MESSAGE_NOT_CANCELABLE`;
  - a non-existent id or one of another tenant, `404 MESSAGE_NOT_FOUND`.
- **Scope:** requires `emails:send`.

## 5. `GET /v1/emails/{id}` — state

```json
{
  "id": "0199c2f1-6b21-7a3e-9c44-2f7a1e5d8b90",
  "status": "DELIVERED",
  "category": "SECURITY",
  "templateKey": "password-reset",
  "templateVersion": 3,
  "to": "ana@example.com",
  "from": "no-reply@colmena.cr",
  "tags": ["password-reset"],
  "metadata": { "userId": "u_8817" },
  "attempts": 1,
  "provider": "resend",
  "providerMessageId": "re_3Hk9…",
  "failureCode": null,
  "failureDetail": null,
  "createdAt": "2026-10-05T18:04:11Z",
  "sentAt": "2026-10-05T18:04:13Z",
  "deliveredAt": "2026-10-05T18:04:19Z",
  "firstOpenedAt": null,
  "openCount": 0,
  "clickCount": 0,
  "events": [
    { "type": "SENT",      "occurredAt": "2026-10-05T18:04:13Z" },
    { "type": "DELIVERED", "occurredAt": "2026-10-05T18:04:19Z" }
  ]
}
```

It includes `renderedSubject` / `renderedHtml` only if the tenant has `storeRenderedContent = true` and they have not been purged (`x-sensitive` variables as `[REDACTED]`). An `id` of another tenant → `404 MESSAGE_NOT_FOUND`.

**(rev. 2026-10-12) Implemented in H7:**
- **Fields added to the example:**
  - `locale` (effective), `cc`, `bcc` and `firstClickedAt`;
  - `lastError`: the code of the last attempt, visible also while the message is still retrying; `failureCode` only exists in `FAILED`;
  - `renderedText`, with the same conditions as `renderedSubject` and `renderedHtml`.
- **Rendered content:** the worker stores it only with `storeRenderedContent = true`. It renders a second time with the `x-sensitive` variables replaced by `[REDACTED]`, so the stored copy never contains sensitive values. What was sent did carry them. If the tenant disables the flag, it stops being shown.
- **`events`:** the message's webhook history, in order (`type`, `occurredAt`).
- **Scope:** requires `emails:read`; without it, `403`. A malformed `id` → `404`.

## 6. `GET /v1/emails` — listing

`?status=FAILED&templateKey=password-reset&to=ana@example.com&tag=password-reset&createdAfter=2026-09-01T00:00:00Z&createdBefore=2026-10-01T00:00:00Z&limit=20&cursor=…`

```json
{
  "data": [ { "id": "…", "status": "SENT", "to": "ana@example.com", "templateKey": "password-reset", "createdAt": "…" } ],
  "nextCursor": "eyJjcmVhdGVkQXQiOiIyMDI2…"
}
```

**(rev. 2026-10-12)** Listing rules:
- **Order and size:** newest to oldest; `limit` from 1 to 100 (20 by default).
- **Filters:**
  - `to` is case-insensitive;
  - `tag` is a single tag;
  - `createdAfter` is inclusive and `createdBefore` exclusive, both ISO-8601;
  - a non-existent `templateKey` returns an empty list.
- **Cursor:** `cursor` is the previous `nextCursor`. Pages neither repeat nor leave gaps even if new messages arrive between one request and the next.
- **Errors:** a malformed parameter (unknown `status`, invalid date, a foreign cursor) → `422 VALIDATION_ERROR`.

## 7. Templates

**Create a template** — `POST /v1/templates`

```json
{ "key": "password-reset", "name": "Password reset", "description": "…", "category": "SECURITY" }
```

**Create a version** — `POST /v1/templates/password-reset/versions` (one version per locale; `locale` defaults to `es-CR`)

```json
{
  "locale": "en",
  "subjectTemplate": "{{firstName}}, reset your Colmena password",
  "htmlTemplate": "<html><body style=\"font-family:sans-serif\"><img src=\"https://cdn.colmena.cr/logo.png\" alt=\"Colmena\" height=\"32\"><h1>Hi {{firstName}}</h1><p>The link expires in {{expiresInMinutes}} minutes.</p><p><a href=\"{{resetUrl}}\">Reset password</a></p></body></html>",
  "textTemplate": "Hi {{firstName}}. Reset your password: {{resetUrl}} (expires in {{expiresInMinutes}} minutes)",
  "variablesSchema": {
    "required": ["firstName", "resetUrl", "expiresInMinutes"],
    "properties": {
      "firstName": { "type": "string", "maxLength": 80 },
      "resetUrl": { "type": "string", "format": "uri", "x-sensitive": true },
      "expiresInMinutes": { "type": "integer" }
    }
  }
}
```

**201 Created** → `{ "version": 4, "status": "DRAFT", "createdAt": "…" }`

**Publish** — `POST /v1/templates/password-reset/versions/4/publish` → `200` with `status = PUBLISHED`. From then on the version is immutable (`409 VERSION_IMMUTABLE` on any edit) and the previously published version of the same locale becomes `ARCHIVED`. Publishing requires `variablesSchema`.

**Preview** — `POST /v1/templates/password-reset/preview`

```json
{ "templateVersion": 4, "variables": { "firstName": "Ana", "resetUrl": "https://app.colmena.cr/reset?t=x", "expiresInMinutes": 30 } }
```

```json
{ "templateVersion": 4, "locale": "en", "status": "DRAFT",
  "subject": "Ana, reset your Colmena password", "html": "<html>…</html>", "text": "Hi Ana…", "warnings": [] }
```

**Joint publication (rev. 2026-10-07, ADR-0020)** — `POST /v1/templates/password-reset/publish`

```json
{ "versions": { "es-CR": 5, "en": 3 } }
```

**200 OK** → `{ "published": [ { "version": 3, "locale": "en", "status": "PUBLISHED", "publishedAt": "…" }, { "version": 5, "locale": "es-CR", … } ] }`.

All versions are published in one transaction, or none:
- each key must be the `locale` of its version; otherwise `422 VALIDATION_ERROR` on `versions.{locale}`;
- schema and URL errors are reported with the prefix `versions.{locale}.`;
- AC-37.6 is validated on what remains published after the operation.

**(rev. 2026-10-06) Behaviour implemented in H3:**
- **Preview:**
  - `templateVersion` can be a draft; that is how it is checked before publishing.
  - Without `templateVersion`, the published version of the requested `locale` (optional) is used; if there is none, that of `tenant.locale`; if there is none either, `422 TEMPLATE_NOT_PUBLISHED`.
  - It applies the same rules as a send: variables against the schema (`422 TEMPLATE_VARIABLES_INVALID`), `https` URL variables in `allowedLinkHosts` (`422 UNSAFE_URL`) and render errors (`422 TEMPLATE_VARIABLES_INVALID`, without repeating the value).
  - It creates no messages and sends nothing.
- **Reading:**
  - `GET /v1/templates` returns `{"data": [...]}` with `published: [{version, locale}]` per template.
  - `GET /v1/templates/{key}` includes the content of each version (`subjectTemplate`, `htmlTemplate`, `textTemplate`, `variablesSchema`), so that tools can detect whether a locale changed.
- **Drafts:**
  - `DELETE /v1/templates/{key}/versions/{version}` deletes a draft (`204`); on a published or archived version, `409 VERSION_IMMUTABLE`.
  - `locale` is `es-CR` by default when creating a version.
- **Variables:** those not declared in `variablesSchema` are accepted and ignored, as in JSON Schema.
- **HTML linter:** besides `href` and `src`, it requires `format: uri` in all URL attributes (`srcset`, `background`, `poster`, `action`, `formaction`, `cite`, `data`, `longdesc`, `usemap`, `xlink:href`). Since the email carries the source as is, it also checks misplaced tags that an HTML5 parser would discard.
- **Subject escaping:** the subject loses `\r` and `\n` (they are removed, not replaced by spaces).

**Template rules (rev. 2026-10, ADR-0011):**
- Logic-less Handlebars. `{{variable}}` escapes HTML **only** in `htmlTemplate`; in `subjectTemplate` and `textTemplate` there is no HTML escaping (the subject also loses CR/LF).
- Rejected with `422 UNSAFE_TEMPLATE_CONSTRUCT`: `{{{ }}}`, `{{& }}`, partials (`{{> }}`), any helper outside the whitelist, variables inside `<script>`, `<style>`, `style=`, `on*=` or unquoted attributes, and the `<script>`, `<iframe>`, `<object>`, `<embed>` and `<form>` tags.
- Every variable in `href`/`src` must be declared with `"format": "uri"`.
- Allowed helpers: `if`, `unless`, `each`, `with`, `formatDate value [pattern]`, `formatNumber value [decimals]` and `formatMoney value currency` (`CRC`, `USD`). **(rev. 2026-10-06)** `formatDate` accepts a date (`2026-10-12`) or a date and time with a zone (ISO-8601); `pattern` is `short`, `medium` (default), `long`, `full` or a `DateTimeFormatter` pattern. `formatNumber` accepts 0 to 6 decimals. Named parameters (`key=value`), subexpressions and block parameters (`as |x|`) are not accepted. They use the message's effective locale (`es-CR` or `en`) and `tenant.timezone`; there is no "current date" helper (rendering must be deterministic).
- Open and click tracking: only in the `NOTICE` category and only if `trackingEnabled = true`.

## 8. Suppressions

```http
GET    /v1/suppressions?reason=HARD_BOUNCE&limit=50
POST   /v1/suppressions                { "email": "bad@example.com", "note": "requested by the user" }
DELETE /v1/suppressions/bad@example.com
```

- `GET` returns `{ email | emailMasked, scope: TENANT|GLOBAL, reason, createdAt }`; global ones are masked.
  - **(rev. 2026-10-11)** The tenant's own ones also include `note`. Global ones show neither the note nor the source tenant.
  - `limit` goes from 1 to 100 (50 by default) and `reason` is optional. `cursor` is the previous page's `nextCursor`; a cursor this service did not issue → `422`.
- `POST` always creates `reason = MANUAL`, `scope = TENANT`.
  - **(rev. 2026-10-11)** It responds `201` with the created suppression, or `200` with the existing one if the tenant already had that address suppressed: it is idempotent and does not change the note.
  - An invalid address → `422 VALIDATION_ERROR`.
- `DELETE` only deletes the tenant's own `MANUAL` ones; on a global one → `403 ADMIN_REQUIRED`.
  - **(rev. 2026-10-11)** `204` if it was deleted (audited as `SUPPRESSION_DELETED`, without the address).
  - `404 RESOURCE_NOT_FOUND` if the tenant does not have that suppression and no global one exists either.
- **(rev. 2026-10-11)** Administering global ones:
  - `GET /admin/v1/suppressions?reason&limit&cursor` lists them with the full address and the origin (`sourceTenantSlug`, `sourceMessageId`);
  - `DELETE /admin/v1/suppressions/{email}` deletes the global one (`204`, audited; `404` if it does not exist). It must also be removed in the provider's dashboard; otherwise the provider keeps skipping it and reports it again.

## 9. Incoming webhooks (rev. 2026-10)

```http
POST /webhooks/resend
Content-Type: application/json
svix-id: msg_2f9…
svix-timestamp: 1789412651
svix-signature: v1,k8s9…
```

- The signature is verified on the **raw body**, before parsing the JSON, with the endpoint's secret (`MAIL_WEBHOOK_SIGNING_SECRET`), with a ±5 min window on `svix-timestamp`. `svix-id` is stored as `provider_event_id` to deduplicate.
- Responses: `204` (processed, duplicate or orphan), `401` (invalid signature or outside the window), `400` (body unreadable after verifying the signature), `503` (database unavailable; the provider will retry). **Never a 5xx** for an event that cannot be correlated.
- **(rev. 2026-10-11)** Other details:
  - an unknown provider, or Resend without `MAIL_WEBHOOK_SIGNING_SECRET` configured (local development), → `404`;
  - a body over 256 KB → `413`;
  - the `401` does not say what failed; the reason goes only to the metric `email_webhook_rejected_total{reason}` and to a WARN per IP and minute.
- The exact format depends on the provider: the `WebhookVerifier` port (`provider` module) has one implementation per provider; the `events` module consumes it.
- Event mapping: `02` §2.1.

## 10. Administration

**Create a tenant** — `POST /admin/v1/tenants`

```json
{
  "slug": "colmena",
  "name": "Colmena",
  "fromEmail": "no-reply@colmena.cr",
  "fromName": "Colmena",
  "allowedFromDomains": ["colmena.cr"],
  "allowedLinkHosts": ["colmena.cr", "app.colmena.cr", "cdn.colmena.cr"],
  "locale": "es-CR",
  "timezone": "America/Costa_Rica",
  "rateLimitPerMinute": 120,
  "dailyQuota": 3000,
  "retentionDays": 90,
  "storeRenderedContent": false
}
```

**Issue an API key** — `POST /admin/v1/tenants/colmena/api-keys`

```json
{ "name": "production 2026", "expiresAt": "2027-10-05T00:00:00Z",
  "scopes": ["emails:send", "emails:read"], "allowedCidrs": ["10.20.0.0/16"] }
```

**201 Created** (the secret is shown **only once**):

```json
{
  "id": "1f2e…",
  "name": "production 2026",
  "apiKey": "esk_live_7fA2kQ9z_3xK9mQ2vL8pR4tW6yB1nC5dF7gH0jA2sE4uI6oP8qZr",
  "keyPrefix": "esk_live_7fA2kQ9z",
  "scopes": ["emails:send", "emails:read"],
  "allowedCidrs": ["10.20.0.0/16"],
  "expiresAt": "2027-10-05T00:00:00Z",
  "warning": "Store this key now: it cannot be shown again."
}
```

**Revoke** — `DELETE /admin/v1/api-keys/{id}` → `204` (idempotent: revoking an already revoked key also gives `204`; a non-existent id, `404 RESOURCE_NOT_FOUND`).

**(rev. 2026-10-06) Behaviour implemented in H2:**
- `PATCH /admin/v1/tenants/{slug}` is a partial update: a missing or `null` field keeps its value (that is why `replyTo` cannot be emptied with a `PATCH`). Lists (`allowedFromDomains`, `allowedLinkHosts`) are replaced as a whole and normalized to lowercase. `status` accepts `ACTIVE` or `SUSPENDED`.
- **(rev. 2026-10-12)** `anomalyOverrides` changes that tenant's FR-34 thresholds (AC-34.5): `{ "volumeMultiplier": 4, "minHourly": 200, "bouncePauseRate": 0.05, "complaintPauseRate": 0.002 }`.
  - A missing field uses the service's value.
  - `minHourly` only raises the minimum: one lower than `ANOMALY_MIN_HOURLY` has no effect.
  - Ranges: `volumeMultiplier` from 1 to 100; the rates, strictly between 0 and 1.
  - The object sent replaces the previous one and appears in the response.
- `GET /admin/v1/tenants` and `GET /admin/v1/tenants/{slug}/api-keys` respond `{"data": [...], "nextCursor": null}`.
- Every access to `/admin/v1/**` is audited: modifications with their action (`TENANT_CREATED`, `TENANT_UPDATED` with the names of the changed fields, `TENANT_SUSPENDED`, `TENANT_REACTIVATED`, `API_KEY_ISSUED`, `API_KEY_REVOKED`), reads as `ADMIN_ACCESS` and rejections as `ADMIN_ACCESS_DENIED`.
- A path that exists with another method responds `405 METHOD_NOT_ALLOWED` with the `Allow` header; a body that is not JSON, `415 UNSUPPORTED_MEDIA_TYPE` (added to the catalogue on 2026-10-06; compatible under NFR-17).

**Lift a pause** — `POST /admin/v1/tenants/colmena/resume-sending` `{ "note": "legitimate spike: maintenance notice" }` → `200`.

**Data-subject erasure** — `POST /admin/v1/data-subjects/erase` `{ "email": "ana@example.com", "reference": "ARCO-2026-014" }` → `200 { "deleted": { "email_message": 12, "email_event": 31, "email_message_tag": 9 }, "copiesRemoved": 2, "suppressionsAnonymized": 1 }`.

**(rev. 2026-10-12)** Data-subject erasure details:
- **Response content:**
  - no `email_attachment`, because that table does not exist (ADR-0015);
  - `email_message_tag` counts the rows that went away in cascade with the messages;
  - `copiesRemoved` counts the messages from which the address was removed in `cc`/`bcc`.
- **Comparisons:** case-insensitive.
- **Idempotency:** a second run returns zeros.
- **Validation:** `reference` is required (≤ 200 characters, no line breaks).

## 11. Error format

```json
{
  "type": "https://email-service.internal/problems/rate-limited",
  "title": "Rate limit exceeded",
  "status": 429,
  "detail": "Tenant colmena exceeded 120 requests per minute.",
  "instance": "/v1/emails",
  "code": "RATE_LIMITED",
  "requestId": "0199c2f1-6b21-7a3e-9c44-2f7a1e5d8b90"
}
```

### `code` catalogue (closed enum)

| HTTP | `code` | Meaning |
|---|---|---|
| 400 | `MALFORMED_REQUEST` | Invalid JSON |
| 401 | `UNAUTHENTICATED` | The key is missing, invalid, revoked or expired |
| 403 | `ADMIN_REQUIRED` | An administration endpoint or resource without an admin credential |
| 403 | `INSUFFICIENT_SCOPE` | **(rev. 2026-10)** The key does not have the required scope |
| 403 | `IP_NOT_ALLOWED` | **(rev. 2026-10)** Source IP outside the key's `allowedCidrs` |
| 403 | `TENANT_SUSPENDED` | Tenant suspended |
| 403 | `TENANT_SENDING_PAUSED` | **(rev. 2026-10)** Sending paused because of an anomaly (FR-34) |
| 403 | `FROM_DOMAIN_NOT_ALLOWED` | Sender outside `allowedFromDomains` |
| 403 | `RECIPIENT_NOT_ALLOWED_IN_ENV` | Recipient outside the environment's allowlist |
| 404 | `TEMPLATE_NOT_FOUND` / `MESSAGE_NOT_FOUND` / `RESOURCE_NOT_FOUND` | Does not exist (or belongs to another tenant) |
| 405 | `METHOD_NOT_ALLOWED` | **(rev. 2026-10-06)** The resource exists but does not accept that method (includes the `Allow` header) |
| 409 | `IDEMPOTENCY_KEY_REUSED` | Same key, different body |
| 409 | `TEMPLATE_KEY_TAKEN` / `TENANT_SLUG_TAKEN` | Identifier in use |
| 409 | `VERSION_IMMUTABLE` | Editing a published version |
| 409 | `MESSAGE_NOT_CANCELABLE` | The message is no longer queued |
| 413 | `PAYLOAD_TOO_LARGE` | Exceeds `MAX_REQUEST_BYTES` or, on `/v1/emails/batch`, `MAX_BATCH_BYTES` |
| 415 | `UNSUPPORTED_MEDIA_TYPE` | **(rev. 2026-10-06)** The body is not `application/json` |
| 422 | `VALIDATION_ERROR` | Invalid fields (includes `errors[]` per field) |
| 422 | `INVALID_EMAIL_ADDRESS` | Malformed address |
| 422 | `HEADER_INJECTION_DETECTED` | CR/LF in a header field |
| 422 | `TEMPLATE_VARIABLES_INVALID` | Missing variables, of the wrong type or that break the render |
| 422 | `TEMPLATE_NOT_PUBLISHED` | No published version |
| 422 | `TEMPLATE_SYNTAX_ERROR` / `UNSAFE_TEMPLATE_CONSTRUCT` | Invalid or unsafe template |
| 422 | `UNSAFE_URL` | **(rev. 2026-10)** URL variable not `https` or outside `allowedLinkHosts` (with `APP_ENV=local`, `localhost` and `*.localhost` are accepted, ADR-0021) |
| 422 | `ATTACHMENT_INVALID` | **Reserved** (FR-30 Won't); not emitted in the MVP |
| 422 | `SEND_AT_OUT_OF_RANGE` | **(rev. 2026-10)** `sendAt` more than `SEND_AT_PAST_TOLERANCE_SECONDS` (60 s) in the past or more than 30 days ahead |
| 422 | `RECIPIENT_SUPPRESSED` | Only in `reject` mode |
| 429 | `RATE_LIMITED` | Per-minute limit or daily quota (includes `Retry-After`). **(rev. 2026-10-12)** Also an IP with too many `401`s, only on requests without a known key prefix (`08` §4) |
| 500 | `INTERNAL_ERROR` | Unexpected failure (no internal details) |
| 503 | `SERVICE_UNAVAILABLE` | Database unavailable |

## 12. Integration contract for the products (rev. 2026-10)

```bash
curl -sS -X POST https://email.internal.example.com/v1/emails \
  -H "Authorization: Bearer $EMAIL_SERVICE_API_KEY" \
  -H "Idempotency-Key: password-reset:$RESET_REQUEST_ID" \
  -H "Content-Type: application/json" \
  -d '{
    "templateKey": "password-reset",
    "to": { "email": "ana@example.com", "name": "Ana" },
    "variables": { "firstName": "Ana", "resetUrl": "https://app.colmena.cr/reset?t=abc", "expiresInMinutes": 30 }
  }'
```

Rules for the client:

1. **Idempotency-Key = the identifier of the domain event** that causes the email (`password-reset:{resetRequestId}`, `receipt:{invoiceId}`, `payment-verified:{verificationId}`). Never user+date (it collapses legitimate requests from the same day) nor a random value per attempt (it does not deduplicate).
2. Treat `202` and `200` as success; do not wait for delivery. A `202` with `status = FAILED` and `failureCode = SUPPRESSED` is **not** retried.
3. Retry only on `5xx` and `429` (honouring `Retry-After`), with backoff and the **same** `Idempotency-Key`. On an unknown `code`, act according to its HTTP class.
4. Client timeouts: connect ≤ 2 s, read ≤ 5 s. Reuse connections (keep-alive or HTTP/2).
5. If the email is business-critical, record it first in the product's own outbox and call the email-service from that outbox, so it is not lost if the email-service does not respond.
6. Do not store the HTML: it lives in the email-service. Keep the returned `id` to check the state later.
7. Use one API key per environment; the production one only with `emails:send` and `emails:read`.
8. There are no attachments: documents and exports are sent as an expiring signed link (a `format: uri` variable, host in `allowedLinkHosts`).
9. Send `locale` only if the user prefers English; if there is no translation, the email goes out in Spanish and the response says so.
10. On `failureCode = SUPPRESSED` in a `SECURITY` email, offer the user an alternative channel (another registered address); do not retry to the same address.

### 12.1 Migration notes from the 2026-09-20 specification

| Change | Impact on the client | Compatibility |
|---|---|---|
| Recommended idempotency keys: from `signup-{userId}-{date}` to an event id | Change how the key is derived | No format change |
| New codes (`INSUFFICIENT_SCOPE`, `IP_NOT_ALLOWED`, `TENANT_SENDING_PAUSED`, `UNSAFE_URL`, `ATTACHMENT_INVALID`, `SEND_AT_OUT_OF_RANGE`; **(rev. 2026-10-06)** `METHOD_NOT_ALLOWED`, `UNSUPPORTED_MEDIA_TYPE`) | Treat by their HTTP class | Compatible if §1 is followed |
| `droppedRecipients`, `locale` and `suppressionReason` in the response | Ignore or use | Compatible (new fields) |
| Optional `locale`, `fromName`, `sendAt` fields in the request | Use them if needed | Compatible |
| URL variables validated against `allowedLinkHosts` | Register the product's hosts in the tenant | **Incompatible** if the product sends links to unregistered hosts → configure before integrating |
| Templates with `category` and a `variablesSchema` required to publish | Affects whoever publishes templates (administration), not sending | Templates are created from scratch in Phase 0 |
| API key scopes | Product keys are issued with `emails:send`, `emails:read` | No impact if the right key is used |
| Events `BOUNCED_HARD`/`BOUNCED_SOFT` → `BOUNCED`/`DELIVERY_DELAYED` | Adjust if the product reads `events[].type` | Renamed before the first deployed version |
| `GET /v1/emails`: date filters `createdAfter`/`createdBefore` | It already was like that in this specification; FR-20 is aligned | — |
