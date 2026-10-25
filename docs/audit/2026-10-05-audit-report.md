# Audit report — email-service documentation

- **Date:** 2026-10-05
- **Scope:** `/docs` (README, 01–10, CHANGELOG), ADR-0000 to ADR-0007 and `AGENTS.md`, version of 2026-09-20.
- **Method:** cross-reading of every document; web checks of versions, prices, limits and regulations (sources at the end, consulted on 2026-10-05); real micro-benchmarks of the acceptance path on a local PostgreSQL 16 (details in ADR-0014).
- **Severity:** **Blocking** (it cannot be implemented without deciding it: it causes lost or duplicated email, a cross-tenant leak or abuse) · **Serious** (a real defect that will show up in production, or a contradiction that forces guessing) · **Minor** (an inconsistency or imprecision with bounded impact) · **Improvement** (not an error, but advisable).
- "To be confirmed" = could not be checked against a primary source; it is not asserted.

Summary: 4 blocking, 17 serious, 22 minor, 3 improvements. What is **right** and stays, one line each: a single artifact with `APP_ROLE` (correct); a queue in PostgreSQL with `SKIP LOCKED` (correct at this volume, see the measurements); a provider over its API behind `EmailSender` (correct); secrets outside the database and API keys with a 256-bit SHA-256 hash (correct, Argon2 would be cost without benefit); template versions immutable and pinned per message (correct); RFC 9457 errors (correct); Java 25 LTS + Spring Boot 4.1 + PostgreSQL 18 as versions (current and verified).

---

## BLOCKING

### 1. The state machine makes `COMPLAINED` unreachable and loses late bounces
- **Where:** `02` §2 and AC-17.2 / AC-17.4; `06` §3.4.
- **What is wrong:** "Final states: `DELIVERED`, `BOUNCED`, `COMPLAINED`…" and "A transition never goes back". A complaint **always** arrives after delivery (the user has to receive the email to mark it as spam), and an asynchronous bounce can also arrive after `delivered`. With `DELIVERED` final, AC-17.2 ("an event… that would imply going back does **not** change the state") forces ignoring the complaint, while AC-17.4 requires "updates the state **and** creates the suppression in the same transaction". The two readings are incompatible.
- **Why it matters:** depending on how the implementer reads it, the complaint suppression is not created and we keep writing to whoever reported us as spam. It is exactly the metric Gmail watches (0.3 % threshold).
- **Fix:** an explicit partial order: `QUEUED → SENDING → SENT → DELIVERED → {BOUNCED | COMPLAINED}`; `DELIVERED` is "stable", not terminal; `BOUNCED`/`COMPLAINED` are. Creating the suppression is decoupled from the state change (it is always created when the event arrives). Applied in `02` §2 and in the new AC-17.5.

