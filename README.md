# email-service

A self-hosted, multi-tenant service for **transactional** email: password resets, verification links, receipts, notifications. Applications call one HTTP API. The service validates and renders the email from a versioned template, queues it in PostgreSQL, sends it through an email provider and tracks what happens to it afterwards. It is not a marketing or bulk-email platform.

The design documentation, in Spanish, lives in [`docs/`](docs/README.md) and is the source of truth. Rules for AI assistants are in [`AGENTS.md`](AGENTS.md). Project status and a guide for using the service from other projects in local development: [`docs/12-estado-y-uso-local.md`](docs/12-estado-y-uso-local.md).

## What it does

- **One service, many tenants.** Each application is a tenant with its own:
  - API keys, with scopes;
  - sender identity and allowed domains;
  - templates;
  - rate limits and quotas.

  Isolation is enforced twice: every query filters by `tenant_id`, and PostgreSQL Row Level Security rejects any row outside the current tenant.
- **Asynchronous sending.** `POST /v1/emails` validates the request, stores the message and answers `202` in milliseconds. A worker sends it later. A slow or unavailable provider never blocks the caller.
- **Templates kept as code.** Logic-less Handlebars templates are versioned per locale. Published versions are immutable, and each message is pinned to the version it was accepted with. Escaping is context-aware, and URL variables must point to the tenant's own hosts, so a leaked key cannot turn a legitimate template into a phishing link.
- **No duplicates in practice.**
  - Clients send an `Idempotency-Key`, unique per tenant in the database.
  - The worker sends every message to the provider with `Idempotency-Key = message.id`.
  - Delivery is at least once, and the provider deduplicates retries inside its idempotency window, so a retry does not produce a second email.
- **Delivery tracking.**
  - Signed provider webhooks (Svix) move each message through its states: `QUEUED`, `SENDING`, `SENT`, `DELIVERED`, `BOUNCED`, `COMPLAINED`, `FAILED` or `CANCELED`.
  - Precedence rules keep late or out-of-order events from moving a message backwards.
- **Suppression.**
  - Hard bounces, complaints and provider suppressions block an address for every tenant.
  - Manual suppressions apply to one tenant.
  - `to`, `cc` and `bcc` are all checked.
- **Protection and operations:**
  - per-tenant rate limits and daily quotas;
  - automatic pause on volume, bounce or complaint anomalies;
  - alerts as structured log lines, health indicators and metrics;
  - retention purges and data-subject erasure.
- **Batch and scheduled sending.**
  - Up to 100 emails per request, each item judged on its own.
  - `sendAt` up to 30 days ahead, and a queued message can be cancelled.

## How it works

```
client ── POST /v1/emails ──> api ──> email_message (PostgreSQL) <── worker ──> provider (Resend)
                                                                                     │
client <── GET /v1/emails/{id} ── api <── POST /webhooks/resend ── delivery events ──┘
```

- **One artifact, several roles.** One deployable runs as `APP_ROLE=api`, `worker`, `all` or `migrate`, against one PostgreSQL database. There is no broker, cache or second service.
- **The queue is a table.**
  - Workers claim messages with `SELECT … FOR UPDATE SKIP LOCKED` in a short transaction, ordered by priority (`SECURITY` > `TRANSACTIONAL` > `NOTICE`) and then by due time.
  - They call the provider with no row locks held, and close each message only if they still own its lock.
  - Retries use backoff, within a 23-hour window.
- **The provider sits behind a port.** `EmailSender` and `WebhookVerifier` hide the provider:
  - Resend over its HTTP API outside development;
  - SMTP to Mailpit for local development only. The application refuses to start with SMTP in production.
- **Secrets stay out of the database.** They come from environment variables or a secrets manager. The database stores only API key prefixes and SHA-256 hashes.

Stack: Java 25, Spring Boot 4.1 (virtual threads), Spring Data JDBC, Flyway, PostgreSQL 18 and Gradle. The API contract is [`contracts/email-service.openapi.json`](contracts/email-service.openapi.json).

## Running locally

Requirements:
- Docker with Compose;
- for the tests and scripts: Java 25 and `jq`, both declared in [`mise.toml`](mise.toml) (`mise install`).

```bash
cp .env.example .env            # local placeholders only; .env is git-ignored
docker compose up --build       # app (APP_ROLE=all) + PostgreSQL 18 with 3 roles + Mailpit
./scripts/seed-local.sh         # tenant "demo", a product key, an operator key and the templates in templates/demo
./scripts/smoke-test.sh         # POST /v1/emails -> queue -> worker -> SMTP -> Mailpit, checked through its API
./scripts/simulate-event.sh <messageId> delivered   # simulated, signed Resend event (or bounced, complained, delayed)
```

