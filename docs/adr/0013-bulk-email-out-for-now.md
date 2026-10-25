# ADR-0013 — Bulk/marketing email: not yet; and when it comes, in a separate provider account and stream

- **Status:** Accepted (owner, 2026-10-05)
- **Date:** 2026-10-05
- **Author:** agent: Claude (documentation audit)
- **Related requirements:** FR-34, FR-35, FR-36, NFR-20, NFR-21, FR-40 to FR-54 (`11-bulk-email.md`)

## Context

We were asked to evaluate adding bulk sending: newsletters, notices to all of a product's customers and campaigns. The risk that matters is not code but **sending reputation**: a campaign with many complaints ruining the deliverability of critical emails (account verification, password recovery, payment confirmation).

Facts checked on 2026-10-05:

- **Gmail:** a bulk sender is anyone who sends close to 5,000 messages or more to personal Gmail accounts in 24 h; subdomains of the same primary domain **count together**, and bulk-sender status **does not expire**. Recommended spam rate < 0.1 %; ≥ 0.3 % has consequences. One-click unsubscribe (RFC 8058) in commercial messages, and unsubscribes honoured within 48 h. Since November 2025, temporary and permanent rejections for non-compliant traffic ([Gmail sender guidelines FAQ](https://support.google.com/mail/answer/14229414?hl=en)).
- **Yahoo:** SPF and DKIM, DMARC at least `p=none` passing, spam < 0.3 %, one-click `List-Unsubscribe` and unsubscribes within 2 days ([Yahoo Sender Hub](https://senders.yahooinc.com/best-practices/)).
- **Microsoft (Outlook.com):** since May 2025, senders of > 5,000 emails/day must have aligned SPF, DKIM and DMARC; otherwise they are rejected ([summary](https://powerdmarc.com/microsoft-sender-requirements/)).
- **Resend:**
  - the suppression list belongs to **the whole team** ([suppressions](https://resend.com/docs/dashboard/emails/email-suppressions));
  - plans without a dedicated IP send from shared IPs, and a dedicated IP requires > 3,000 emails/day ([dedicated IPs](https://resend.com/docs/knowledge-base/how-do-dedicated-ips-work));
  - it offers a marketing product (*Broadcasts*) billed by contacts: free up to 1,000 and "Pro Marketing" from 40 USD/month for 5,000 ([pricing KB](https://resend.com/docs/knowledge-base/what-is-resend-pricing)).
- **Postmark** separates transactional and broadcast traffic into different infrastructure and IPs: "transactional and broadcast traffic never intersect in Postmark, including IP ranges" ([Message Streams](https://postmarkapp.com/message-streams)).

## Decision

1. **No bulk or marketing email is added to the email-service for now.** It stays transactional.
2. **Reputation separation happens at the provider, not in code.** Reputation is decided by:
   - the signing domain (DKIM `d=`) and the primary domain;
   - the outgoing IPs;
   - the provider account (its suppression list and its abuse review).

   A separate microservice sending through the **same** Resend account protects nothing; the same service sending through a separate account or stream does.
3. **Interim policy:**
   - until the trigger is met, a product that needs a notice to all its customers sends it with the provider's broadcast tool, in an account **separate** from the transactional one and from a dedicated subdomain (`news.<domain>`), never through `/v1/emails`;
   - the batch endpoint (FR-35) accepts operational `NOTICE` messages (maintenance, terms changes) within the tenant's daily quota, at the lowest priority and with anomaly detection (FR-34): it is not a campaign tool;
   - any notice to more than `dailyQuota` recipients goes through the broadcast path.
4. **Concrete trigger** to build it (any one is enough):
   - **(a)** a product needs to send non-transactional communications to ≥ 1,000 recipients more than once a month for 2 months in a row;
   - **(b)** two or more products need campaigns and the provider's tool does not cover the **consent record with proof** the law requires (`11` §4);
   - **(c)** the cost of the provider's marketing product exceeds 100 USD/month for 3 months.
5. **If it is built**, the mandatory design in `11-bulk-email.md`:
   - a separate provider account or stream;
   - a dedicated subdomain with aligned SPF, DKIM and DMARC;
   - tables and a queue of its own (`campaign`, `campaign_message`);
   - a worker role of its own (`APP_ROLE=bulk-worker`) with its own rate limit and time windows;
   - keys with a scope of their own (`campaigns:write`);
   - consent, double opt-in, `List-Unsubscribe` + `List-Unsubscribe-Post`;
   - prior approval of large campaigns and an automatic pause on complaints.

   It can live in the same repository and artifact as a `campaigns` module with its own role; deploying it as a separate service adds no reputation and does add cost.

## Alternatives considered

| Alternative | For | Against | Why discarded |
|---|---|---|---|
| Adding campaigns to the current service, same provider account | Fast | A campaign with complaints suppresses addresses and exposes the whole account, password recoveries included | Direct risk to critical email |
| A separate marketing service, same provider account | Apparent "separation" | Separates nothing that mailboxes or the provider decide on | Cost with no benefit |
| The provider's marketing product from each product (interim) | Zero code; one-click unsubscribe and contacts solved | Consent and segmentation limited to what it offers; cost per contact | **Adopted as the interim policy** |
| Postmark's Broadcast stream for bulk and Resend for transactional | Separate IPs by design | Two providers to integrate | Preferred candidate if it is built (inside the same `EmailSender`, different configuration) |

## Consequences

- **Positive:** critical email shares no account, suppression or IPs with campaigns; zero development until there is a measured need; the future design is already written.
- **Negative / accepted trade-offs:**
  - today's bulk notices go through an external tool and an extra account;
  - Gmail's "bulk sender" status is counted per primary domain, so a product running campaigns from a subdomain of its primary domain can turn its transactional domain into a bulk sender too. Mitigation: meet the bulk-sender requirements **also** on the transactional side (aligned DMARC, complaints < 0.1 %), which NFR-21 already requires.

## Review criterion

Any of the triggers in point 4, a change in the provider's suppression or account policies, or the entry into force of Law 10946 (expected for June 2027 according to secondary sources, **to be confirmed**) if it requires centralizing the consent record.