### 2. "Exactly once" is not guaranteed: fencing is missing, the sweep does not count attempts, and Resend's idempotency, which exists, is not used
- **Where:** `03` NFR-04; `02` AC-11.3, AC-13.6; `05` §3 steps 10–14; `06` §6; ADR-0002.
- **What is wrong:**
  - NFR-04: "Mitigation: the provider's idempotency **when it offers it**". Resend **already offers it**: an `Idempotency-Key` header, up to 256 characters, kept for 24 h, with `409 invalid_idempotent_request` if the body changes ([Resend — Idempotency keys](https://resend.com/docs/dashboard/emails/idempotency-keys)). The design does not use it.
  - AC-13.6 ("if there is a `provider_message_id`, it is not resent") does not cover the real duplicate case: the provider accepts, the response is lost because of a timeout and the `provider_message_id` is **never** stored.
  - The sweep (`06` §6) returns expired `SENDING` messages to `QUEUED` **without** adding to `attempts`: a message that crashes the worker (e.g. OOM while rendering) is retried forever.
  - There is no fencing: the final `UPDATE … SET status='SENT'` does not check that the worker still owns the lock. If the sweep reassigns a message whose first worker is still alive (a GC pause, a slow provider), two workers send it.
  - The lock is set for the whole batch (25 messages) when it is claimed, but the batch is processed with concurrency 4: the last message of the batch can start close to the expiry.
  - The text does not say whether the provider call happens inside the transaction that claimed the batch. `06` §6 suggests it does not (an autocommit CTE), but does not forbid it.
- **Why it matters:** it is the project's goal no. 2 ("never lose or duplicate emails"), and the AGENTS.md rule "Never re-send a message that already has provider_message_id" gives a false sense of safety.
- **Fix (ADR-0010, supersedes ADR-0002):** claiming work in a short transaction; **never** HTTP with row locks held; a `lock_token` per claim and `UPDATE … WHERE lock_token = :mine` to close; `attempts` is incremented **on claiming** (not on failing), so the sweep counts; `Idempotency-Key` towards Resend = `email_message.id` (stable across attempts) and a deterministic body; a total retry window < 23 h (validated at startup) to stay within the provider's 24 h; NFR-04 is rewritten as "at least once with deduplication at the provider within 24 h" and declares the real residual risk (a send lasting > 24 h, which the design forbids).

### 3. A leaked API key is a phishing cannon with your domain
- **Where:** `08` §4 ("Arbitrary content / phishing → only sent with a published template"); `07` §2 (the `/v1/templates/**` and `/v1/suppressions/**` endpoints use the same key as `/v1/emails`); `07` §3 (a free `activationUrl` variable).
- **What is wrong:** the same key the product uses to send can **create and publish templates**. "Only a published template" protects nothing if whoever steals the key publishes their own. Even without that, a legitimate template with `href="{{activationUrl}}"` accepts any URL: the attacker sends "Activate your account" from `no-reply@your-product` with a link to their site. The same key can also **delete suppressions** (hard bounces included, AC-18.3).
- **Why it matters:** the "a key leaks" scenario is the most likely one (environment variables in CI logs, a `.env` in a ticket), and today its maximum impact is impersonating the domain to any recipient up to the daily quota (5,000 by default).
- **Fix:** scopes per key (FR-33: `emails:send`, `emails:read`, `templates:write`, `suppressions:write`; the product's production key has only the first two); an optional IP/CIDR list per key; URL variables validated against the tenant's `allowedLinkHosts` and only `https` (AC-07.8); deleting `HARD_BOUNCE`/`COMPLAINT` suppressions only through administration; anomaly detection with an automatic pause (FR-34).

### 4. Postponing Row Level Security does not hold up, and the database does not guarantee isolation even with RLS
- **Where:** `01` §6 ("Row Level Security… Phase 2"); `03` NFR-08; `08` §5; ADR-0007; `06` §5.
- **What is wrong:** the only control is "every query carries `tenant_id`" plus tests per endpoint. The service stores recipients, variables (with activation tokens) and events of **four** different products, including a payments one (Payverica). On top of that, the database itself allows cross-tenant inconsistencies: `email_message.template_version_id` references `template_version(id)` without including `tenant_id`, `template_version.tenant_id` is "denormalized" with no constraint tying it to `template.tenant_id`, and `suppression`/`email_event` do not tie it either. ADR-0007's argument ("adds session/role complexity from day 1") is real but small: one `set_config('app.tenant_id', …, true)` per transaction and policies per table.
- **Why it matters:** your other projects require it from the MVP; this one, which mixes personal data of several products, has **more** reason to, not less. A single badly written repository (or an AI agent that "simplifies" a query) exposes data between products without any test catching it if the test does not cover that path.
- **Fix (ADR-0008, supersedes ADR-0007):** RLS with `FORCE ROW LEVEL SECURITY` from migration V1; an owner role (migrations) separate from the application role; a fail-closed policy (no row is visible without `app.tenant_id`); a second `DataSource` with a system role only for the worker, sweeps, webhooks and the API key lookup, confined by an architecture test; composite foreign keys `(tenant_id, id)` so that the database prevents a message from pointing to another tenant's template.

---

## SERIOUS

### 5. Blocking `{{{ }}}` does not close injection or XSS
- **Where:** AC-04.5, ADR-0005, `04` §7, `08` §4.
- **What is wrong:**
  - Handlebars also allows unescaped output with `{{& variable}}` ([handlebars.java README](https://github.com/jknack/handlebars.java)). Only `{{{ }}}` is forbidden.
  - Handlebars.java registers helpers such as `partial`, `block`, `embedded`, `precompile`, `i18n` and `log` by default (same README). `partial`/`embedded` load content through the `TemplateLoader`; the library has published path traversal fixes in its loaders (4.5.x versions, [releases](https://github.com/jknack/handlebars.java/releases)). The "allowed helpers" list in `07` §6 does not say that the others are **removed**.
  - Reflection-based value resolvers (`JavaBeanValueResolver`, `MethodValueResolver`) allow navigating public methods of Java objects if the context is not a pure map.
  - HTML escaping does not protect URL contexts (`href="javascript:…"` or a foreign domain), CSS (`style="color:{{c}}"`) or unquoted attributes.
  - HTML escaping is also applied to the **subject** and the **plain text**, where it produces visible `&amp;` and `&#39;` ("Ana &amp; Juan").
- **Fix:** ADR-0011 (supersedes ADR-0005) and AC-04.5/04.7–04.10: reject `{{&`, `{{{`, `{{>`, partials and any helper outside the whitelist; a `TemplateLoader` that loads nothing; only `MapValueResolver`; a linter on publication that forbids variables inside `<script>`, `<style>`, `style=`, `on*` attributes and unquoted attributes, and requires `format: uri` for variables in `href`/`src`; context-aware escaping (HTML in `html`, none in `subject`/`text` other than removing CR/LF).

### 6. "Per tenant" suppression: a false premise with Resend and semantic gaps
- **Where:** AC-10.2, AC-18.3, ADR-0007, `06` §3.6.
- **What is wrong:** "Suppression is **per tenant**, not global." In Resend the suppression list belongs to **the whole team**: "Any address added to the suppression list will be skipped across all your domains and subdomains", and it is fed automatically with hard bounces and complaints ([Resend — Email suppressions](https://resend.com/docs/dashboard/emails/email-suppressions)). A complaint against Chinamo blocks Colmena's emails to that address at the provider, and your table will not know. The `email.suppressed` event ([event types](https://resend.com/docs/dashboard/webhooks/event-types)) is not mapped: those messages stay `SENT` forever. Also: `cc`/`bcc` are not checked against the suppression; a tenant can delete a hard bounce (which is a fact about the address, not about the tenant); what happens with critical emails (password recovery) to someone who complained is not defined.
- **Fix:** ADR-0012: `HARD_BOUNCE` and `COMPLAINT` with a **global** scope (reflecting what the provider already does), `MANUAL` per tenant; a mirror of `suppression.added`/`email.suppressed`; checking `to`+`cc`+`bcc`; deleting global suppressions only through administration. If you want a complaint against one product **not** to affect another, the only real way is one Resend account (team) per product: left as an open question.

### 7. Soft bounce: the diagram contradicts the requirement, and the provider emits no "soft bounce"
- **Where:** `02` §2 ("webhook bounce (hard/soft) → BOUNCED") against AC-10.4 ("A soft bounce does **not** suppress, it only records the event"); `06` §3.5 (`BOUNCED_SOFT`).
- **What is wrong:** the diagram sends the soft bounce to a terminal state; the requirement treats it as informational. Resend does not distinguish hard/soft bounces by a field: `email.bounced` is a permanent rejection and `email.delivery_delayed` is a temporary problem ([event types](https://resend.com/docs/dashboard/webhooks/event-types)). `email.sent`, `email.failed`, `email.scheduled` and `email.suppressed` are not mapped either.
- **Fix:** a provider→internal event→transition mapping table in `02` §2.1; `BOUNCED_SOFT` becomes `DELIVERY_DELAYED` and `BOUNCED_HARD` becomes `BOUNCED` (renamed before the first migration: there is no data yet); `email.failed` → `FAILED` with `PROVIDER_FAILED`.

### 8. Webhook verification is documented with headers Resend does not send
- **Where:** `07` §8 (`Webhook-Id`, `Webhook-Timestamp`, `Webhook-Signature`); AC-16.1 (`WEBHOOK_SIGNING_SECRET`) against `05`/`07`/`08` (`MAIL_WEBHOOK_SIGNING_SECRET`).
- **What is wrong:** Resend signs with Svix and sends `svix-id`, `svix-timestamp`, `svix-signature`, with a secret **per endpoint**, and verification requires the **raw body** without re-serializing ([Resend — Verify webhooks](https://resend.com/docs/dashboard/webhooks/verify-webhooks-requests)). The environment variable's name does not match across documents.
- **Fix:** `07` §8 with the real headers, reading the raw body before parsing, a single variable `MAIL_WEBHOOK_SIGNING_SECRET`, deduplication by `svix-id`.

### 9. Suspended tenant: three different behaviours
- **Where:** AC-02.3 ("those already queued are **not** sent while it stays suspended"); `05` §4 ("Blocking: suspended tenant → `FAILED` with `failure_code`"); `08` §9 ("queued sends are held").
- **Why it matters:** with `05` §4, suspending a tenant as a precaution during an incident **destroys** its queue; with AC-02.3 it holds it. It is the main containment lever of the incident plan.
- **Fix:** hold: the worker does not claim messages of `SUSPENDED` or paused tenants (a filter in the claim query); the queue age alert excludes them; `TENANT_SUSPENDED` stops being a `failure_code` (DEPRECATED) and remains only as an HTTP 403 error.

### 10. Taking attachments out of the MVP breaks Chinamo's electronic receipt (and perhaps Colmena's)
- **Where:** `01` §6, FR-28, `09` Phase 1 ("Attachments… Prerequisite: a real use case").
- **What is wrong:** the real case already exists: a POS in Costa Rica issues electronic receipts and must deliver them to the recipient; common practice is to email the signed XML, the tax authority's (Hacienda) response and the PDF. The regulation says receipts are "transmitted to the recipient" ([Alegra — guide v4.4](https://blog.alegra.com/costa-rica/comprobantes-provisionales-y-electronicos-costa-rica/)); the exact channel and format are **to be confirmed** against the tax administration's current resolution. If Chinamo is one of the first products, the service is no use to it. PipeMend's monthly export, on the other hand, does **not** need an attachment: a signed link is better (size, expiry, privacy).
- **Fix:** FR-28 is marked DEPRECATED and split: FR-30 Bounded attachments (Should, MVP): base64 in the request, max. 3 files, `application/pdf` and `application/xml`, ≤ 2 MB decoded, a magic-bytes check, stored in `email_attachment` and **deleted when a terminal state is reached** (no object storage). FR-31 `sendAt` (Could, it costs almost nothing because `next_attempt_at` already exists). FR-32 outgoing webhooks (Won't; no product needs them to operate).

### 11. Throughput and provider limits: NFR-02 ignores Resend's limit and there is no mechanism with N workers
- **Where:** NFR-02 ("≥ 20 messages/second"), AC-12.6 ("respects… the provider's rate limit"), NFR-06 (N workers).
- **What is wrong:** Resend's default limit is **10 requests/second per team** ([Resend — API reference](https://resend.com/docs/api-reference/introduction)). If staging and production share a team, they share the limit. With N workers, each with its own in-memory limiter, it is exceeded. NFR-02 is measured "against a simulated provider with 100 ms", which says nothing about the real behaviour.
- **Fix:** NFR-21 (provider limits): a limiter per instance = `PROVIDER_MAX_RPS / WORKER_INSTANCES` (by default 1 worker and 8 rps); a separate Resend team for staging; the provider's `429` is transient and honours `Retry-After` (it already did).

### 12. Idempotency: the key recommended to clients is wrong and there are three inconsistencies
- **Where:** `07` §11 ("`signup-{userId}-{date}`"), `07` §3 (example `signup-8f14e45f-2025`); README ("Idempotency **mandatory**") against AC-08.6 ("it is accepted anyway"); `05` §3 (rate limit in step 3, idempotency in step 8); AC-21.5.
- **What is wrong:** a key per user and **date** collapses two legitimate requests on the same day (two "forgot my password" → the second gets 200 with the first and is never sent) and does not protect a retry that crosses midnight. Idempotent retries consume rate limit and can get `429` instead of the original `200`. If the counter is incremented before validating, a rejected send **does** consume quota, against AC-21.5.
- **Fix:** the key is derived from the product's **domain event** (`password-reset:{resetRequestId}`); acceptance order: auth → idempotency (read) → validation → rate limit → insert, all in one transaction; AC-08.6 stays (accepting without a key) but the README stops saying "mandatory" and the products are required to send it by the integration contract.

### 13. Privacy and retention: sensitive data kept too long and Law 8968 half covered
- **Where:** NFR-15, FR-23, `06` §8, `08` §6.
- **What is wrong:**
  - `variables` (with activation and recovery links) lives 90 days even though it is only needed until the last retry: a database dump hands over tokens.
  - `email_event.payload` is stored "in full" (recipient, subject, clicked URL with its query string).
  - Orphan events (`tenant_id NULL`) have no defined retention.
  - Erasure at the data subject's request is a "script" that only deletes messages (not events, `cc`/`bcc` or audit entries with the recipient) and has no deadline.
  - Logs: no retention and no rule to mask addresses.
  - Law 8968: transferring addresses and content to a provider in the US (Resend) requires express consent, because there is no adequacy regime (art. 14); the Regulation (Decree 37554-JP, arts. 38–39) requires notifying a breach to the affected people and to PRODHAB within **5 business days**; ARCO rights are handled within 5 business days ([Law 8968 summary](https://www.recordinglaw.com/world-laws/world-data-privacy-laws/costa-rica-data-privacy-laws/)). None of this appears. The reform (bill 23.097) is still not approved according to the available information ([Delfino](https://delfino.cr/asamblea/proyecto/23097)); its 2026 status is **to be confirmed**.
  - An inconsistency in priorities: NFR-15 (privacy) is **Must** and FR-23 (the purge that implements it) is **Should**.
- **Fix:** FR-23 becomes Must; `variables` are purged when a terminal state is reached + 7 days, and those marked `x-sensitive` on sending; a minimized event payload; orphans 30 days; FR-29 (data-subject erasure, an administration endpoint, ≤ 5 business days, the suppression kept as a hash); NFR-20 (compliance) with the obligations of Law 8968 and the note that I am not a lawyer: to be validated with legal counsel.

### 14. The cost goal is impossible with the reference environment
- **Where:** `03` header ("small managed PostgreSQL (2 vCPU / 4 GB)") and NFR-16 ("≤ 25 USD/month").
- **What is wrong:** a 2 vCPU/4 GB managed PostgreSQL costs 60.90 USD/month on DigitalOcean; the 1 vCPU/1 GB one, 15.15 USD ([DO prices](https://www.digitalocean.com/pricing/managed-databases)). The goal is not met even by the database alone.
- **Fix:** reference environment = a 1 vCPU/1 GB managed database + a 1 GB app instance; NFR-16: ≤ 25 USD/month with the provider's free plan and ≤ 50 USD/month with Resend Pro.

### 15. Outdated or wrong provider prices and limits
- **Where:** `04` §5 and ADR-0003.
- **What is wrong / checked today:**
  - Amazon SES: the document says "0.16 USD/1,000"; the current price is **0.10 USD/1,000**, plus 0.12 USD/GB of attachments; new customers get up to 200 USD in credits ([AWS SES pricing](https://aws.amazon.com/ses/pricing/)).
  - Postmark at 50,000/month: the document says "~15–18 USD base + overage"; the real figure is 87 USD (Basic), 68.50 USD (Pro) or 66 USD (Platform) ([Postmark pricing](https://postmarkapp.com/pricing)).
  - Resend at 500,000/month: "~90 USD + overage" is wrong (the 90 USD Scale plan includes 100,000); the price of the 500,000 tier is **to be confirmed**. Resend Pro also has a 35 USD tier for 100,000 ([Resend — pricing KB](https://resend.com/docs/knowledge-base/what-is-resend-pricing)).
  - Confirmed: Resend free 3,000/month with 100/day and 3 domains; Pro 20 USD/50,000 with 10 domains and 5 webhook endpoints; overage 0.90 USD/1,000 ([Resend pricing](https://resend.com/pricing)).
  - Missing data that changes the design: the 10 rps limit (#11), native idempotency (#2), global suppression per team (#6), a dedicated IP only with > 3,000 emails/day ([Resend — dedicated IPs](https://resend.com/docs/knowledge-base/how-do-dedicated-ips-work)).
- **Fix:** the table in `04` §5 redone with a date and a source; the triggers for a change recomputed (#20).

### 16. A retry window with no limit against the provider's 24 h, and a backoff with an off-by-one
- **Where:** AC-13.1 / AC-13.3, `05` §6 (`RETRY_BACKOFF_SECONDS=60,300,900,3600,21600`, `MAX_ATTEMPTS=5`).
- **What is wrong:** with 5 attempts there are 4 waits; the 6 h one is never used. Nothing prevents configuring a backoff that exceeds Resend's 24 h of idempotency, and then the retry could duplicate.
- **Fix:** `MAX_ATTEMPTS` = number of waits + 1 (validated at startup); the sum of waits + `LOCK_TIMEOUT` < 23 h or the app does not start; exceeding the window → `FAILED` with `RETRY_WINDOW_EXCEEDED`.

### 17. There is no "time to send" requirement and no priority: a batch can delay a password recovery
- **Where:** NFR-01 only measures acceptance; the queue orders only by `next_attempt_at`.
- **Why it matters:** for the user, what counts is when the code arrives, not when the API responded. A notice to 2,000 of Colmena's customers queued earlier puts 2,000 messages in front of Payverica's verification email.
- **Fix:** NFR-19: acceptance→`SENT` p95 ≤ 5 s with a healthy provider and a queue free of retries; a `priority` column derived from the template's category (`SECURITY` > `TRANSACTIONAL` > `NOTICE`) and the order `priority, next_attempt_at` when claiming (FR-36).

### 18. A single provider account for every product: an undocumented account risk
- **Where:** `04` §5 ("Separate domains and keys per product, which aligns with the multi-tenant design").
- **What is wrong:** separating domains does not separate the **account**: shared suppression (#6), a shared rate limit (#11), shared IPs on the Pro plan ([Resend — dedicated IPs](https://resend.com/docs/knowledge-base/how-do-dedicated-ips-work)) and, above all, if one product generates complaints, the provider's review affects the whole account. It is the central risk of task 3 (bulk email).
- **Fix:** documented in `04` §5.4 and in ADR-0013; rule: no bulk sending shares an account with transactional email; an open question about one account per product.

### 19. Open and click tracking enabled by default in transactional email
- **Where:** `01` §5.9, FR-16/FR-17 (`opened`, `clicked` as expected events).
- **What is wrong:** click tracking rewrites links through the provider's domain, including password recovery and payment verification links (the token passes through a third party and appears in its logs); opens are unreliable with mail clients' privacy protection, and it is processing of personal data with no need. Nothing justifies it in transactional email.
- **Fix:** tracking disabled by default; can be enabled per template of the `NOTICE` category; forbidden in `SECURITY` (AC-04.11).

### 20. The triggers for changing provider and queue do not measure what matters
- **Where:** `04` §4–5, ADR-0002, ADR-0003, `09` Phase 3.
- **What is wrong:** "> 50 messages/second sustained" is equivalent to ~130 million emails/month: 1,300 times the target volume and 5 times Resend's limit, so it would never trigger. "To Postmark if deliverability becomes the priority" is not measurable. "To SES when it exceeds ~200,000/month" was computed with a wrong SES price.
- **Fix:** queue: review if claim p95 > 50 ms, if queue age > 60 s with a healthy provider for 3 days, or if the volume exceeds 3 M/month. Provider: Postmark if `delivery_delayed` + bounces > 2 % for 7 days attributable to the provider, or after an account incident; SES when the verified monthly saving exceeds 150 USD for 3 months (at 200,000/month SES ≈ 20 USD against Resend Scale ≥ 90 USD; exact tier to be confirmed).

### 21. NFR-15 Must with FR-23 Should, and data-subject erasure with no deadline or endpoint
Included in #13 because they are linked; fixed with FR-23 → Must and a new FR-29.

---

## MINOR

22. **`01` §2, `05` §1, `06` §3.1, `07`, README, AGENTS.md — fictional products.** "finance-app / automation / ai-tool" are not your products. Replaced by PipeMend, Colmena, Chinamo and Payverica, with their sending profile (`01` §2.1).
23. **`04` §3 — broken references.** "The project's ADR-0005: no other product accesses it" (ADR-0005 is about templates) and "an 8-table domain" (the model has 10). Fixed.
24. **`01` §7 against `04` §4.** "Peaks of tens per second" against "the goal is tens per minute". Unified as: average 0.04 msg/s, peaks of up to 20 msg/s for seconds.
25. **AC-20.1 against `07` §5.** `to` appears twice (recipient and date); `07` uses `createdAfter`/`createdBefore`. `07` is adopted.
26. **`07` §3 — incomplete error list.** Missing `400 MALFORMED_REQUEST`, `403 RECIPIENT_NOT_ALLOWED_IN_ENV`, `422 RECIPIENT_SUPPRESSED` and the new ones (`INSUFFICIENT_SCOPE`, `IP_NOT_ALLOWED`, `TENANT_SENDING_PAUSED`, `UNSAFE_URL`, `ATTACHMENT_INVALID`).
27. **`05` §6 — incomplete configuration.** Missing `SUPPRESSION_REJECT_MODE`, `IDEMPOTENCY_RETENTION_HOURS`, `PROVIDER_CONNECT_TIMEOUT`/`READ_TIMEOUT` and the new ones.
28. **`07` §8 against `05` §2.** "The signature adapter lives in the `events` module next to the corresponding `EmailSender`", but `EmailSender` lives in `provider`. The `WebhookVerifier` port is defined in `provider`, consumed by `events`.
29. **`06` §4 — unreachable failure codes.** `TEMPLATE_NOT_PUBLISHED` cannot happen in the worker (the version is pinned on acceptance) and `TENANT_SUSPENDED` stops being a failure (#9). Marked DEPRECATED.
30. **`01` OBJ-4.** A list of final states without `COMPLAINED` or `CANCELED`, and with `SENT`, which is not always final. Fixed with the rule "SENT without events for 72 h = time-based final".
31. **AC-24.1/24.4 — health.** `DEGRADED` is not a standard Spring Boot Actuator state (`UP`, `DOWN`, `OUT_OF_SERVICE`, `UNKNOWN`); also, "health reflects the provider" suggests pinging the provider (it spends rate limit). A custom indicator is defined based on recent results, with a custom state mapped to HTTP 200, and liveness/readiness without external dependencies.
32. **NFR-05 — a circuit breaker without a requirement.** It is mentioned with no thresholds or library. Specified: open after 5 consecutive transient failures, half-open after 60 s, implemented by hand (~50 lines) without adding dependencies.
33. **`05` §5 against `06` §3.9 — task locks.** `05` says `FOR UPDATE SKIP LOCKED` on `scheduled_lock` and `06` defines `locked_until`. Replaced by `pg_try_advisory_xact_lock`, with no table.
34. **NFR-09 + `08` §8 — Flyway at startup in N instances with the same role.** With RLS, the owner of the tables bypasses the policies unless `FORCE`. Migrations become a deployment step with the owner role; the app runs with a role without DDL privileges.
35. **`08` §2 against `07` §9 — key format.** "≥ 32 bytes in Base62" (43 characters) against the hexadecimal example; a 4-character prefix with possible collisions. Fixed: an 8-character Base62 prefix with a retry on collision and a 43-character Base62 secret.
36. **`07` §6 — date and number formatting without a locale.** There is no language, time zone or currency (colón, `America/Costa_Rica`). `locale` and `timezone` are added to the tenant, and the helpers `formatDate`, `formatNumber` and `formatMoney` with explicit parameters.
37. **AC-04.2, AC-07.4 — optional `variablesSchema` and an undefined subset.** Without a schema, AC-07.4 is not verifiable. It becomes mandatory to publish, with the subset defined (`required`, `type`, `format: uri|email|date-time`, `maxLength`, `maxItems`, `x-sensitive`).
38. **AC-20.2, NFR-01 — "1,000,000 rows" without a distribution.** The dataset is defined: 4 tenants, 1 M rows each, 70 % of the volume in one.
39. **`05`/AC-14.4 — `X-Entity-Ref-ID` as correlation.** That header serves to keep Gmail from grouping threads, not to correlate. Correlation is `provider_message_id` plus the provider's `message_id` tag (Resend's tags accept only ASCII, `_` and `-`, max. 256 characters, according to [Send email](https://resend.com/docs/api-reference/emails/send-email)).
40. **`06` §3.8 — rate limit counter.** A "hot" row per tenant and window; the per-IP rate limit for `401`s does not say where it lives. Accepted (at < 200 rps it is more than enough), documenting that the per-IP limit is in memory per instance, acceptable because it is a secondary defence.
41. **Template size and Gmail clipping.** Without limits, an email with more than ~100 KB of HTML is clipped in Gmail (limit **to be confirmed**). A 100 KB limit for the rendered HTML (warning) and 256 KB for the source (rejection) are set.
42. **`08` §2 — optional internal TLS.** "On an internal network, TLS anyway if the hosting provider allows it without friction": API keys travel as bearer tokens. TLS or an encrypted private network (WireGuard/6PN) becomes mandatory.
43. **NFR-14 — backup without RPO/RTO or encryption.** RPO ≤ 24 h (≤ 5 min with PITR), RTO ≤ 4 h and backups encrypted at rest are set.

---

## IMPROVEMENTS

44. **Rendering on acceptance too (AC-07.5).** Rendering costs microseconds with a logic-less engine; doing it in the API (and discarding the result) turns an asynchronous `RENDER_ERROR` into a synchronous `422`. Applied.
45. **Batch endpoint and persistent connections (task 4).** Measured: with keep-alive, parsing and serializing JSON is ~1.5 % of server time (everything that is not the database, ~3 %); grouping 100 messages in one transaction drops from ~0.75 to ~0.25 ms/message. FR-35 and ADR-0014.
46. **Anomaly detection (task 3).** An automatic pause on volume, bounces and complaints per tenant (FR-34).

---

## What to check in the consuming products

I do not have their documents. Review in each one (PipeMend, Colmena, Chinamo, Payverica):

1. **Template keys:** that the `templateKey`s they use exist with the same name (kebab-case, e.g. `password-reset`) and that each one has a category (`SECURITY`/`TRANSACTIONAL`/`NOTICE`).
2. **Idempotency:** that the `Idempotency-Key` is derived from an event identifier of their domain, not from user+date nor random per attempt.
3. **Response contract:** that they treat `202` and `200` as success, and that a `202` with `status=FAILED, failureCode=SUPPRESSED` is **not** retried.
4. **Errors:** that they interpret `code` (not `title`/`detail`), retry only `429`/`5xx` honouring `Retry-After`, and do not break on new codes (`INSUFFICIENT_SCOPE`, `TENANT_SENDING_PAUSED`, `UNSAFE_URL`).
5. **Recipients:** that they do not send several recipients in `to` (the contract accepts one) nor use `cc` to notify third parties.
6. **Language and format:** the language of the emails (all `es-CR`?), the currency (CRC/USD) and the time zone they format amounts and dates with, especially in Chinamo and Payverica.
7. **Sensitive data in variables:** what they put in `variables` (Payverica: SINPE phone numbers, amounts, the payer's names?) and which ones must be marked `x-sensitive`.
8. **Attachments and scheduled sending:** whether Chinamo/Colmena send receipts by email (XML+PDF and real sizes) and whether any product needs `sendAt` (Colmena's reminders).
9. **Privacy notices:** that each product informs about, and obtains consent for, transferring data to an email provider in the US (Law 8968, art. 14).
10. **Credentials:** where they store the API key, that they use one key per environment and that the production one does not have `templates:write`.
11. **Links:** the domains that appear in their emails' links, to configure `allowedLinkHosts`.
12. **Client timeouts:** connect ≤ 2 s and read ≤ 5 s, with keep-alive, and an outbox pattern in the product if the email is critical (so it is not lost if the email-service does not respond).

---

## Conclusions of tasks 2, 3 and 4 (details in the ADRs)

- **Stack (ADR-0009):** Java 25 + Spring Boot 4.1 + PostgreSQL 18 is **kept**, but with another justification: an I/O profile solved with virtual threads, a marginal hosting cost of 0 to 5 USD/month compared with Go, and above all the cost of maintaining a fifth stack in the portfolio. Go is the serious alternative (it scores 3.90 against 4.15 in the weighted matrix); Elixir/Oban is the best technology for queues, but the worst bet for a single maintainer with no BEAM experience.
- **Bulk email (ADR-0013):** **not yet.** Reputation is not separated by microservice but by the provider's account, domain and IP; the real risk is that a campaign with complaints contaminates the suppression and the reputation of the Resend account that sends password recoveries. A concrete trigger and the mandatory separation design in `11-bulk-email.md`.
- **Intake protocol (ADR-0014):** REST stays. Measured on the acceptance path: the database accounts for 96 % of server time (1.33 of 1.38 ms p50); parsing, authenticating, validating and serializing add up to ~44 µs. Protobuf against JSON saves nothing measurable (4.81 against 3.43 µs). What does matter is reusing connections (a new TLS connection per request adds ~2.5 ms on loopback plus 2 network RTTs) and grouping sends (the batch endpoint). Threshold to review: > 200 requests/s sustained or > 30 M emails/month.

---

## Sources (consulted on 2026-10-05)

- [Resend — Pricing](https://resend.com/pricing) · [Resend — What is Resend pricing](https://resend.com/docs/knowledge-base/what-is-resend-pricing) · [Resend — API introduction (rate limit)](https://resend.com/docs/api-reference/introduction) · [Resend — Idempotency keys](https://resend.com/docs/dashboard/emails/idempotency-keys) · [Resend — Webhook event types](https://resend.com/docs/dashboard/webhooks/event-types) · [Resend — Verify webhooks](https://resend.com/docs/dashboard/webhooks/verify-webhooks-requests) · [Resend — Email suppressions](https://resend.com/docs/dashboard/emails/email-suppressions) · [Resend — Dedicated IPs](https://resend.com/docs/knowledge-base/how-do-dedicated-ips-work) · [Resend — Send email](https://resend.com/docs/api-reference/emails/send-email)
- [Postmark — Pricing](https://postmarkapp.com/pricing) · [Postmark — Message Streams](https://postmarkapp.com/message-streams)
- [Amazon SES — Pricing](https://aws.amazon.com/ses/pricing/)
- [DigitalOcean — Managed Databases pricing](https://www.digitalocean.com/pricing/managed-databases)
- [endoflife.date — Spring Boot](https://endoflife.date/spring-boot) · [PostgreSQL](https://endoflife.date/postgresql) · [Go](https://endoflife.date/go) · [Node.js](https://endoflife.date/nodejs) · [Elixir](https://endoflife.date/elixir) · [Erlang/OTP](https://endoflife.date/erlang) · [Rust](https://endoflife.date/rust)
- [handlebars.java — README](https://github.com/jknack/handlebars.java) · [Releases](https://github.com/jknack/handlebars.java/releases)
- [Gmail — Email sender guidelines FAQ](https://support.google.com/mail/answer/14229414?hl=en) · [Yahoo — Sender best practices](https://senders.yahooinc.com/best-practices/) · [Microsoft sender requirements (PowerDMARC summary)](https://powerdmarc.com/microsoft-sender-requirements/)
- [FTC — CAN-SPAM Act: A Compliance Guide for Business](https://www.ftc.gov/business-guidance/resources/can-spam-act-compliance-guide-business)
- [Law 8968 and PRODHAB — 2026 summary](https://www.recordinglaw.com/world-laws/world-data-privacy-laws/costa-rica-data-privacy-laws/) · [Bill 23.097 (Delfino)](https://delfino.cr/asamblea/proyecto/23097) · [Law 10946 (Bufete de Costa Rica)](https://bufetedecostarica.com/gobernanza-de-los-servicios-digitales-y-el-comercio-electronico-en-costa-rica-10946/)
- [Alegra — Electronic receipts v4.4](https://blog.alegra.com/costa-rica/comprobantes-provisionales-y-electronicos-costa-rica/)
