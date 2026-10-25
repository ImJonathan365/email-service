# 04 — Technology stack and justification

> Revision 2026-10-05: versions verified, the language justification rewritten with a weighted matrix and measurements (ADR-0009), the provider comparison corrected (ADR-0016), the intake protocol evaluated (ADR-0014).

## 1. Summary of the choice

| Layer | Choice | Verified version (2026-10-05) |
|---|---|---|
| Language | **Java 25 (LTS)** | 25 |
| Framework | **Spring Boot 4.1** (Web MVC + Actuator + Validation), virtual threads enabled | 4.1.1 (OSS until 2027-07-31) |
| Database | **PostgreSQL 18** | 18.6 (supported until 2030-11-14) |
| Data access | **Spring Data JDBC** (no JPA/Hibernate) + **Flyway**, two `DataSource`s (tenant with RLS / system) | Managed by the BOM |
| Job queue | **PostgreSQL** (`FOR UPDATE SKIP LOCKED` + `lock_token` + priority) | — (no broker) |
| Templates | Hardened **Handlebars.java** (ADR-0011) + jsoup for the linter | **(rev. 2026-10-06)** Handlebars.java 4.5.5 (2026-09-14), without Nashorn; jsoup 1.23.2 |
| Email provider | **Resend** through its HTTP API (alternatives: Postmark, Amazon SES) | Current API |
| Development SMTP | **Mailpit** | Latest |
| API documentation | **springdoc-openapi** | Compatible with Boot 4.1 |
| Build | **Gradle (Kotlin DSL)**, Java 25 toolchain | Wrapper committed |
| Containers | **Docker + Docker Compose**, base image `eclipse-temurin:25-jre` | — |
| Tests | JUnit 5, Testcontainers (PostgreSQL with roles and RLS), WireMock (provider), Awaitility, ArchUnit | Latest compatible |
| Observability | Micrometer + Prometheus endpoint; JSON logs to stdout; optional OTLP | — |
| CI | GitHub Actions (build, tests, gitleaks, dependency scanning, OpenAPI diff) | — |

> Major versions are pinned here and in ADR-0009; patch versions in `build.gradle.kts` and `docker-compose.yml`.

## 2. Language and framework (rev. 2026-10)

**Java 25 LTS + Spring Boot 4.1**: a decision ratified in ADR-0009 after comparing it with Go, Node/TypeScript, Elixir and Rust. Summary:

- **The language is not the bottleneck.** Measured: the acceptance path takes 1.38 ms p50 on the server, and 96 % of it is PostgreSQL. Parsing, auth, validation and serialization add up to ~44 µs. That holds at our volume and at 100 times more.
- **The I/O profile is covered** with virtual threads (`spring.threads.virtual.enabled=true`) in the API and the worker.
- **A complete ecosystem** for what the service needs: validation, Actuator, springdoc, Flyway, Testcontainers, ArchUnit, Handlebars.java and JSON Schema.
- **Portfolio cost.** You already run Java 25 + Spring Boot 4.1 in another service; a fifth stack is the highest long-term cost for a single maintainer. It is the deciding criterion, and it is said explicitly: without it, Go wins the matrix (4.63 against 3.94).
- **Against:**
  - more memory: the JVM's minimum RSS measured at 125 MB, against 72 MB for an equivalent Node server; a real Spring Boot app, 200–300 MB, to be confirmed in H1;
  - slower startup.

  It fits in the 1 GB reference instance; the estimated extra hosting cost is 0 to 5 USD/month.

Weighted matrix and sensitivity: ADR-0009. Spring Boot 3.5 ended its OSS support on 2026-06-30 and 4.0 ends it on 2026-12-31, so 4.1 is used.

## 3. Database

**PostgreSQL 18**, a database **owned exclusively by the service** (ADR-0001: no other product accesses it).