Seed and data behaviour:
- The seed is idempotent. If the tenant already exists, it changes nothing and issues no keys; `--new-keys` issues a new pair.
- `SLUG=my-app ./scripts/seed-local.sh` creates a tenant for another application.
- `docker compose down -v` deletes the database, including tenants, keys and templates.

Applications running in Docker reach the service at `http://email-service:8080` through the `email-service-net` network ([`docs/12`](docs/12-estado-y-uso-local.md)).

| URL | What it is |
|---|---|
| http://localhost:8080/swagger-ui.html | Swagger UI (`public` and `admin` groups) |
| http://localhost:8080/actuator/health/readiness | Readiness (depends on the database only) |
| http://localhost:8025 | Mailpit (development SMTP; delivers nothing outside) |

Tests: `./gradlew build`. They need Docker, because Testcontainers runs PostgreSQL with the same roles and RLS as production. The tests never send a real email: the provider is stubbed with WireMock.

## Sending through Resend from staging

Local development always sends over SMTP to Mailpit. Resend is used from staging onwards (ADR-0016). The Resend account is prepared by the owner; the service only needs the variables.

1. **A Resend team for staging,** separate from production, so a staging key or domain never affects production's reputation.
2. **Sender domain:** in *Domains → Add domain*, add a subdomain of your own (for example `mail-staging.<domain>`). Publish the DNS records Resend shows:
   - **SPF:** the `TXT` and `MX` records of the bounce subdomain (`send.…`);
   - **DKIM:** the `TXT` record `resend._domainkey…`;
   - **DMARC:** Resend does not create it. Add `_dmarc.<subdomain>` `TXT "v=DMARC1; p=none; rua=mailto:<mailbox>"`, and tighten it (`quarantine` / `reject`) once the reports are clean.

   Wait until the domain shows as **Verified**.
3. **API key:** in *API Keys → Create*, with **Sending access**, restricted to that domain. It is shown once: store it straight in the staging secrets manager.
4. **Staging deployment variables** (secrets manager, never the repository):

   | Variable | Value |
   |---|---|
   | `APP_ENV` | `staging` |
   | `MAIL_PROVIDER` | `resend` |
   | `RESEND_API_KEY` | the key from step 3 (secret) |
   | `ALLOWED_RECIPIENT_DOMAINS` | recipient domains allowed in staging (required) |
   | `WORKER_PROVIDER_RPS` | the Resend account's requests-per-second limit divided by the number of worker instances |
   | `CIRCUIT_BREAKER_FAILURES` / `CIRCUIT_BREAKER_OPEN_SECONDS` | `5` / `60` (defaults) |

   `RESEND_API_URL` changes only for tests against a stub, and outside `local` it must be `https`.
5. **Tenant:** its `fromEmail` must use the verified domain, which also goes in `allowedFromDomains`:
   ```bash
   curl -X PATCH "$BASE/admin/v1/tenants/demo" -H "X-Admin-Key: $ADMIN_KEY" -H 'Content-Type: application/json' \
     -d '{"fromEmail": "no-reply@mail-staging.<domain>", "allowedFromDomains": ["mail-staging.<domain>"]}'
   ```

**When something is misconfigured** (docs/05 §4):
- **Revoked key or missing permission** (`401`, a key-related `403`, `404`/`405`):
  - the circuit breaker opens on the first error, and no message uses up attempts;
  - an error log and `email_provider_config_error_total{error}` go up;
  - once the key is fixed, the next probe (60 s) resumes sending;
  - messages expire 23 h after their first attempt (`RETRY_WINDOW_EXCEEDED`).
- **Unverified domain** (`403 validation_error`):
  - only that tenant is paused (`pause_reason = PROVIDER_DOMAIN`, audited);
  - its messages stay `QUEUED`, and the API answers `403 TENANT_SENDING_PAUSED`;
  - once the domain is verified, resume it with:
  ```bash
  curl -X POST "$BASE/admin/v1/tenants/demo/resume-sending" -H "X-Admin-Key: $ADMIN_KEY" \
    -H 'Content-Type: application/json' -d '{"note": "domain verified in Resend"}'
  ```

