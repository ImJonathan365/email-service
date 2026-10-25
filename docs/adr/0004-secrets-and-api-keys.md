# ADR-0004 — Secrets outside the database; API keys with prefix and hash

- **Status:** Superseded by ADR-0017 (2026-10-05)
- **Date:** 2026-09-20
- **Related requirements:** FR-01, FR-03, NFR-07, `08-security.md`

## Context
The service handles two kinds of credentials: its own, towards the outside (email provider, database, webhook signing), and the ones it issues so that its internal clients can authenticate. Storing any of them in clear text in the database turns a data dump into a takeover of the sending domain.

## Decision
1. The service's own credentials live **only** in environment variables injected by the orchestrator or a secrets manager. Never in the database, the repository, the image or the logs. Without them, the application does not start in `production`.
2. Tenant API keys have the format `esk_{env}_{prefix}_{secret}`, with a `secret` of ≥ 32 cryptographically random bytes.
3. The database stores `key_prefix` (in clear, to find the row) and `SHA-256(secret)`. The full secret is shown **only once**, when it is issued.
4. Verification compares hashes in constant time. Validated keys are cached for ≤ 60 s.
5. A tenant may have several active keys, to rotate without downtime.

## Alternatives considered
| Alternative | For | Against | Why discarded |
|---|---|---|---|
| Argon2id / bcrypt for API keys | Standard for passwords | High, unnecessary cost per request: the secret has 256 bits of entropy, it is not a human password | SHA-256 is enough here; to be revisited if low-entropy keys are ever issued |
| JWTs signed per tenant | No database lookup | Complicated revocation; more complex key rotation | Worse fit for long-lived keys |
| mTLS between services | Very strong | Certificate management out of proportion for this size | Discarded |
| Storing the provider key encrypted in the database | "Everything in one place" | The encryption key must be guarded anyway; widens the impact of a dump | Contradicts the original requirement |

## Consequences
- Positive: a database dump does not allow sending email or impersonating a tenant; simple, audited rotation; a leaked key is identified by its prefix.
- Negative: if a key's secret is lost, another one must be issued (by design); revocation takes up to 60 s because of the cache.

## Review criterion
Issuing low-entropy credentials, exposing the service outside the private network (→ add HMAC request signing) or compliance requirements that demand another scheme.

---
> **Status note (2026-10-05):** only the *Status* line of this ADR was changed; its accepted text is not edited. The owner accepted ADR-0017, which supersedes it, on 2026-10-05.
