# ADR-0014 — Intake protocol: REST/JSON over persistent connections, plus a batch endpoint

- **Status:** Accepted (owner, 2026-10-05)
- **Date:** 2026-10-05
- **Author:** agent: Claude (documentation audit)
- **Related requirements:** FR-07, FR-35, NFR-01, NFR-02, NFR-17, NFR-18

## Context

The internal API is REST/JSON over HTTPS. We were asked to assess whether another technology reduces sending time or is lighter, **measuring** instead of giving opinions. First-year volume: < 100,000 emails/month; stress scenario: 100 times more (10 M/month).

### Measurement (2026-10-05)

Test bench: a server that reproduces the acceptance path of `POST /v1/emails`:
1. JSON parsing;
2. auth (SHA-256 + constant-time comparison);
3. validation and canonical hash;
4. a PostgreSQL transaction with 2 rate-limit `UPSERT`s and an idempotent `INSERT … ON CONFLICT` on `email_message` with the 6 indexes of `06`;
5. `COMMIT` with `fsync=on` and `synchronous_commit=on`;
6. a `202` response.

Environment details:
- Node 22 + `pg`, PostgreSQL 16, a 2 vCPU Linux container, TLS 1.3 with an ECDSA P-256 certificate, client on the same machine (loopback);
- 1,500 sequential requests per mode, after 300 for warm-up.

The bench's language (Node) is not the service's (Java). What is measured here is the **ratio** between protocol and database, which does not depend on the language.

**Latency budget per component (server, p50):**

| Component | Time | % of server |
|---|---|---|
| JSON parsing of the body (323 bytes) | 10.5 µs | 0.8 % |
| Authentication (SHA-256 + `timingSafeEqual`) | 12.1 µs | 0.9 % |
| Validation + canonical hash | 11.5 µs | 0.8 % |
| **Database** (2 UPSERTs + INSERT + COMMIT) | **1,333 µs** | **96.6 %** |
| Serializing the response | 9.9 µs | 0.7 % |
| **Server total** | **1,380 µs** (p95 2,331 µs) | 100 % |

**Latency seen by the client (loopback):**

| Mode | p50 | p95 | p99 |
|---|---|---|---|
| HTTPS/1.1, new TLS connection per request | 4.36 ms | 6.78 ms | 11.10 ms |
| HTTPS/1.1 keep-alive | **1.84 ms** | 3.01 ms | 5.87 ms |
| HTTP/2, one session | 2.05 ms | 3.84 ms | 6.13 ms |

A new connection costs ~2.5 ms of handshake CPU on loopback, plus 2 real network RTTs (TCP + TLS 1.3). Across regions, that adds up to tens of ms.

**Throughput** (16 concurrent clients, DB pool of 10, everything on 2 shared vCPUs): HTTPS/1.1 keep-alive 634 req/s; multiplexed HTTP/2 569 req/s.

**Body formats** (encoding + decoding the sample payload, 200,000 iterations):

| Format | Bytes | µs per round trip |
|---|---|---|
| JSON | 323 (256 with gzip) | 3.43 |
| Protobuf (protobufjs) | 221 | 4.81 |
| MessagePack (msgpackr) | 272 | 5.44 |
| CBOR (cbor-x) | 273 | 6.48 |

**Batch versus single** (100 inserts):
- 100 transactions: 68–85 ms (0.68–0.85 ms/message);
- 1 transaction: 22.6–35.6 ms (0.23–0.36 ms/message).

**Reading:**
- Protocol and serialization add up to ~1.5 % of server time; everything that is not the database, ~3.4 %.
- A binary format saves 100 bytes and does **not** save CPU compared to native JSON.
- What does move the needle: (1) not opening a TLS connection per request (−58 % of client latency on loopback) and (2) grouping inserts in one transaction (~3× per message).

### Sizing

