# ADR-0001 — One deployable service (API + worker) instead of several microservices

- **Status:** Accepted
- **Date:** 2026-09-20
- **Related requirements:** NFR-09, NFR-10, NFR-12

## Context
The email-service has two natural execution modes: serving HTTP requests and processing the send queue. The usual temptation is to split them into two services (or more: templates, webhooks, administration). The project is maintained by one person and the target volume is < 100,000 emails/month.

## Decision
A single deployable artifact containing the API, the worker and the webhooks, with the active role selected by the variable `APP_ROLE=api|worker|all`. A single PostgreSQL database, owned exclusively by the service.

Scaling: N instances with `APP_ROLE=api` behind the load balancer and M instances with `APP_ROLE=worker`. Same image, same base configuration.

## Alternatives considered
| Alternative | For | Against | Why discarded |
|---|---|---|---|
| API and worker as separate services | "Pure" independent deployment and scaling | Two pipelines, two images, two configurations, shared code in a library | `APP_ROLE` gives the same scaling at a fraction of the operating cost |
| Decomposition by domain (templates, sending, events) | "Clean microservice architecture" | Latency, distributed transactions, huge complexity for an 8-table domain | Obvious over-engineering |
| A serverless function per send | No servers to maintain | The worker needs persistent connections and rate control; cold starts and time limits | Poor fit |

## Consequences
- Positive: one pipeline, one artifact, one configuration; trivial local debugging; local transactions.
- Negative: a failure in the API module can affect the `all` process in small deployments (mitigated by separating roles in production); modules must respect internal boundaries through discipline and architecture tests, not through a network boundary.

## Review criterion
The API and the worker needing radically different resource profiles or deployment cadences, or the service no longer being maintained by a small team.