- `jsonb` for variables, webhook payloads and metadata, without inventing a table for every shape of data.
- `FOR UPDATE SKIP LOCKED` turns a table into a correct, observable job queue (ADR-0010).
- Partial indexes for the queue (`WHERE status = 'QUEUED'`), `UNIQUE` constraints for idempotency and **composite FKs + RLS** for isolation: correctness is guaranteed by the database, not the code (ADR-0008).
- Available as a managed service at any provider, with backups and PITR. Cost reference: DigitalOcean 1 vCPU/1 GB for 15.15 USD/month; 2 vCPU/4 GB for 60.90 USD/month ([prices](https://www.digitalocean.com/pricing/managed-databases)). A requirement to confirm with the chosen hosting: that it allows creating a role with `BYPASSRLS`, or per-role policies (ADR-0008).

**Data access: Spring Data JDBC + Flyway**, not JPA. Explicit queries, no first-level cache and no surprise lazy loading; for a domain of **10 tables** it is simpler and more predictable. Versioned migrations, immutable once applied, run by a deployment step with the owner role.

## 4. Message queue

**PostgreSQL as the queue** (the message row is the job + `SKIP LOCKED`), no RabbitMQ, Redis or Kafka. Reasons:

1. **Real atomicity:** the message and its queued job are the same row; there is no "stored but not queued" window and no dual write.
2. **One component less** to install, secure, back up and monitor.
3. **Trivial observability:** the queue's state is queried with SQL; retries, backoff and stuck messages are columns.
4. **More than enough:** measured, claiming takes 2.4–4.8 ms with 12,800 queued messages. The practical limit is set by the provider (Resend: 10 rps per team), not by the queue. **(rev. 2026-10)** This corrects the previous version, which spoke of "tens per minute", contradicting `01` §7.

**Review criterion (rev. 2026-10, ADR-0010):** claim p95 > 50 ms for 3 days, queue age > 60 s with a healthy provider for 3 days, > 3 M emails/month or a need for fan-out. Candidate: SQS with an outbox, keeping `email_message` as the record of truth. (The "> 50 msg/s" threshold is removed: it was equivalent to ~130 M emails/month and to 5 times the provider's limit.)

## 5. Transactional email provider (rev. 2026-10)

### 5.1 Comparison (data checked on 2026-10-05; review before contracting)

| Criterion | **Resend** | **Postmark** | **Amazon SES** |
|---|---|---|---|
| Entry plan | Free: 3,000/month, **100/day**, 3 domains, 1 webhook endpoint | Free: 100/month. Basic 15 USD / Pro 16.50 USD / Platform 18 USD for 10,000 | No permanent free plan; up to 200 USD in credits for new accounts |
| Price at 50,000/month | **20 USD** (Pro; 10 domains; 5 webhook endpoints) | 87 USD (Basic) / 68.50 USD (Pro) / 66 USD (Platform) | ≈ 5 USD (0.10 USD/1,000) + SNS/EventBridge |
| Price at 100,000/month | 35 USD (Pro) or 90 USD (Scale) | 177 / 133.50 / 126 USD | ≈ 10 USD + extras |
| Price at 500,000/month | **To be confirmed** (the Scale tier was not published in the consulted source; the scale reaches 2.5 M for 1,150 USD) | Overage of 1.20–1.80 USD/1,000 | ≈ 50 USD + extras |
| Default rate limit | **10 requests/s per team** | To be confirmed | Per account, depending on reputation (to be confirmed) |
| API idempotency | **Yes**: `Idempotency-Key`, 24 h, `409` if the body changes | To be confirmed | To be confirmed |
| Event webhooks | Yes, signed with Svix: `sent`, `delivered`, `delivery_delayed`, `bounced`, `complained`, `failed`, `suppressed`, `opened`, `clicked`… | Yes, very complete | Through SNS/EventBridge (extra wiring) |
| Suppression list | **Team-wide** (all domains), automatic | Per stream | Per account (optional) |
| Transactional / bulk separation | Separate domains; shared IPs unless a dedicated IP (> 3,000/day) | **Message Streams with separate IPs** by design | Configuration sets + dedicated IP pools |
| Initial friction | Very low | Low (account review) | High: leaving the sandbox, SNS, our own reputation |

Sources: [Resend pricing](https://resend.com/pricing), [Resend pricing KB](https://resend.com/docs/knowledge-base/what-is-resend-pricing), [Resend API (rate limit)](https://resend.com/docs/api-reference/introduction), [Resend idempotency](https://resend.com/docs/dashboard/emails/idempotency-keys), [Resend events](https://resend.com/docs/dashboard/webhooks/event-types), [Resend suppressions](https://resend.com/docs/dashboard/emails/email-suppressions), [Resend dedicated IPs](https://resend.com/docs/knowledge-base/how-do-dedicated-ips-work), [Postmark pricing](https://postmarkapp.com/pricing), [Postmark Message Streams](https://postmarkapp.com/message-streams), [AWS SES pricing](https://aws.amazon.com/ses/pricing/).

**Corrections to the 2026-09-20 version:**
- SES costs 0.10 USD/1,000, not 0.16.
- Postmark at 50,000/month costs 66–87 USD, not "~15–18 USD base + overage".
- Resend at 500,000/month is not "~90 USD + overage".

### 5.2 Recommendation: **Resend** for the MVP (ADR-0016)

1. **Cost:** 20 USD/month covers 50,000 emails, 3 to 4 times less than Postmark at that volume.
2. **First-class API and webhooks**, with **native idempotency**, which closes the duplicate window (ADR-0010).
3. **Minimal initial friction**: verify the domain with DKIM/SPF/DMARC and send.
4. **Plan:** free to integrate the first product; **Pro** as soon as there is more than one product in production. Any spike exhausts the free plan's 100/day limit and blocks `SECURITY` emails for the rest of the day.

### 5.3 Triggers for a change (measurable)

- **To Postmark** if:
  - delays and bounces attributable to the provider exceed 2 % for 7 days;
  - an account incident interrupts transactional sending;
  - or our own bulk email is built and IP separation per stream in a single provider is wanted.
- **To Amazon SES** when the verified saving exceeds 150 USD/month for 3 months (at 200,000/month: SES ≈ 20 USD against ≥ 90 USD in Resend).
- **Multi-provider failover** only after a real outage with `SECURITY` emails delayed > 30 min.

Thanks to `EmailSender`/`WebhookVerifier` (FR-14), the change is **one new implementation + environment variables**. Careful: if the new provider does not offer idempotency, the timeout duplicate window comes back (NFR-04).

### 5.4 Shared account risk (rev. 2026-10)

All products share **one** Resend team in production. Separating domains does **not** separate:
- the suppression list (team-wide);
- the rate limit (10 rps);
- the IPs (shared on Pro);
- or the account's abuse review.

A product that generates complaints affects everyone. Mitigations: anomaly detection per tenant (FR-34), no bulk email on this account (ADR-0013) and a separate team for staging. Opening one team per product is an open question for the owner.

## 6. Local development mode: Mailpit

- An SMTP server + web interface that captures all email without delivering it.
- Enabled with `MAIL_PROVIDER=smtp` pointing to `mailpit:1025`; the interface is at `http://localhost:8025`.
- It allows testing the real rendered HTML, with no credentials and no risk of sending to real addresses.
- **SMTP is not the production mechanism** (ADR-0003, ratified by ADR-0016): no webhooks, no idempotency, no rate control, no event traceability.

## 7. Template engine: hardened Handlebars.java (rev. 2026-10, ADR-0011)

- **Logic-less:** templates interpolate variables and do simple conditionals and loops, but do not execute code.
- **Mandatory hardening:**
  - a `TemplateLoader` that loads nothing (no partials);
  - the default helpers (`partial`, `block`, `embedded`, `precompile`, `i18n`, `log`, `lookup`…) are removed and only `if`, `unless`, `each`, `with`, `formatDate`, `formatNumber` and `formatMoney` are registered;
  - only `MapValueResolver` (no reflection over Java objects);
  - `{{{ }}}` **and** `{{& }}` are rejected.
- **Context-aware escaping:** HTML in the HTML body; none in the subject and the plain text.
- **HTML linter (jsoup)** on save: no variables in `<script>`/`<style>`/`style=`/`on*`/unquoted attributes; variables in `href`/`src` only with `format: uri`.
- Thymeleaf and FreeMarker are discarded for this use: they evaluate expressions (SSTI).
- **Fallback:** jmustache, if Handlebars.java stops being maintained (criterion in ADR-0011).

## 8. Intake protocol (rev. 2026-10, ADR-0014)

**REST/JSON over HTTPS** with persistent connections and a batch endpoint. Measured: protocol and serialization are ~1.5 % of server time; protobuf is not faster than JSON for this payload (4.81 against 3.43 µs); a TLS connection per request does cost (+2.5 ms on loopback, plus 2 network RTTs), and grouping inserts lowers the database cost about 3 times. gRPC, Connect, binary bodies, HTTP/3, queue-based intake and inbound SMTP are discarded (full table in ADR-0014).

## 9. Object storage and other pieces (rev. 2026-10)

- **Object storage:** none. There are no attachments in the MVP (ADR-0015 rejected); documents and exports are sent as a signed link hosted by the product.
- **Circuit breaker:** our own code, no Resilience4j.
- **Coordination of scheduled tasks:** `pg_try_advisory_xact_lock`, no Quartz, ShedLock or lock table.

## 10. Summary of production dependencies

**Allowed:** PostgreSQL and the email provider. Nothing else.

Any other infrastructure dependency (Redis, RabbitMQ, Kafka, Elasticsearch, S3, a second service) requires an ADR with a real metric that justifies it (NFR-10). It is deliberate: the biggest risk of this project is not falling short on scalability, but becoming expensive to maintain.
