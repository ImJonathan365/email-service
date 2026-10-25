# ADR-0009 — Ratified stack: Java 25 + Spring Boot 4.1 + PostgreSQL 18, with a measurable justification

- **Status:** Accepted (owner, 2026-10-05)
- **Date:** 2026-10-05
- **Author:** agent: Claude (documentation audit)
- **Supersedes:** ADR-0006
- **Related requirements:** NFR-01, NFR-02, NFR-09, NFR-10, NFR-12, NFR-13, NFR-16, NFR-19

## Context

ADR-0006 pinned the versions, and `04` §2 justified the language almost only by "consistency with my other projects". The choice was to be challenged for **this** service, whose profile is:
- many I/O connections and little CPU;
- low acceptance latency;
- continuous background work;
- a single maintainer;
- < 100,000 emails/month (≈ 0.04 msg/s on average).

### Current versions (checked on 2026-10-05)

| Component | Current version | Support | Source |
|---|---|---|---|
| Java | 25 LTS (Java 26 is not LTS) | LTS | Spring Boot 4.1 supports Java 17–26 ([endoflife.date/spring-boot](https://endoflife.date/spring-boot)) |
| Spring Boot | 4.1.1 (2026-08-20); 4.0.8 | 4.1 OSS until 2027-07-31; 4.0 until 2026-12-31; 3.5 OSS ended on 2026-06-30 | [endoflife.date/spring-boot](https://endoflife.date/spring-boot) |
| PostgreSQL | 18.6 (2026-08-11) | Until 2030-11-14; 19 was not listed as released | [endoflife.date/postgresql](https://endoflife.date/postgresql) |
| Go | 1.27.1 (1.27: 2026-08-19) | The last two versions | [endoflife.date/go](https://endoflife.date/go) |
| Node.js | 24 LTS (24.21.0); 26 becomes LTS in Oct. 2026 | 24: security until 2028-04-30 | [endoflife.date/nodejs](https://endoflife.date/nodejs) |
| Elixir / Erlang OTP | 1.20.4 / OTP 29.1.1 | Active | [endoflife.date/elixir](https://endoflife.date/elixir), [endoflife.date/erlang](https://endoflife.date/erlang) |
| Rust | 1.99.0 (2026-10-01) | Only the latest stable version | [endoflife.date/rust](https://endoflife.date/rust) |

### Our own measurements (2026-10-05, 2 vCPU container, local PostgreSQL 16)

- Full acceptance path (reference server in Node 22 + `pg`): **1.38 ms p50 / 2.33 ms p95**, of which the database accounts for **1.33 ms (96 %)**. The language's work (parsing, auth, validation and serialization) adds up to ~44 µs. **The language is not the bottleneck**; the database is.
- Idle RSS after 3,000 requests:
  - reference Node server (HTTP/2 + `pg`): **72 MB**;
  - minimal JDK server (`HttpServer` + virtual threads, Java 21): **125 MB**. This is the JVM's lower bound.
  - A real Spring Boot app with Actuator, JDBC and Flyway usually sits at 200–300 MB (**to be confirmed**: measured in H1; acceptance criterion: RSS ≤ 350 MB with `-Xmx256m`).
  - Go could not be measured (the module proxy was not reachable from the audit environment); a usual reference is 10–30 MB, **to be confirmed**.
- Every candidate fits in a 1 GB instance. On a 512 MB VPS only Go, Rust and Node fit comfortably: the hosting difference is 0 to ~5 USD/month (**to be confirmed** depending on the chosen hosting).

## Decision

**Java 25 LTS + Spring Boot 4.1.x + Spring Data JDBC + Flyway + PostgreSQL 18.x + Gradle (Kotlin DSL) is kept.** It is a firm recommendation, with a corrected justification:

1. **The I/O profile is solved in the current JVM:** `spring.threads.virtual.enabled=true` (virtual threads) for the API and the worker. Blocking calls to the database and the provider do not use up platform threads.
2. **Performance does not set the candidates apart** at this volume, nor at 100× (measured: 96 % of the time is the database).
3. **The ecosystem covers everything without hand-made code:** validation, Actuator/Micrometer, OpenAPI (springdoc), Flyway, Testcontainers, ArchUnit, an HTTP client with timeouts, JSON Schema and Handlebars.java. Go would need more pieces assembled (validation, OpenAPI and migrations separately).
4. **The deciding criterion is the portfolio cost:** you already run Java 25 + Spring Boot 4.1 in another service (same conventions, same pipeline, same alerts). With four products and a single maintainer, one more stack is the largest long-term source of cost.

### Weighted matrix (1 = worst, 5 = best)

| Criterion (weight) | Java + Spring Boot | Go | Node/TypeScript | Elixir (BEAM) | Rust |
|---|---|---|---|---|---|
| I/O latency and throughput (10) | 4 — virtual threads | 5 — goroutines | 4 — event loop | 5 — BEAM processes | 5 — async/tokio |
| Memory and hosting cost (10) | 2 — ≥ 125 MB measured, ~250 MB typical | 5 | 5 — 72 MB measured | 4 | 5 |
| Maturity of email and queue libraries (15) | 5 — Spring, Flyway, Handlebars.java, Resend SDK | 5 — pgx, River (queue in PG), Resend SDK | 5 — official Resend SDK, pg-boss, graphile-worker | 4 — Swoosh, Oban | 3 — apalis, unofficial SDK |
| Reliability of background work (15) | 4 — our own with SKIP LOCKED, proven | 5 — River | 3 — single thread; a CPU block stalls everything | 5 — Oban + OTP supervision | 2 — young ecosystem |
| Ease of operating for one person (15) | 4 — JRE image, Actuator | 5 — static binary | 4 | 3 — releases, VM configuration | 4 |
| Development speed for you (15) | 4 — familiar | 3 | 3 | 2 | 1 |
| Cost of an extra stack in the portfolio (20) | 5 — already in use | 1 | 2 — TypeScript is already used in the frontends (confirmed by the owner) | 1 | 1 |
| **Weighted total** | **4.15** | **3.90** | **3.55** | **3.20** | **2.70** |

**Sensitivity:** without the portfolio criterion (weights renormalized over 80), the result flips: Go 4.63, Java 3.94, Node 3.94, Elixir 3.75 and Rust 3.13. The decision therefore depends on Java remaining the main stack of your portfolio. Put plainly: **if this were your only service, the recommendation would be Go**.

### Non-language pieces

| Piece | Decision | Reason |
|---|---|---|
| Database | Managed PostgreSQL 18.x (1 vCPU/1 GB) | Queue, RLS, `jsonb` and partial indexes; 18 is supported until 2030 |
| Queue | Stays in PostgreSQL (ADR-0010) | Claiming takes 2.4–4.8 ms with 12,800 queued messages; no dual write |
| Template engine | Hardened Handlebars.java (ADR-0011); jmustache as a fallback | Whitelisted formatting helpers; fallback if it stops being maintained |
| Object storage | **None** | Attachments ≤ 2 MB, transient in PG and purged on sending (ADR-0015); large exports as signed links from the product |
| Observability | Micrometer + Prometheus endpoint; JSON logs to stdout with 30-day retention in the hosting's log destination; OTLP disabled but configurable | No components of our own to operate |
| Circuit breaker and retries | Our own code (~50 lines) | Does not justify adding Resilience4j |
| Image | `eclipse-temurin:25-jre` (runtime) and `25-jdk` (build), non-root user | Same as ADR-0006 |
| Startup | Spring CDS/AOT only if startup exceeds 10 s on the hosting | Not a requirement today |

### Pinned versions

| Component | Version |
|---|---|
| Java | **25 (LTS)**, Gradle toolchain pinned |
| Spring Boot | **4.1.x** (4.1.1 or later at start) |
| Data access | Spring Data JDBC + Flyway (no JPA/Hibernate) |
| PostgreSQL | **18.x** (`postgres:18-alpine` locally) |
| Templates | Handlebars.java 4.5.x (hardened) |
| Build | **Gradle (Kotlin DSL)**, wrapper committed |
| Tests | JUnit 5, Testcontainers, WireMock, Awaitility, ArchUnit |

Rules inherited from ADR-0006 that remain:
- no Spring Boot 3.x APIs are used;
- patch versions live in `build.gradle.kts` and `docker-compose.yml`;
- upgrading a major version requires an ADR.

### Cost of the decision

Almost zero: no new learning or pipeline. The only new cost is configuring virtual threads and the two `DataSource`s (ADR-0008). Switching to Go would cost, as our own estimate **to be confirmed**:
- about 2–3 part-time weeks of learning and scaffolding;
- a new CI pipeline;
- a second set of conventions, alerts and dependencies to watch indefinitely.

## Alternatives considered

See the matrix. One line per discarded option:
- **Go:** best technical fit, worst portfolio fit.
- **Node/TS:** the best email ecosystem, but the most fragile for the long-lived worker.
- **Elixir:** the best queue technology (Oban), but the largest learning jump.
- **Rust:** development cost out of proportion for an I/O service.

## Consequences

- **Positive:** zero transition cost; verifiable justification; explicit criterion for changing.
- **Negative / accepted trade-offs:** more memory (a 1 GB instance instead of 512 MB); slower startup than Go (irrelevant with rolling deployments).

> **Confirmed by the owner (2026-10-05):** all current services are Spring Boot and the frontends use TypeScript; the portfolio assumption holds.

## Review criterion

- Your new services stop being Java (for example, if PipeMend or Colmena are built in Go or TypeScript): the portfolio criterion flips and it is worth re-evaluating with the matrix.
- RSS measured in H1 > 350 MB with `-Xmx256m`, or the app's hosting cost > 15 USD/month because of memory.
- End of OSS support for Spring Boot 4.1 (2027-07-31): upgrade to the next minor version with its ADR.
