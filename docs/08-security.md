# 08 — Security considerations

> Revision 2026-10-05: scopes and CIDRs per key, anomalies, hardened templates, RLS, global suppression, Svix webhooks, privacy and Law 8968, incident response with breach notification.

An email service is an attractive target: whoever controls it can impersonate your domains, send phishing with your reputation and read personal data. These rules are **mandatory** and are not relaxed "temporarily".

## 1. Secrets management

| Secret | Where it lives | Where it **never** lives |
|---|---|---|
| Provider API key (`RESEND_API_KEY`) | An environment variable injected by the orchestrator or a secrets manager (AWS Secrets Manager, Doppler, Infisical, 1Password Connect) | Database, repository, Docker image, logs |
| `MAIL_WEBHOOK_SIGNING_SECRET` | Same | Same |
| `ADMIN_API_KEYS` | Same | Same |
| `SUPPRESSION_HASH_KEY` (rev. 2026-10) | Same; with an offline backup (if it is lost, the suppression hashes cannot be recomputed) | Same |
| PostgreSQL credentials (three roles) | Same; `email_owner` only available to the `migrate` job | Same |
| Tenant API keys | In the service: **hash only**. In the client product: its own environment variable | In clear in the database, in logs, in responses (except at creation time) |

Rules:

- `.env` is in `.gitignore`; `.env.example` only contains placeholders.
- The application **does not start** if a required secret is missing in `production` or `staging` (fail-fast, no insecure defaults).
- Secret scanning in CI (`gitleaks` or equivalent), blocking the merge if it finds anything.
- Rotation: documented and tested at least once (§3).
- No secret is printed in logs, traces or error messages; the configuration objects that hold them have a redacted `toString()`.

## 2. Service-to-service authentication (rev. 2026-10, ADR-0017)

**API key format:** `esk_{env}_{prefix}_{secret}`

- `esk` = *email service key*; `env` = `live`/`test`; `prefix` = 8 public Base62 characters that let us find the row (regenerated if they collide); `secret` = 43 Base62 characters from a CSPRNG (≥ 256 bits).
- **Storage:** `key_prefix` in clear (for the lookup) and `SHA-256(secret)` in `key_hash`. With 256 bits of real entropy, SHA-256 is enough and avoids the cost of Argon2 on every request; it is not a human password. (If low-entropy keys are ever issued, switch to Argon2id → requires an ADR.)
- **Constant-time comparison** (`MessageDigest.isEqual`), never `String.equals`.
- The prefix allows identifying a leaked key in a log or repository without exposing the secret.
- ~~An in-memory cache of validated keys with a TTL ≤ 60 s~~ **(rev. 2026-10-06, ADR-0019)** No cache: the lookup by prefix is one indexed read per request (cheap at this volume) and revocation is immediate, stricter than AC-03.4. A cache (TTL ≤ 60 s) would be added only if a measurement of authentication latency justifies it.
- **(rev. 2026-10-06)** Failures with a missing, malformed or unknown key are not audited one by one (that would allow flooding `audit_log`): they are counted in the metric `email_auth_unauthenticated_total{reason=missing|malformed|unknown_key}`, without writing to the database. **(rev. 2026-10-12)** The aggregated audit per IP and the rate limit on `401`s are described in §4. Rejections of known keys are audited: revoked, expired, outside `allowedCidrs` or of a suspended tenant.
- **Scopes** (`emails:send`, `emails:read`, `templates:write`, `suppressions:write`): the key deployed in a product only has `emails:send` and `emails:read` (FR-33).
- **Origin:** `allowedCidrs` per key when the hosting provides stable outgoing IPs.

**Admin credential:** `ADMIN_API_KEYS` (one or several, to rotate without an outage), with its own header (`X-Admin-Key`); `/admin/v1/**` is reachable only from the private network and restricted by IP at the load balancer. A tenant key is never used for administration.