**Manual test (optional).** Send a real email to a mailbox of your own, within `ALLOWED_RECIPIENT_DOMAINS`:
```bash
curl -X POST "$BASE/v1/emails" -H "Authorization: Bearer $PRODUCT_KEY" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $(uuidgen)" \
  -d '{"templateKey": "<published template>", "to": {"email": "<your mailbox>"}, "variables": {...}}'
```
Check that:
- the response is `202` with the message `id`;
- the email shows up in Resend's *Emails* with the tag `message_id=<id>`;
- in the database, the message moves to `SENT` with a `provider_message_id`.

## Resend webhooks in staging

Events (delivered, bounced, complained, suppressed) arrive at `POST /webhooks/resend`, signed with Svix (FR-16). Resend needs a public HTTPS URL. In staging, the load balancer must expose **only** `/webhooks/*` (docs/05 §7).

**Register the endpoint** (in the staging Resend team):
1. In *Webhooks → Add endpoint*, set the URL to `https://<staging host>/webhooks/resend`.
2. Select these events: `email.sent`, `email.delivered`, `email.delivery_delayed`, `email.bounced`, `email.complained`, `email.failed`, `email.suppressed`, `email.opened`, `email.clicked`, `suppression.added` and `suppression.removed`.
3. Copy the *Signing secret* (`whsec_…`) straight into the secrets manager as `MAIL_WEBHOOK_SIGNING_SECRET`, and restart the `api` instances. It is required with `MAIL_PROVIDER=resend`: without it, the API does not start.
4. **Rotation** (docs/08 §1):
   - create a second endpoint;
   - set both secrets, comma-separated (`whsec_old,whsec_new`);
   - delete the old endpoint;
   - keep only the new secret.

**Temporary test through a tunnel** (before staging has a host of its own). Never expose the whole application: a proxy in front of the tunnel lets only `/webhooks/*` through, and `/v1` and `/admin` answer `404`.

```bash
# 1. The app with staging values, from the host, against the compose PostgreSQL.
#    .env.staging is git-ignored; it holds APP_ENV=staging, MAIL_PROVIDER=resend, RESEND_API_KEY,
#    MAIL_WEBHOOK_SIGNING_SECRET, ALLOWED_RECIPIENT_DOMAINS (including resend.dev) and the variables in .env.
docker compose up -d postgres
set -a; source .env.staging; set +a; ./gradlew bootRun

# 2. A proxy that forwards only /webhooks/* (another terminal).
printf ':8081 {\n\thandle /webhooks/* {\n\t\treverse_proxy localhost:8080\n\t}\n\trespond 404\n}\n' > /tmp/Caddyfile
docker run --rm --network host -v /tmp/Caddyfile:/etc/caddy/Caddyfile:ro caddy:2

# 3. A temporary tunnel to the proxy (another terminal); it prints a URL https://<random>.trycloudflare.com
cloudflared tunnel --url http://localhost:8081
```

Then:
1. Register `https://<random>.trycloudflare.com/webhooks/resend` as an endpoint (steps above), and use its secret in `.env.staging`.
2. Send to Resend's test addresses, with the manual test of the previous section:
   - `delivered@resend.dev`: the message moves to `DELIVERED`;
   - `bounced@resend.dev`: it moves to `BOUNCED`, and a global `HARD_BOUNCE` suppression is created;
   - `complained@resend.dev`: it moves to `COMPLAINED`, and a global `COMPLAINT` suppression is created.
3. In Resend's *Webhooks → \<endpoint\>*, every delivery should show `204`. A `401` means the secret does not match.
4. **When done:**
   - stop the tunnel and the proxy (`Ctrl+C`);
   - delete the temporary endpoint in Resend;
   - remove the test suppressions with `DELETE /admin/v1/suppressions/{email}`, and in Resend's *Suppressions* too.

## Operations: alerts, health and data

**Alerts.** The NFR-11 alerts are JSON log lines at level `ERROR` with an `alert` field, and `alertSubject` says what they refer to.
- The same alert for the same subject is emitted at most once every 15 minutes per instance.
- For a log-based alert in your hosting, filter on `level = ERROR` with an `alert` field present, and group by `alert` and `alertSubject`.
- `ops/prometheus/alerts.yml` has equivalent reference rules, for when metrics are collected with Prometheus.

