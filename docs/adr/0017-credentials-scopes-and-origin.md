# ADR-0017 — Credentials: API keys with scopes and restrictable origin; rotation of the admin credential

- **Status:** Accepted (owner, 2026-10-05); the "cache ≤ 60 s" point is superseded by ADR-0019
- **Date:** 2026-10-05
- **Author:** agent: Claude (documentation audit)
- **Supersedes:** ADR-0004
- **Related requirements:** FR-01, FR-03, FR-33, FR-34, NFR-07, `08-security.md` §2–4

## Context

ADR-0004 established:
- secrets outside the database;
- API keys `esk_{env}_{prefix}_{secret}` with a SHA-256 hash and constant-time comparison;
- a cache of ≤ 60 s;
- several active keys per tenant.

All of that is **kept**: SHA-256 over a 256-bit secret is correct, and Argon2 would be cost without benefit. The audit found three gaps:

1. **No scopes:** the same sending key could create and publish templates and delete suppressions. A leaked key allowed publishing arbitrary content and sending it from the product's domain.
2. **Imprecise format:** a 4-character prefix can collide; "Base62" in the text versus hexadecimal in the example.
3. **A single admin credential** (`ADMIN_API_KEY`) with no way to rotate it without an outage.

## Decision

1. Points 1, 3, 4 and 5 of ADR-0004 are kept:
   - the service's own credentials only in the environment or a secrets manager;
   - SHA-256 hash and constant-time comparison;
   - cache ≤ 60 s;
   - several active keys per tenant.
2. **Format:** `esk_{env}_{prefix}_{secret}`.
   - `env` ∈ {`live`, `test`};
   - `prefix` = 8 Base62 characters (regenerated if it collides with a `UNIQUE`);
   - `secret` = 43 Base62 characters from a CSPRNG (≥ 256 bits);
   - the hash is stored as a 32-byte `bytea`.
3. **Scopes per key** (FR-33): `emails:send`, `emails:read`, `templates:write` and `suppressions:write`.
   - The key deployed in a product has only `emails:send` + `emails:read`.
   - `templates:write` and `suppressions:write` are issued in separate keys used by the administrator from their machine or from a CI job that publishes templates.
4. **Restrictable origin:** `allowedCidrs` per key (empty = any). The IP is taken from the socket, or from `X-Forwarded-For` only if the previous hop is a trusted proxy (`TRUSTED_PROXIES`).
5. **Immutability:** scopes and CIDRs are fixed at issue time; changing them = issuing another key and revoking the previous one.
6. **Administration:**
   - `ADMIN_API_KEYS` accepts several comma-separated keys, to rotate without an outage (add the new one, deploy, remove the old one);
   - each one has ≥ 32 random bytes;
   - `/admin/v1/**` is reachable only from the private network (a load balancer rule) and is audited.
7. **Abuse detection** (FR-34): automatic pause on anomalous volume, bounces or complaints, as a second line if a key leaks.

## Alternatives considered

| Alternative | For | Against | Why discarded |
|---|---|---|---|
| No scopes (ADR-0004) | Simpler | A leaked key controls templates and suppressions | Blocking risk (audit #3) |
| HMAC signature of every request on top of the key | Prevents reusing a key captured in transit | More complexity in every client; with mandatory TLS it adds little | Kept for phase 2, conditional on exposing the service outside the private network |
| mTLS between services | Very strong | Certificate management out of proportion | Same as in ADR-0004 |
| OAuth2 client credentials | Standard, native scopes | Requires an authorization server | Out of proportion |

## Consequences

- **Positive:** the impact of a leaked key is limited to "sending existing templates, with links to the product's own hosts, until the quota or the automatic pause"; a rotatable admin credential.
- **Negative / accepted trade-offs:** more keys to manage (at least two per tenant); `allowedCidrs` only helps if the hosting provides stable outgoing IPs.

## Review criterion

Exposing the service outside the private network (→ request HMAC), third-party clients (→ OAuth2), or issuing low-entropy credentials (→ Argon2id).
