# ADR-0012 — Global suppression for bounces and complaints, per tenant for manual unsubscribes

- **Status:** Accepted (owner, 2026-10-05)
- **Date:** 2026-10-05
- **Author:** agent: Claude (documentation audit)
- **Related requirements:** FR-10, FR-17, FR-18, FR-29, NFR-21; complements ADR-0008 (it supersedes no ADR: the suppression semantics only lived in AC-10.2)

## Context

AC-10.2 said: "Suppression is **per tenant**, not global". The checks of 2026-10-05 show that this premise is false with the chosen provider:

- In Resend the suppression list belongs to **the whole team**: "Any address added to the suppression list will be skipped across all your domains and subdomains". It is fed automatically with hard bounces and complaints, and an address removed without fixing the cause gets suppressed again on its own ([Resend — Email suppressions](https://resend.com/docs/dashboard/emails/email-suppressions)).
- Resend emits `email.suppressed`, `suppression.added` and `suppression.removed` ([event types](https://resend.com/docs/dashboard/webhooks/event-types)); none were mapped, so a message skipped by the provider stayed `SENT` forever.

Also:
- a hard bounce is a fact about the **address** (it does not exist), not about the tenant;
- `cc` and `bcc` were not checked;
- a tenant could delete a hard bounce and damage the shared reputation again.

## Decision

1. **Scope by reason:**

| Reason | Scope | Source |
|---|---|---|
| `HARD_BOUNCE` | GLOBAL | `email.bounced` |
| `COMPLAINT` | GLOBAL | `email.complained` |
| `PROVIDER` | GLOBAL | `email.suppressed`, `suppression.added` (a mirror of the provider's list) |
| `MANUAL` | TENANT | `POST /v1/suppressions` (e.g. "the user asked not to receive notices from this product") |

2. **Mirror:** `suppression.removed` deletes the matching `PROVIDER` row; `HARD_BOUNCE`/`COMPLAINT` rows are deleted only by an administrator (`DELETE /admin/v1/suppressions/{email}`), who must also remove it in the provider's dashboard.
3. **Check** on `to`, `cc` and `bcc`, on acceptance and again just before sending. A suppressed `to` produces `FAILED/SUPPRESSED`; suppressed `cc`/`bcc` are dropped and reported in `droppedRecipients`.
4. **Privacy:** lookup key `email_hash = HMAC-SHA256(SUPPRESSION_HASH_KEY, normalized email)`; the clear-text address is optional and is nulled by a data-subject erasure (FR-29), so we still never write to them without keeping their address. Tenants see global suppressions masked and without the source tenant.
5. **Critical emails to someone who complained:** there is no exception. Even if we wanted to allow a password recovery to someone who marked another email as spam, the provider would skip it anyway (team list). Documented as known behaviour; the product must offer another recovery channel if it is critical.

## Alternatives considered

| Alternative | For | Against | Why discarded |
|---|---|---|---|
| Everything per tenant (previous decision) | Conceptual isolation between products | False with Resend; skipped messages stay `SENT` | Contradicts the provider's reality |
| Everything global | The simplest | A product's manual unsubscribe would block the others | `MANUAL` must stay per tenant |
| One Resend account (team) per product, with real per-tenant suppression | Real isolation of complaints and reputation | 4 accounts, 4 verified domains per account, 4 provider keys (configuration becomes per tenant) and, with Pro, up to 80 USD/month | An open question for the owner; the conservative decision (global) works with one account or several |

## Consequences

- **Positive:** the model reflects what the provider already does; no messages left hanging in `SENT`; the shared reputation is protected.
- **Negative / accepted trade-offs:** a complaint against one product blocks every product's emails to that address (imposed by the provider); one more secret is needed (`SUPPRESSION_HASH_KEY`), which cannot be rotated without recomputing the hashes, and that needs the clear-text address.

## Review criterion

If one provider account per product is adopted, `HARD_BOUNCE` stays global and `COMPLAINT`/`PROVIDER` become per tenant. Also revisited if the provider offers per-domain suppression lists.
