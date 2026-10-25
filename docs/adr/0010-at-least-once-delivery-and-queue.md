# ADR-0010 — Queue in PostgreSQL with fencing, priority and idempotency towards the provider

- **Status:** Accepted (owner, 2026-10-05)
- **Date:** 2026-10-05
- **Author:** agent: Claude (documentation audit)
- **Supersedes:** ADR-0002
- **Related requirements:** FR-11, FR-12, FR-13, FR-36, NFR-03, NFR-04, NFR-06, NFR-19, NFR-21

## Context

ADR-0002 decided that the `email_message` table is the queue (`FOR UPDATE SKIP LOCKED`) and that a sweep returns messages with an expired lock to `QUEUED`. The underlying decision is right and is **kept**. Measured on 2026-10-05: claiming with `LIMIT 4` over 12,800 queued messages takes between 2.4 and 4.8 ms on a local PostgreSQL 16; a full acceptance transaction, 1.38 ms p50.

But the audit found five defects that made the promise of NFR-04 ("exactly once") false:

1. Resend offers native idempotency (`Idempotency-Key`, 24 h, `409` if the body changes; [documentation](https://resend.com/docs/dashboard/emails/idempotency-keys)) and the design did not use it ("when it offers it").
2. The real duplicate (the provider accepts, the response is lost) leaves no `provider_message_id`, so the safeguard "if there is a `provider_message_id`, do not resend" does not cover it.
3. The sweep did not increment `attempts`: a message that crashes the worker is retried forever.
4. There was no fencing: a slow worker could close as `SENT` a message the sweep had already reassigned to another, and both sent it.
5. The batch of 25 shared a single lock expiry but was processed 4 at a time; and calling the provider inside the claim transaction was not forbidden.

There was also no priority: a mass notice (`NOTICE`) could delay a password recovery. The review criterion "> 50 msg/s" was equivalent to ~130 M emails/month and would never trigger.

## Decision

1. **The `email_message` table is still the queue.** No broker.
2. **A short claim with no network:**
   - a single statement (CTE `SELECT … FOR UPDATE SKIP LOCKED` + `UPDATE … RETURNING`) in autocommit;
   - **no** HTTP call with row locks held;
   - at most as many messages are claimed as the worker has free concurrency slots, instead of a fixed batch.
3. **Attempts at claim time:** `attempts = attempts + 1` and `first_attempt_at = coalesce(first_attempt_at, now())` in the claim itself. The sweep returns messages to `QUEUED` (or to `FAILED`/`MAX_ATTEMPTS_EXCEEDED` if they ran out) and records `LOCK_EXPIRED` in `attempt_log`.
4. **Fencing token:**
   - each claim generates a `lock_token` (uuid);
   - every close (`SENT`, `FAILED`, back to `QUEUED`) is done with `WHERE id = :id AND status = 'SENDING' AND lock_token = :token`;
   - if it affects 0 rows, the worker does nothing but record the metric `email_lost_lock_total`.
5. **Idempotency towards the provider:**
   - `Idempotency-Key = email_message.id` on every attempt;
   - the body sent is deterministic (pinned version, fixed variables, ordered attachments, no "current date" helpers);
   - a `409` from the provider for a reused key is classified as permanent (`PROVIDER_IDEMPOTENCY_CONFLICT`) and raises an alert.
6. **Window:**
   - all attempts of a message happen within < 23 h of `first_attempt_at`;
   - the app does not start if the backoff + lock configuration can exceed that window;
   - past the window, `FAILED`/`RETRY_WINDOW_EXCEEDED`.
7. **Lock per message:** `LOCK_TIMEOUT_SECONDS` = 60 (> 3 s of connection + 10 s of reading + margin).
8. **Priority:**
   - a `priority` column (0 `SECURITY`, 1 `TRANSACTIONAL`, 2 `NOTICE`) inherited from the template's category;
   - a partial index `(priority, next_attempt_at) WHERE status = 'QUEUED'`;
   - the claim orders by `priority, next_attempt_at`.
9. **Held tenants:** the claim excludes `SUSPENDED` or paused tenants (join with `tenant`); their messages stay `QUEUED`.
10. **Limit towards the provider:**
    - an in-memory rate limiter per worker instance, `WORKER_PROVIDER_RPS` (default 8);
    - the sum over all instances ≤ 80 % of the account limit (Resend: 10 rps per team by default);
    - with 1 worker (the default) no coordination is needed.
11. **A hand-made circuit breaker:** 5 consecutive transient failures open it for 60 s, after which a single send is tried. While it is open, no messages are claimed.
12. **Scheduled tasks** (sweep, purge, metrics, anomalies) coordinated across instances with `pg_try_advisory_xact_lock`; the `scheduled_lock` table is removed.
13. **Declared semantics:** at least once, with deduplication at the provider within 24 h (NFR-04).

## Alternatives considered

| Alternative | For | Against | Why discarded |
|---|---|---|---|
| Keep ADR-0002 as is | Nothing to change | Possible duplicates, infinite loops and no priority, all demonstrable | Breaks NFR-04 |
| Calling the provider inside the claim transaction | The lock "protects" the send | DB locks and connections held for up to 10 s; a timeout aborts the transaction and the email may still have gone out | Worse in every way |
| A queue library (JobRunr, db-scheduler) | Retries and sweeps already done | A second job table separate from the send record (dual write comes back) or bending the domain to the library; one more dependency | ~300 lines of our own, explicit and tested, cost less than coupling to it |
| A broker (SQS, RabbitMQ) | Native priorities and DLQ | Dual write and another component; the volume does not justify it | Same as in ADR-0002 |
| LISTEN/NOTIFY to wake the worker | Latency < 1 s | More code and edge cases with the pool | NFR-19 is met with 1 s polling; reconsidered if not |

## Consequences

- **Positive:**
  - messages are neither lost nor duplicated within the provider's 24 h idempotency;
  - "poison" messages end in `FAILED`;
  - priority protects critical emails;
  - the queue stays inspectable with SQL.
- **Negative / accepted trade-offs:**
  - we depend on the provider offering idempotency (switching to one without it brings back the timeout duplicate window, and a warning is logged at startup);
  - rendering must be deterministic (a restriction on helpers);
  - with N workers the rate limit must be split by hand.

## Review criterion

Any of these signals:
- p95 of the claim query > 50 ms for 3 days;
- queue age > 60 s with a healthy provider for 3 days;
- volume > 3 M emails/month;
- a need for fan-out to several independent consumers;
- `email_lost_lock_total` or `PROVIDER_IDEMPOTENCY_CONFLICT` > 0 recurrently.

Preferred candidate in that case: SQS with the outbox pattern, keeping `email_message` as the record of truth.
