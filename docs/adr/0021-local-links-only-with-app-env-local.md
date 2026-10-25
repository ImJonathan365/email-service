# ADR-0021 — Links to localhost in URL variables, only with APP_ENV=local

- **Status:** Accepted (owner, 2026-10-14)
- **Date:** 2026-10-14
- **Author:** agent: Claude (local integration)
- **Supersedes:** — (modifies rule 6 of ADR-0011, only locally)
- **Related requirements:** FR-06, FR-07 (AC-07.8), NFR-15

## Context

ADR-0011 (rule 6) and AC-07.8 require every `format: uri` variable to be `https` and to point to a host in `tenant.allowedLinkHosts`. The `allowedLinkHosts` validation also requires a domain with a dot. Locally, a product runs on `http://localhost:3000` or `http://colmena.localhost:5173`: no link in its emails (verify account, reset password) passes the rule, and the only way out would be faking domains or turning the rule off, and that rule is the protection against a leaked key used for phishing.

## Decision

1. **Only with `APP_ENV=local`**, a URL variable is also accepted if:
   - its scheme is `http` or `https`;
   - its host is `localhost` or ends in `.localhost`;
   - it carries no credentials in the URL (`user@`).

   Whatever the tenant has in `allowedLinkHosts` does not matter.
2. With any other `APP_ENV` (`staging`, `production`), the rule is exactly that of ADR-0011, unchanged. Even if a tenant had `localhost` in `allowedLinkHosts`, `http` is still rejected.
3. **There is no switch of its own:** it derives only from `APP_ENV`. No environment variable or tenant field turns it on. With `APP_ENV=production` it is impossible by construction, like SMTP (ADR-0016), which is rejected at startup in production.
4. It applies equally on acceptance (`POST /v1/emails`, batch) and in preview (FR-06): the same `UrlPolicy` class.
5. Everything else that was rejected is still rejected locally:
   - `http` to other hosts;
   - `127.0.0.1` and `[::1]`, because only the name is accepted;
   - `localhost.evil.test`;
   - `javascript:`;
   - `ftp:`.

## Alternatives considered

| Alternative | For | Against | Why discarded |
|---|---|---|---|
| An `ALLOW_LOCAL_LINKS` switch | Explicit | A switch can end up enabled in production by mistake | The owner's requirement: impossible in production |
| Allowing `localhost` in the tenant's `allowedLinkHosts` | No special code | Would still require `https` locally, and opens the door to production tenants with `localhost` | Does not solve `http` and relaxes tenant validation in every environment |
| Development domains with TLS (mkcert) | No exception | Each developer sets up certificates and DNS per product | High cost for an internal service with a single owner |

## Consequences

- **Positive:** a product in development sends emails with links to itself, and they show up in Mailpit without touching the production rule.
- **Negative / accepted trade-offs:**
  - a service deployed with `APP_ENV=local` by mistake would accept links to `localhost`;
  - the damage is bounded, because those links only resolve on the machine of whoever opens the email;
  - and `APP_ENV=local` already changes other visible behaviours (migrations at startup, `esk_test_` keys).
- **Tests:**
  - `UrlPolicyTest` checks both cases: in `local` they are accepted, and in `staging` and `production` the same links are rejected;
  - `EmailAcceptanceTest` checks it end to end (the tests run with `APP_ENV=local`).

## Review criterion

If local links are needed in `staging`, or if a shared development environment with its own domain appears.
