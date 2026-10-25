# ADR-0006 — Pinned versions and build tool

- **Status:** Superseded by ADR-0009 (2026-10-05)
- **Date:** 2026-09-20
- **Related requirements:** NFR-09, NFR-12, NFR-13

## Context
Spring Boot 3.5 reached the end of its open-source support on 2026-06-30; the supported lines are 4.0 and 4.1. Spring Framework 7 / Spring Boot 4.x support JDK 17–25 and recommend JDK 25 in production. Java 25 is the current LTS. On top of that, much of the public material (and of AI models' knowledge) is about Spring Boot 3.x.

## Decision
| Component | Version |
|---|---|
| Java | **25 (LTS)**, Gradle toolchain pinned |
| Spring Boot | **4.1.x** (latest patch at start) |
| Data access | Spring Data JDBC + Flyway (no JPA/Hibernate) |
| PostgreSQL | **18.x** (`postgres:18-alpine`) |
| Templates | Handlebars.java |
| Build | **Gradle (Kotlin DSL)**, wrapper committed |
| Image | `eclipse-temurin:25-jdk` (build) → `eclipse-temurin:25-jre` (runtime), non-root user |
| Tests | JUnit 5, Testcontainers, WireMock, Awaitility |

Derived rules:
- Patch versions live in `build.gradle.kts` and `docker-compose.yml`; this table pins the major ones.
- **No Spring Boot 3.x APIs are used.** If an example does not compile, look for the equivalent in the documentation of the pinned version; never downgrade the framework to make it compile.
- Before adding a dependency, check that it has a version compatible with Spring Boot 4 and Java 25.
- Upgrading the major version of any row requires a new ADR.

## Consequences
- Positive: a stack with current support and security patches; one set of versions for all my Java projects.
- Negative: fewer public examples for Boot 4 than for 3.x; AI assistants tend to generate 3.x code (mitigation: this explicit rule and an early compilation failure).

## Review criterion
A new Java LTS supported by the Boot line in use, end of support for Spring Boot 4.1, or an essential dependency without support for the pinned stack.

---
> **Status note (2026-10-05):** only the *Status* line of this ADR was changed; its accepted text is not edited. The owner accepted ADR-0009, which supersedes it, on 2026-10-05.
