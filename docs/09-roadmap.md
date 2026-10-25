# 09 — Roadmap

## Phase 0 — Functional MVP (goal: ~7 part-time weeks)

Goal: a real product sending email in production through the service.

| Milestone | Content | FR / NFR |
|---|---|---|
| **H0. Decisions** ✅ (2026-10-05) | ADR-0008 to 0014, 0016 and 0017 accepted; 0015 rejected; 0018 accepted; open questions answered | — |
| **H1. Skeleton** | Repo, Docker Compose (app + PostgreSQL with 3 roles + Mailpit), Flyway with a `migrate` job, base RLS, health, OpenAPI, CI, ArchUnit; measure RSS (ADR-0009) | FR-24, FR-25, NFR-08, NFR-09, NFR-12 |
| **H2. Tenancy** | Tenants, API keys (issuing, revoking, hash, scopes, CIDR), authentication, tenant context + `set_config`, administration, basic auditing; measure the cost of RLS | FR-01, FR-02, FR-03, FR-22, FR-33 |
| **H3. Templates** | CRUD, categories, versions per locale (es-CR, en), immutable publication, hardened Handlebars + linter, context-aware escaping, preview | FR-04, FR-05, FR-06, FR-36, FR-37 |
| **H4. End-to-end sending** | `POST /v1/emails`, validations, allowed URLs, idempotency, queue with priority and fencing, worker, `SmtpEmailSender` → Mailpit | FR-07 to FR-13, FR-15 |
| **H5. Real provider** | `ResendEmailSender` with `Idempotency-Key`, rate limiter, circuit breaker; verified domain (SPF/DKIM/DMARC); real sending from staging (separate account); "lost response" test | FR-14, NFR-04, NFR-21 |
| **H6. Events** | Svix webhooks (raw body), full event mapping, states by precedence, global/tenant suppression, provider mirror, suppression API | FR-10, FR-16, FR-17, FR-18 |
| **H7. Operations and protection** | Queries and listings, rate limit and quotas, anomalies and pause, metrics and alerts, purging (variables, retention), data-subject erasure, correlated logs | FR-19, FR-20, FR-21, FR-23, FR-29, FR-34 |
| **H8. Batch and scheduled sending** (rev. 2026-10) | Batch endpoint, `sendAt` | FR-35, FR-31 |
| **H9. Production** | Deployment (migrate → api → worker), encrypted backups, tested restore, security checklist, README and integration of the first product | `08` §10 |

**Phase 0 definition of done:**

- [ ] A real product in production sends its emails through the service.
- [ ] `docker compose up` from a clean clone allows sending an email to Mailpit without external credentials.
- [ ] All **Must** FRs have their acceptance criteria covered by automated tests.
- [ ] Multi-tenant isolation tests green for every `/v1/**` endpoint, plus the no-context test (RLS) and the cross-FK test.
- [ ] With the provider down (simulated), the API keeps accepting and the queue drains when it recovers.
- [ ] "Lost response" test: the simulated provider records **one** send.
- [ ] NFR-19 measured in staging: acceptance → `SENT` p95 ≤ 5 s.
- [ ] Security checklist (`08` §10) complete.
- [ ] README with an integration guide in < 30 minutes.

## Phase 1 — Operational comfort (after 2–3 integrated products)

| Improvement | Why | Prerequisite |
|---|---|---|
| **Minimal web dashboard** (list of sends, detail, manual resend, template preview, paused tenants) | Stop querying with `curl` and SQL | Day-to-day operation really hurting |
| **Template editor and preview** with saved sample data | Iterate on design without deploying | Web dashboard |
| **Client SDK** (Java and TypeScript) generated from OpenAPI, with correct keep-alive, retries and idempotency by default | Integrate new products faster and without contract mistakes | 3+ products |
| **Metrics exported to Prometheus + a dashboard** | Historical visibility | Volume that justifies it |
| **Attachments** (FR-30, design in ADR-0015) | Only if a case appears that a signed link does not solve | A real use case |

## Phase 2 — Robustness

| Improvement | Why |
|---|---|
| ~~Row Level Security in PostgreSQL~~ | **(rev. 2026-10)** Moves to the MVP (ADR-0008) |
| **Inline content without a template** (enabled per tenant) | Occasional cases; with sanitization and the linter |
| **More languages and controlled partials** | es-CR and en are already in the MVP (ADR-0018); shared header and footer |
| **Request signing (HMAC) on top of the API key** | Extra defence if the service is exposed outside the private network |
| **Automated load tests** | Validate NFR-01, NFR-02 and NFR-19 as it grows |
| **Sandbox mode per tenant** | Simulate sends in test integrations |
| **Outgoing webhooks** (FR-32) | Only if its trigger is met |

## Phase 3 — Scale (only with metrics that justify it) (rev. 2026-10)

| Improvement | Concrete trigger |
|---|---|
| **External queue (SQS)** | Claim p95 > 50 ms for 3 days, queue age > 60 s with a healthy provider for 3 days, > 3 M emails/month or fan-out (ADR-0010) |
| **Multi-provider with failover** | A real provider outage with `SECURITY` emails delayed > 30 min |
| **Migration to Postmark** | Delays/bounces attributable to the provider > 2 % for 7 days, or an account incident (ADR-0016) |
| **Migration to Amazon SES** | Verified saving > 150 USD/month for 3 months (ADR-0016) |
| **Partitioning `email_message`** | > 50 million rows or a measurable degradation of the listings |
| **Read replica** | Listings affect acceptance latency |
| **Dedicated IP and warm-up** | > 3,000 emails/day sustained (Resend's requirement) and a shared IP reputation that hurts measurably |
| **An intake protocol other than REST** | > 200 req/s sustained, or p95 > 150 ms not attributable to the database, or > 30 M emails/month (ADR-0014) |
| **One provider account per product** | A complaint or an account review caused by one product measurably affects another (ADR-0012) |

## Bulk and marketing email (rev. 2026-10, ADR-0013)

It enters no phase until its trigger is met (`11-bulk-email.md` §2):
- (a) ≥ 1,000 non-transactional recipients more than once a month for 2 months;
- (b) ≥ 2 products with campaigns and no proven consent record in the provider's tool;
- (c) > 100 USD/month on the provider's marketing product for 3 months.

Meanwhile, bulk notices are sent with the provider's broadcast product in a separate account and subdomain.

## Anti-roadmap (what will not be done)

- ~~Marketing campaigns, subscription lists, segmentation, A/B testing~~ **(rev. 2026-10)**: replaced by "Bulk and marketing email" above. There is a trigger and a mandatory design; it is not done "halfway" inside the transactional flow.
- Receiving and parsing inbound email.
- A drag-and-drop visual template editor.
- Active-active multi-region.
- Kubernetes or a service mesh for a service with two roles.
- Turning it into a SaaS product for third parties (it would completely change the billing, isolation and compliance requirements; it would be another project).
- gRPC or bus-based intake "for performance" (measured: it adds nothing, ADR-0014).

## Criterion for moving something between phases

An improvement moves up a phase when there is **a real metric or incident** that justifies it, not when it seems interesting. It is recorded in an ADR: what was observed, what is decided and what is expected to improve.
