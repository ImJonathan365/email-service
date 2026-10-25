# ADR-0016 — Transactional provider: Resend ratified, with verified data and measurable triggers

- **Status:** Accepted (owner, 2026-10-05)
- **Date:** 2026-10-05
- **Author:** agent: Claude (documentation audit)
- **Supersedes:** ADR-0003
- **Related requirements:** FR-14, FR-15, FR-16, NFR-04, NFR-05, NFR-16, NFR-21

## Context

ADR-0003 chose Resend over its API (SMTP only in development) behind `EmailSender`. That decision is **kept**. But the comparison in `04` §5 had wrong data and was missing facts that change the design.

### Data checked on 2026-10-05

| Item | Value | Source |
|---|---|---|
| Resend free | 3,000/month, 100/day, 3 domains, 1 webhook endpoint | [pricing](https://resend.com/pricing) |
| Resend Pro | 20 USD/month for 50,000 (35 USD for 100,000); 10 domains; 5 endpoints; overage 0.90 USD/1,000 | [pricing](https://resend.com/pricing), [KB](https://resend.com/docs/knowledge-base/what-is-resend-pricing) |
| Resend Scale | 90 USD/month for 100,000; up to 2.5 M for 1,150 USD; dedicated IP as a 30 USD/month add-on | Same |
| Resend 500,000/month | **To be confirmed** (the exact tier was not found) | — |
| Resend rate limit | 10 requests/s per team (default) | [API reference](https://resend.com/docs/api-reference/introduction) |
| Resend idempotency | `Idempotency-Key`, ≤ 256 characters, 24 h; `409` if the body changes | [Idempotency keys](https://resend.com/docs/dashboard/emails/idempotency-keys) |
| Resend webhooks | Signed with Svix (`svix-id`, `svix-timestamp`, `svix-signature`), secret per endpoint, raw body | [Verify webhooks](https://resend.com/docs/dashboard/webhooks/verify-webhooks-requests) |
| Resend suppression | Team-wide, automatic on hard bounce and complaint | [Suppressions](https://resend.com/docs/dashboard/emails/email-suppressions) |
| Resend dedicated IP | Requires > 3,000 emails/day | [Dedicated IPs](https://resend.com/docs/knowledge-base/how-do-dedicated-ips-work) |
| Postmark | Free 100/month; 10,000 for 15 / 16.50 / 18 USD (Basic/Pro/Platform); at 50,000: 87 / 68.50 / 66 USD; transactional and broadcast streams with separate IPs | [Postmark pricing](https://postmarkapp.com/pricing), [Message Streams](https://postmarkapp.com/message-streams) |
| Amazon SES | 0.10 USD/1,000 (the previous document said 0.16); 0.12 USD/GB of attachments; up to 200 USD of credits for new accounts | [SES pricing](https://aws.amazon.com/ses/pricing/) |

## Decision

1. **Resend** stays the MVP provider, over its API, behind `EmailSender` + `WebhookVerifier`. SMTP (Mailpit) only in development, failing fast in production (unchanged from ADR-0003, points 2–5).
2. **The verified capabilities are used:** `Idempotency-Key = message.id` (ADR-0010), Svix verification on the raw body, full event mapping (`02` §2.1) and a mirror of the team's suppression list (ADR-0012).
3. **Accounts:** one Resend team for production and **another** for staging (separate limits, suppression and reputation). All products share the production team (accepted risk; an open question about one team per product).
4. **Plan:** free while the first product is being integrated; **Pro** as soon as there is more than one product in production, because any spike exhausts the free plan's 100/day limit and blocks `SECURITY` emails for the rest of the day.
5. **Measurable triggers for a change** (replacing those of ADR-0003):
   - **To Postmark** if any of these happens:
     - (a) `email.delivery_delayed` + `email.bounced` events attributable to the provider (not to bad addresses) exceed 2 % for 7 days;
     - (b) an account incident (suspension or review) interrupts transactional sending;
     - (c) we decide to build our own bulk email (ADR-0013) and want IP separation per stream in a single provider.
   - **To Amazon SES** when the verified monthly saving exceeds 150 USD for 3 months in a row. Reference: at 200,000/month, SES ≈ 20 USD versus ≥ 90 USD in Resend (exact tier to be confirmed). A time budget to operate SNS/EventBridge and our own reputation is also required.
   - **Multi-provider failover:** only after a real provider outage with measured impact (`SECURITY` emails delayed > 30 min).

## Alternatives considered

| Alternative | For | Against | Why discarded |
|---|---|---|---|
| Postmark as the initial one | Transactional/broadcast separation by IP; reference reputation | 100 emails/month free; at 50,000/month it costs 66–87 USD versus 20 USD | 3–4× the cost for the current volume |
| Amazon SES as the initial one | Cheapest at volume | Sandbox, SNS/EventBridge and our own reputation; up to 200 USD in credits, not permanently free | High friction for the MVP |
| A transactional provider's SMTP | Trivial provider change | No API idempotency or reliable identifiers | Same as in ADR-0003 |

## Consequences

- **Positive:** a decision based on verified data; the provider's idempotency closes the duplicate window; the triggers can be measured with the metrics of FR-24.
- **Negative / accepted trade-offs:** one account shared by all products (common suppression, limit and review); dependency on Resend-specific features (idempotency, Svix) encapsulated behind the ports.

## Review criterion

The triggers of point 5, a change in the provider's prices or limits (the pricing page is reviewed every 6 months and before changing plans), or the withdrawal of API idempotency.