| Scenario | Average | Assumed peak | Protocol CPU at peak | Maximum saving of going binary |
|---|---|---|---|---|
| Year 1: < 100,000/month | 0.04 req/s | 20 req/s | 20 × ~20 µs ≈ 0.04 % of a core | Negligible |
| ×100: 10 M/month | 3.9 req/s | 200 req/s | ≈ 0.4 % of a core | Negligible; the real limit is the database commit and the provider (10 rps in Resend) |

## Decision

1. **REST/JSON over HTTPS stays** as the only intake interface.
2. **Added:**
   - **persistent connections required in the clients** (HTTP/1.1 keep-alive or HTTP/2; documented in `07` §1 and §12), with client timeouts of 2 s (connect) and 5 s (read);
   - **a batch endpoint** `POST /v1/emails/batch` (FR-35), up to 100 items in one transaction;
   - the server accepts HTTP/2 and gzip compression on batch requests (optional for the client);
   - nothing else.
3. gRPC, Connect, binary bodies, HTTP/3, queue-based intake and SMTP submission are **not** adopted.

### Comparison

| Option | Measured or estimated latency gain | Lightness | Debugging (curl/OpenAPI) | Client compatibility | New infrastructure | Cost for one maintainer | Verdict |
|---|---|---|---|---|---|---|---|
| REST/JSON HTTP/1.1 keep-alive (baseline) | — (1.84 ms p50) | High | Full | Full | None | None | **Stays** |
| REST/JSON HTTP/2 | None with 1 request at a time (2.05 ms); useful to multiplex batches | High | Full (curl `--http2`) | High | None | Low | Accepted by the server, optional for the client |
| REST/JSON HTTP/3 (QUIC) | Only on lossy or mobile networks; not the server-to-server case | Medium | Partial | Low in JVM libraries | A proxy with QUIC | Medium | No |
| gRPC (HTTP/2 + protobuf) | ≈ 0 (protobuf is not faster than JSON here; HTTP/2 already available) | Medium | Worse (grpcurl, no Swagger) | Needs generated stubs in each product | None, but another contract (`.proto`) | High: two contracts | No |
| Connect / gRPC-Web | Same as gRPC | Medium | Better than gRPC (JSON possible) | Medium | None | Medium | No |
| Binary body (protobuf/MessagePack/CBOR) over HTTP | −100 bytes; +1.4–3 µs of CPU | Same | Worse | Needs a library | None | Medium | No |
| Intake through a queue or bus (SQS, NATS, RabbitMQ, Redis Streams, Kafka) | Producer latency drops to the bus's, but latency until sending grows; the synchronous response is lost (id, validation, 422) | Low | Worse | Needs a bus client | **Yes** (violates NFR-10) | High | No; decoupling is achieved with an outbox in the product |
| SMTP submission from the products | Worse (multi-RTT SMTP dialogue) | Low | Worse | High (any library) | An inbound SMTP server | High (MIME parsing, SMTP auth) | No (contradicts ADR-0003) |
| REST batch endpoint | ~3× lower database cost per message for multiple sends | High | Full | Full | None | Low | **Added** |

### What would be lost by leaving REST

- debugging with `curl`;
- Swagger/OpenAPI as the single contract (FR-25);
- compatibility with any client without generating code;
- the same stack as the provider's incoming webhooks (which are HTTP/JSON and will stay so);
- and a second contract to version.

## Consequences

- **Positive:** zero new infrastructure; a single contract; the two improvements with a measured effect (keep-alive and batch) cost little.
- **Negative / accepted trade-offs:** products must configure their HTTP client well (pool and keep-alive); the batch introduces partial responses that the client must process per item.

## Review criterion (measurable threshold)

Reconsider the protocol only with:
- > 200 requests/s sustained on the API for a week;
- acceptance p95 > 150 ms with the database ruled out as the cause (`db_time` < 50 % of the total);
- > 30 M emails/month.

Reconsider queue-based intake if a product needs to keep enqueuing with the email-service down for more than 15 min and its own outbox is not enough.
