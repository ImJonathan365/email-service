# Decision summary — audit of 2026-10-05

Accepted by the owner on 2026-10-05, except row 16 (attachments), which they rejected. Rows 31–36 record their answers to the open questions.

| # | Area | Previous decision | New decision | Reason (one line) | ADR / requirement |
|---|---|---|---|---|---|
| 1 | Isolation | `tenant_id` in code; RLS in phase 2 | Fail-closed RLS `FORCE` from V2 + composite FKs + 3 DB roles + 2 `DataSource`s | Personal data of 4 products; a single code mistake must not be enough to leak it | ADR-0008 (supersedes 0007), NFR-08 |
| 2 | Language / stack | Java 25 + Spring Boot 4.1 "for consistency" | Kept, with virtual threads, a weighted matrix (Java 4.15 against Go 3.90) and an explicit criterion for changing | 96 % of the latency is the database; the deciding factor is not adding a stack to the portfolio | ADR-0009 (supersedes 0006) |
| 3 | Delivery semantics | "Exactly once" with a residual risk | At least once + `Idempotency-Key = message.id` towards Resend (24 h) + a window < 23 h | Resend already offers idempotency; the previous design did not close the timeout duplicate | ADR-0010, NFR-04 |
| 4 | Queue | Batch of 25, a shared lock, a sweep that did not count attempts | Claim by free slots, `attempts++` on claiming, `lock_token` (fencing), a 60 s lock per message | Avoids duplicates between workers and infinite loops with "poison" messages | ADR-0010 (supersedes 0002), FR-11/12 |
| 5 | Priority | Only by `next_attempt_at` | `priority` by category (`SECURITY` > `TRANSACTIONAL` > `NOTICE`) | A mass notice must not delay a password recovery | FR-36, NFR-19 |
| 6 | Queue trigger | > 50 msg/s sustained | Claim p95 > 50 ms, age > 60 s, > 3 M/month or fan-out | 50 msg/s ≈ 130 M/month: it would never have triggered | ADR-0010, NFR-06 |
| 7 | State machine | `DELIVERED` terminal; "never goes back" | Order of precedence; `DELIVERED` stable → `BOUNCED`/`COMPLAINED`; effects independent of the state | Complaints always arrive after delivery | `02` §2, FR-17 |
| 8 | Events | delivered/bounced (hard/soft)/complained/opened/clicked/deferred | Full Resend mapping, including `failed`, `suppressed` and `delivery_delayed`; `BOUNCED_SOFT` → `DELIVERY_DELAYED` | The provider emits no "soft bounce"; suppressed messages stayed `SENT` | `02` §2.1, `06` §3.6 |
| 9 | Webhooks | `Webhook-Id/Timestamp/Signature` | `svix-id/timestamp/signature`, raw body, a secret per endpoint, dedupe by `svix-id` | They are Resend's real headers | FR-16, `07` §9 |
| 10 | Suppression | Per tenant, always | `HARD_BOUNCE`/`COMPLAINT`/`PROVIDER` global; `MANUAL` per tenant; `to`/`cc`/`bcc` checked; `email_hash` | Resend's list is team-wide | ADR-0012, FR-10, FR-18 |
| 11 | Templates | Logic-less Handlebars, `{{{ }}}` forbidden | Also: `{{& }}` and partials forbidden, a helper whitelist, `MapValueResolver`, an HTML linter, context-aware escaping, limited URLs | Really closing injection and XSS | ADR-0011 (supersedes 0005), FR-04, AC-07.8 |
| 12 | API keys | One key can do everything | Scopes + optional CIDR; an 8-character prefix; several admin keys | A leaked key must not be able to publish templates | ADR-0017 (supersedes 0004), FR-33 |
| 13 | Anti-abuse | Rate limit + a quota of 5,000/day | A default quota of 1,000 (~3× the expected volume) + automatic pause on volume, bounces and complaints | Contain a leaked key within minutes | FR-34, FR-21 |
| 14 | Suspended tenant | Contradictory (`FAILED` against held) | Held in `QUEUED`; `TENANT_SUSPENDED` stops being a `failure_code` | A preventive suspension must not destroy the queue | AC-02.3, `06` §4 |
| 15 | Client idempotency | Recommended key `signup-{userId}-{date}`; check after the rate limit | Key = domain event id; check before the rate limit; counter in the same transaction | The date-based key collapsed legitimate requests; repeats consumed quota | FR-08, FR-21, `07` §12 |
| 16 | Attachments | Out of the MVP | ~~In, bounded~~ → **still out** (owner: no product sends them); design kept | No real use case | ADR-0015 **Rejected**, FR-30 Won't |
| 17 | Scheduled sends | Out of the MVP | **Should** (`sendAt` ≤ 30 days; owner) | Almost zero cost thanks to `next_attempt_at` | FR-31 |
| 18 | Outgoing webhooks | Out of the MVP | Still out (Won't) with a measurable trigger | No product needs them to operate | FR-32 |
| 19 | Retention and privacy | Variables 90 days; full event payload; purge as Should | Variables after 7 days (sensitive ones on sending); minimized payload; purge as Must; data-subject erasure; logs 30 days | Minimize personal data and tokens (Law 8968) | FR-23, FR-29, NFR-15, NFR-20 |
| 20 | Open and click tracking | Active as an expected event | Disabled by default; only `NOTICE`; forbidden in `SECURITY` | Do not let a third party rewrite links with tokens | AC-04.11 |
| 21 | Provider | Resend; a comparison with wrong prices | Resend ratified; prices verified (SES 0.10 USD/1,000); Pro as soon as there is > 1 product; staging on another account | Real data; the free plan's 100/day limit blocks critical emails | ADR-0016 (supersedes 0003), `04` §5 |
| 22 | Provider triggers | "Critical deliverability" / > 200,000/month | Delays/bounces > 2 % over 7 days or an account incident → Postmark; saving > 150 USD/month for 3 months → SES | Make them measurable | ADR-0016 |
| 23 | Rate limit towards the provider | "Respects the rate limit" with no mechanism | `WORKER_PROVIDER_RPS` = 8 per instance; sum ≤ 80 % of the account limit (10 rps) | Resend limits to 10 rps per team | NFR-21, AC-12.6 |
| 24 | Bulk email | Out forever (anti-roadmap) | Out for now, with a concrete trigger and a mandatory design (another account, subdomain, queue and role) | Reputation is separated by account, domain and IP, not by microservice | ADR-0013, `11` |
| 25 | Intake protocol | REST/JSON | REST/JSON + mandatory keep-alive + a batch endpoint; no gRPC, binary or queue | Measured: the protocol is ~1.5 % of the time; the batch saves ~3× of database cost | ADR-0014, FR-35 |
| 26 | Cost | ≤ 25 USD/month with a 2 vCPU/4 GB database | ≤ 25 USD (free plan) / ≤ 50 USD (Pro) with a 1 vCPU/1 GB database | The reference database cost 60.90 USD/month | NFR-16 |
| 27 | Migrations | Flyway at startup in every instance | An `APP_ROLE=migrate` job with the owner role | With RLS, the owner of the tables must not run the app | NFR-09 |
| 28 | Health | `DEGRADED` and "reflects the provider" | Liveness without dependencies; readiness only the database; a provider indicator based on recent results; a custom state mapped to 200 | `DEGRADED` is not standard; pinging the provider spends rate limit | FR-24 |
| 29 | Scheduled tasks | A `scheduled_lock` table | `pg_try_advisory_xact_lock` | Simpler; the documents contradicted each other | `05` §5 |
| 30 | Internal transport | TLS "if there is no friction" | TLS or an encrypted private network required | API keys are bearer tokens | `08` §2 |
| 31 | Locales | Only `tenant.locale`; i18n in phase 2 | Versions per locale (`es-CR`, `en`), `locale` per message falling back to Spanish | The owner wants English implemented and ready | ADR-0018, FR-37 |
| 32 | Sender name | Fixed per tenant | Optional `fromName` per request | Colmena sends on behalf of each business | AC-09.5 |
| 33 | Suppressed recipient | `FAILED/SUPPRESSED` | Also `suppressionReason`, so that the product uses another address | An alternative channel for recovery (answer 13) | AC-10.1 |
| 34 | Regulatory scope | GDPR/CAN-SPAM "to be confirmed" | They do not apply (Costa Rica only) | Answer 8 | NFR-20, `11` §5 |
| 35 | Third-party data (Colmena) | Assumed internal use | Colmena as processor; email-service as sub-processor; debt `x-sensitive` | Answer 12 | NFR-20, `08` §6.1 |
| 36 | Privacy notice / transfer | Blocking "for production" | Pending; does not block development | Answer 9 | `08` §10 |
