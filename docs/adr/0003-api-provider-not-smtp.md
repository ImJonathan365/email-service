# ADR-0003 — Transactional provider over its API (Resend), SMTP only in development

- **Status:** Superseded by ADR-0016 (2026-10-05)
- **Date:** 2026-09-20
- **Related requirements:** FR-14, FR-15, NFR-05

## Context
Sending over SMTP with the username and password of a mail account (Gmail, cPanel, etc.) means guarding those credentials, gives no bounce or open webhooks, no rate control, and leaves sending reputation out of our control. A centralized service needs exactly the opposite.

## Decision
1. In production, email is sent **through the HTTP API of a transactional provider**. Provider chosen for the MVP: **Resend** (comparison and criteria in `04-technology-stack.md` §5).
2. The whole integration lives behind the `EmailSender` interface, with the implementations `ResendEmailSender`, `SmtpEmailSender` (Mailpit) and `NoopEmailSender` (tests), selected by `MAIL_PROVIDER`.
3. No type, exception or header of the provider's SDK crosses that boundary.
4. SMTP is for local development **only**. With `APP_ENV=production` and `MAIL_PROVIDER=smtp`, the application does not start unless `ALLOW_SMTP_IN_PRODUCTION=true` (a documented escape hatch, not recommended).
5. One active provider per environment; automatic failover is phase 3.

## Alternatives considered
| Alternative | For | Against | Why discarded |
|---|---|---|---|
| A transactional provider's SMTP (same provider, over SMTP) | Switching provider is trivial | Webhooks and message identifiers are lost; worse error handling | Contradicts the traceability goal |
| Postmark as the initial provider | Best deliverability and support in the industry | Free plan of 100 emails/month; higher cost per thousand | Kept as the alternative if critical delivery outweighs cost |
| Amazon SES from the start | Cheapest at volume | Initial sandbox, webhooks through SNS, self-managed reputation | High friction for an MVP; migration planned from ~200,000 emails/month |

## Consequences
- Positive: event webhooks, message identifiers, rate control and better error handling; no credentials of personal mail accounts; switching provider = one new class + environment variables.
- Negative: dependency on a third party and its API (mitigated by the interface); local mode does not reproduce webhooks (accepted: tests simulate them).

## Review criterion
A provider change per the triggers in `04` §5 (critical deliverability → Postmark; > 200,000 emails/month → SES) or a provider outage with real impact (→ evaluate failover, phase 3).

---
> **Status note (2026-10-05):** only the *Status* line of this ADR was changed; its accepted text is not edited. The owner accepted ADR-0016, which supersedes it, on 2026-10-05.
