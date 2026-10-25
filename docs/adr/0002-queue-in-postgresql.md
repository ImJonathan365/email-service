# ADR-0002 — PostgreSQL as the job queue (no external broker)

- **Status:** Superseded by ADR-0010 (2026-10-05)
- **Date:** 2026-09-20
- **Related requirements:** FR-11, FR-12, FR-13, NFR-03, NFR-06, NFR-10

## Context
Sending must be asynchronous, with retries and idempotency. The industry's "default" option is to add RabbitMQ, Redis or Kafka. That means one more component to install, secure, back up and monitor, and it opens the classic dual-write problem (storing the message in the database and publishing it to the broker without a shared transaction).

## Decision
The `email_message` table **is** the queue. The worker claims work with:

```sql
SELECT id FROM email_message
WHERE status = 'QUEUED' AND next_attempt_at <= now()
ORDER BY next_attempt_at
FOR UPDATE SKIP LOCKED LIMIT :batch;
```

Claimed messages are marked `SENDING` with `lock_expires_at`; a sweep every 60 s returns to `QUEUED` the ones left with an expired lock. Backoff is expressed with `next_attempt_at`.

## Alternatives considered
| Alternative | For | Against | Why discarded |
|---|---|---|---|
| RabbitMQ | Mature queues, priorities, DLQ | Extra component; dual write; opaque queue state | Complexity not justified by the volume |
| Redis (lists/streams) | Fast and simple to start | Configurable and weaker durability; another service to back up | Durability is a hard requirement (NFR-03) |
| Amazon SQS | Managed, no operations | Ties us to AWS; dual write remains; worse local debugging | Kept as a future exit |
| Kafka | Massive scale | Absurd at this scale | Discarded |

## Consequences
- Positive: real atomicity between accepting and enqueuing; state inspectable with SQL; retries and time-based priority with no extra infrastructure; local tests with no dependencies.
- Negative: polling every second instead of push; queue load competes with API queries (mitigated with partial indexes and, if ever needed, a read replica); no native DLQ (its equivalent is `status = 'FAILED'` with `failure_code`).

## Review criterion
> 50 messages/second sustained, a need for strict priorities between message types, or fan-out to several independent consumers. In that case the preferred candidate is SQS (or RabbitMQ for cloud independence), keeping `email_message` as the record of truth and using the outbox pattern.

---
> **Status note (2026-10-05):** only the *Status* line of this ADR was changed; its accepted text is not edited. The owner accepted ADR-0010, which supersedes it, on 2026-10-05.