**Transport (rev. 2026-10):** TLS required for all traffic, or an encrypted private network (WireGuard, the hosting's encrypted private network). API keys travel as bearer tokens: they are not accepted in plain text over an unencrypted network. HSTS on the public proxy.

## 3. Credential rotation

**A tenant's API key (no downtime):**

1. Issue a new key (`POST /admin/v1/tenants/{slug}/api-keys`) with the same scopes: the tenant has two active keys.
2. Deploy the product with the new one in its environment.
3. Check in `last_used_at` that the old one is no longer used.
4. Revoke the old one (`DELETE /admin/v1/api-keys/{id}`).
5. It is recorded in the audit log.

**Admin credential:** add the new one to `ADMIN_API_KEYS` → deploy → use the new one → remove the old one → deploy.

**Provider credentials:** create the new one in the dashboard → update the environment variable → redeploy (rolling) → revoke the previous one. The service reads the key at startup; a restart is enough.

**Webhook secret:** Resend has one secret per endpoint. To rotate it without losing events: create a second endpoint with its secret, accept both during the change (`MAIL_WEBHOOK_SIGNING_SECRET` accepts two comma-separated values) and delete the old endpoint.

**On a suspected leak:** revoke immediately (the availability of the affected product is secondary to an impersonated send), pause the tenant, rotate the webhook secret if it could have been exposed, review `audit_log` and the sends of the last hours per tenant.

## 4. Protection against abuse and spam (rev. 2026-10)

| Vector | Control |
|---|---|
| A leaked product key used for mass sending | A daily quota set to ~3× the expected volume (FR-21); **automatic pause on an anomaly** in volume, bounces or complaints (FR-34); CIDR per key (FR-33); an alert |
| A leaked key used to publish its own content (phishing) | The product key does **not** have `templates:write` (FR-33); publishing is audited |
| Phishing through URL variables in legitimate templates | `format: uri` variables only `https` and with a host in the tenant's `allowedLinkHosts` (AC-07.8) |
| Use as an open relay | There is no sending endpoint without an API key; every request uses one of the tenant's own templates |
| Arbitrary content | In the MVP, sending happens **only with a published template** of the tenant; there is no free HTML in the request (FR-27 out of scope) |
| Sender spoofing | The `from` comes from the tenant; if specified, it must be in `allowedFromDomains`; SPF, DKIM and DMARC aligned per domain at the provider |
| Header injection (CRLF) | `\r`/`\n` rejected in addresses, names (including `fromName`), subject and tags (AC-09.3, AC-09.5); the rendered subject is cleaned too |
| Template injection (SSTI) | A hardened logic-less engine: no partials, no default helpers, only `MapValueResolver`, `{{{ }}}` and `{{& }}` rejected (ADR-0011) |
| XSS / active content in the email | HTML escaping by default; a linter that forbids variables in `<script>`, `<style>`, `style=`, `on*` and unquoted attributes, and forbids active tags (AC-04.7) |
| SSRF | The service never downloads remote content |
| Malicious attachments | There are no attachments in the MVP (FR-30 Won't) |
| Resource enumeration | UUID IDs; resources of another tenant return `404`, not `403` |
| Queue abuse (huge payloads) | `MAX_REQUEST_BYTES`, limits on `metadata` (4 KB), `tags` (10), `cc`/`bcc` (5) and batches |
| Bounces and complaints that damage reputation | Automatic **global** suppression (ADR-0012); rates per tenant; a pause above 4 % bounces or 0.1 % complaints |
| Webhook replay | Svix signature on the raw body + ±5 min window + dedupe by `svix-id` |
| API key brute force | 256-bit keys (infeasible); rate limit per IP on repeated `401`s (in memory per instance) and an aggregated audit. **(rev. 2026-10-12)** Details below the table |
| Cross-tenant leak through a code mistake | Fail-closed RLS + composite FKs (ADR-0008) |

**(rev. 2026-10-12, owner) Repeated `401`s per IP.** Several products can go out through the same IP (the same server), and a misconfigured one must not block the others.
- **Known prefixes:** each API instance keeps in memory the prefixes of the active API keys (they are not secrets).
  - They are refreshed at most every 60 s, and immediately when that instance issues or revokes a key.
  - Until the first load the set is considered unknown and every request goes to the database.
- **Threshold:** an IP that exceeds `AUTH_FAILURES_PER_MINUTE_PER_IP` authentication failures (`401`) in the current minute (default 30, a fixed window per instance) is limited.
  - It gets `429 RATE_LIMITED`, with `Retry-After` until the end of the minute, **only** on requests with a missing or malformed key, or with a prefix that is not in the set.
  - Those are rejected **without touching the database**; the metric `email_auth_key_lookups_total` verifies it.
- **Known prefix:** normal authentication against the database continues even if the IP is above the threshold. Therefore:
  - another product with a valid key behind the same IP keeps working;
  - revocation stays immediate: a just-revoked key gets `401` even though its prefix stays in the set until the next refresh.
- **A key issued on another instance:** for at most 60 s, a limited IP also gets `429` with that key.
- **Audit:** one `AUTHENTICATION_FAILURES_BY_IP` row per IP and hour, with the threshold; never one per failure.
- **Metrics:** `email_auth_ip_limited_total` counts the `429`s; `email_auth_unauthenticated_total{reason}` keeps counting the `401`s without a known key.

**Sending to real addresses from non-production environments:** `ALLOWED_RECIPIENT_DOMAINS` is **mandatory** in `staging`; in `local` the default provider is Mailpit, which delivers nothing outside. Staging uses a provider account separate from production.

## 5. Multi-tenant isolation (rev. 2026-10, ADR-0008)

- `tenant_id` in every business query; the tenant context is derived **only** from the authenticated API key, never from a body field or a parameter.
- A `tenantId` sent by the client is explicitly ignored: there is no "acting on behalf of".
- **RLS active from the MVP**: `FORCE ROW LEVEL SECURITY` and a fail-closed policy on `app.tenant_id`, set per transaction with `set_config(…, true)`.
- **Composite FKs `(tenant_id, …)`**: the database rejects cross references between tenants.
- **The `email_system` role (`BYPASSRLS`)** confined by an architecture test to the worker, webhooks, auth, administration, anomalies and maintenance.
- Mandatory tests per endpoint (A neither sees nor affects B), without a context (0 rows) and for a cross FK.

## 6. Personal data protection (rev. 2026-10)

- **Minimization:** the address, the variables and the metadata are stored; the rendered HTML only if the tenant enables it, and with the `x-sensitive` variables redacted.
- **Variables:** they can contain personal data (names, amounts, SINPE phone numbers, links with tokens). They are **never** written to logs. `x-sensitive` ones are purged on sending; the rest, 7 days after the final state.
- **Provider events:** a minimized payload (no `to`, `from`, `subject`, headers or URL query string).
- **Logs:** masked addresses; 30-day retention.
- **Links with a one-time token:** the product's responsibility (short expiry); the email-service does not interpret them and marks them `x-sensitive`. That is why click tracking is forbidden in `SECURITY` (it would rewrite those links through the provider).
- **Data processor:** the email provider processes addresses and content in the US; the README documents which data it receives.
- **Erasure at the data subject's request (FR-29):** the administration endpoint `POST /admin/v1/data-subjects/erase`.
  1. Receive the request through the product (which is the controller towards the user).
  2. Run the endpoint with the address and a reference to the request.
  3. Ask the provider to delete its records of that address (retention declared by Resend: 30 days according to its pricing page; to be confirmed per plan).
  4. Answer the data subject within **5 business days** (Law 8968; to be confirmed with legal counsel).
  5. The suppression is kept only as a hash, so as not to write to them again.

### 6.1 Law 8968 (Costa Rica) — obligations that affect this service

I am not a lawyer: this is a technical reading of secondary sources checked on 2026-10-05, to be validated with legal counsel (NFR-20).

| Obligation | How it is covered |
|---|---|
| Informed, written consent to process data (art. 5) | Each product's responsibility; the email-service only processes data on their behalf |
| International transfer only with express authorization (art. 14; there is no adequacy regime) | Each product must inform and obtain consent for an email provider in the US to process the address and the content. **Blocking for production** of each product |
| ARCO rights within 5 business days | FR-29 and the procedure in §6 |
| Breach notification to the affected people and PRODHAB within 5 business days (Regulation, Decree 37554-JP, arts. 38–39) | §9, step 5 |
| Registering databases with PRODHAB (only those that are distributed, disseminated or sold) | The email-service is considered internal use: exempt, **to be confirmed** |
| Third-party data processed on behalf of others (Colmena: its businesses' customers, products, debts) | The business is the controller; Colmena, the processor; the email-service and Resend, sub-processors. Colmena's terms with its businesses must cover the emails and the transfer. Debt amounts and concepts are marked `x-sensitive` (NFR-20) |
| Security measures | This document |

## 7. Auditing

Recorded in `audit_log` (with no secrets or email content; addresses as `email_hash`):
- creating, suspending, pausing and resuming tenants;
- issuing and revoking keys;
- publishing templates;
- deleting suppressions;
- data-subject erasures;
- limit changes;
- authentication failures aggregated by IP and window;
- access to administration endpoints.

Each entry: actor, action, resource, IP, `requestId`, timestamp. Inserts only (no `UPDATE`/`DELETE` for the app's roles); 365-day retention.

## 8. Supply chain and deployment security

- An official base image pinned by version; a container with a non-root user and a read-only file system except `/tmp`.
- Dependencies with pinned versions and vulnerability scanning in CI (`dependency-check`, `osv-scanner` or Dependabot). Handlebars.java is watched in particular (ADR-0011).
- No exposed debug ports; Actuator limited to `health`, `info`, `metrics` and `prometheus` (the latter only on the private network).
- **(rev. 2026-10)** Three PostgreSQL roles: `email_owner` (migrations, only in the `migrate` job), `email_app` (RLS, no DDL) and `email_system` (`BYPASSRLS`, no DDL). The running app never owns the tables.
- CORS disabled (no browsers call this API); if there ever are, an explicit allowlist.
- **(rev. 2026-10)** Backups encrypted at rest, with retention ≤ 35 days.

## 9. Incident response (minimum viable) (rev. 2026-10)

1. **Detection:** alerts on queue age, a paused tenant (FR-34), bounce rate > 4 % or complaint rate > 0.1 % per tenant, a `401` spike, `email_lost_lock_total` > 0, circuit breaker open > 10 min.
2. **Containment:** pause or suspend the affected tenant (`PATCH /admin/v1/tenants/{slug}` → `SUSPENDED`) and/or revoke its key; queued sends **are held** in `QUEUED` (they are neither lost nor failed).
3. **Investigation:** `audit_log` + `email_message` per tenant and time window; the provider's events.
4. **Recovery:** rotate credentials, resume the tenant (`resume-sending`), review the domain's reputation in the provider's dashboard and in Google Postmaster Tools.
5. **Notification (if personal data was compromised):** to the affected people and PRODHAB within **5 business days** of the incident, stating the nature, the compromised data, the corrective actions and the information channels (Regulation of Law 8968; to be confirmed with legal counsel). Each affected product notifies its users; the email-service provides the exact scope (queries per tenant and window).
6. **Record:** write down the incident, the cause and the decisions in `docs/CHANGELOG.md`.

## 10. Pre-production checklist

- [ ] No secret in the repository (CI scan green).
- [ ] `ADMIN_API_KEYS` and `SUPPRESSION_HASH_KEY` generated with ≥ 32 random bytes and stored in the secrets manager (the latter with an offline backup).
- [ ] SPF, DKIM and DMARC (at least `p=none`, aligned) configured and verified for each sending domain.
- [ ] `MAIL_PROVIDER=resend` and `ALLOW_SMTP_IN_PRODUCTION=false`; a separate Resend team for staging.
- [ ] TLS or an encrypted private network for all traffic; only `/webhooks/*` is public.
- [ ] Webhook signature verified with a real event from the provider (raw body).
- [ ] Roles `email_owner`/`email_app`/`email_system` created; RLS active and the no-context test green against the real database.
- [ ] Limits, `allowedLinkHosts` and anomaly thresholds reviewed per tenant; alerts configured.
- [ ] Product keys issued only with `emails:send` + `emails:read`.
- [ ] PostgreSQL backup active, encrypted and **restore tested once**.
- [ ] Multi-tenant isolation tests green.
- [ ] Actuator exposes only what is needed.
- [ ] Each product's privacy notice covers the transfer to the provider in the US (Law 8968, art. 14). **Pending, it does not block development; it blocks each product's go-live.**
- [ ] Colmena: the terms with its businesses cover sending emails to each business's customers.