| `alert` | `alertSubject` | Meaning | What to do |
|---|---|---|---|
| `QUEUE_AGE` | `queue` | The oldest eligible message has waited longer than `QUEUE_AGE_ALERT_SECONDS` (300 s) | Check that workers are alive, the circuit breaker and `/actuator/health` |
| `FAILED_RATE` | `all-tenants` | More than 5 % of the last hour's messages are `FAILED` (with at least 20) | Group by `failure_code` (docs/06 §4) |
| `TENANT_PAUSED` | tenant slug (or id) | Automatic pause: volume, bounces, complaints (FR-34) or unverified domain | Review with the tenant's owner; resume with `POST /admin/v1/tenants/{slug}/resume-sending` |
| `CIRCUIT_BREAKER_OPEN` | `provider` | The provider has not accepted sends for more than 10 minutes | Check Resend's status; the queue waits without losing attempts |
| `PROVIDER_CONFIGURATION` | Resend error code | The API key or its permissions are wrong (`401`, a key-related `403`) | Rotate or fix `RESEND_API_KEY` |
| `LOST_LOCK` | `worker` | A worker lost a message's lock before closing it | Look for long worker pauses, or review `LOCK_TIMEOUT_SECONDS` |
| `IDEMPOTENCY_CONFLICT` | message id | The provider saw the same id with a different body: non-deterministic render (AC-12.7) | Review the template and the version pinned to the message |
| `COMPLAINT_RATE` | tenant slug | 7-day complaint rate above 0.1 % | Review the tenant's content and recipients before it reaches the pause threshold |
| `AUTH_FAILURE_SPIKE` | IP | An IP exceeded `AUTH_FAILURES_PER_MINUTE_PER_IP` authentication failures | If it is one of your applications, its key is misconfigured; if not, possible brute force (docs/08 §4) |

**Health.**
- `/actuator/health/liveness` and `/actuator/health/readiness` are the orchestrator probes.
- `/actuator/health` also aggregates `queue` and `provider`; the latter exists only in the worker role.
- `DEGRADED` answers `200`: it warns without restarting healthy instances. `provider` `DOWN` (open breaker or configuration error) answers `503` on `/actuator/health`, but does not affect the probes.

**Retention and erasure.**
- Purges run on their own in the worker (docs/06 §8). They cover variables, idempotency keys, per-tenant retention, rendered content, orphans, counters and audit entries.
- A data subject is erased with `POST /admin/v1/data-subjects/erase`, following the full procedure in docs/08 §6, which includes asking Resend to delete the data and answering within the legal deadline.

## Templates as code

Templates are kept as folders and published through the API ([`docs/05`](docs/05-arquitectura.md) §9). The examples live in this repository; an application can keep its own templates in its own repository.

```
templates/{tenant}/{templateKey}/
├── template.json     # key, name, category, subject per locale, variablesSchema, previewVariables
├── es-CR.html        # one .html per locale (required)
├── es-CR.txt         # plain text (optional)
├── en.html
└── en.txt
```

```bash
EMAIL_SERVICE_TEMPLATES_KEY=esk_test_... ./scripts/publish-template.sh demo password-reset

# From an application's repository, with the path of its template folder:
EMAIL_SERVICE_TEMPLATES_KEY=esk_test_... EMAIL_SERVICE_URL=http://localhost:8080 \
  ../email-service/scripts/publish-template.sh emails/password-reset
```

The script:
- creates the template if it does not exist;
- skips locales with no changes;
- creates the drafts and previews them with `previewVariables`;
- publishes only if every preview passes; if anything fails, it deletes the drafts it created;
- when several locales changed, publishes them all in one operation (ADR-0020), so the required variables of a multi-locale template can change.

The key must be an operator key (`templates:write` and `emails:read`), never the one deployed with an application. In CI, `TemplatesAsCodeTest` validates every template with the same rules as the service.

CI does not use mise or `jq`: `setup-java` installs Java, and no job runs the scripts. `jq` is needed only for the scripts in local development; `mise install` installs it, and GitHub's runners already include it.

Template rules (ADR-0011):
- logic-less Handlebars;
- only `if`, `unless`, `each`, `with`, `formatDate`, `formatNumber` and `formatMoney`;
- no `{{{ }}}`, no `{{& }}` and no partials;
- HTML escaped in the body;
- URL variables declared with `"format": "uri"`, using `https` and a host from the tenant's `allowedLinkHosts`. With `APP_ENV=local` only, links to `localhost` and `*.localhost` are accepted too (ADR-0021).

## Branches

- `main`: always deployable.
- `dev`: integration.
- `feat/*` and `fix/*`: one branch per milestone or large change.

Details in [`AGENTS.md`](AGENTS.md#branching).
