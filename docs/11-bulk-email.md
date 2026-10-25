# 11 — Bulk and marketing email: decision, trigger and mandatory design

> A new document (2026-10-05). Decision in ADR-0013. Every requirement in this document is **Won't (MVP)** and is not implemented until the trigger in §2 is met. They are written so that, when the time comes, nobody improvises the reputation separation or the compliance.

## 1. Recommendation

**Do not add bulk email yet.** If it is added, **not** through the same provider account or the same domain as transactional email.

### 1.1 Why: reputation, not code elegance

What Gmail, Yahoo and Microsoft decide, and what the provider watches, depends on three things:

1. **The signing domain** (DKIM `d=`) and its primary domain. Gmail counts the subdomains of the same primary domain **together** to decide who is a bulk sender (≈ 5,000 messages/day to personal Gmail accounts), and that status **does not expire** ([Gmail FAQ](https://support.google.com/mail/answer/14229414?hl=en)).
2. **The outgoing IPs.** In Resend without a dedicated IP (which requires > 3,000 emails/day), everything goes out through shared IPs ([Resend](https://resend.com/docs/knowledge-base/how-do-dedicated-ips-work)).
3. **The provider account.** In Resend, the suppression list belongs to **the whole team** ([Resend](https://resend.com/docs/dashboard/emails/email-suppressions)), and an abuse review affects the account.

**Damage scenario:** Colmena sends a newsletter to 8,000 contacts imported from a spreadsheet. 0.5 % mark it as spam (40 complaints) and 6 % bounce.
- The 40 addresses get suppressed in the account for **every** product, so those users stop receiving Payverica's password recovery.
- The domain's complaint rate goes over Gmail's 0.3 % and rejections start.
- The provider reviews the account, and the account is the same one that sends Chinamo's receipts.

None of this is avoided by "another microservice" sending through the same account; it is avoided by **another account (or stream), another subdomain and other rules**.

### 1.2 Meanwhile (interim policy)

- Small operational notices (maintenance, terms changes) to a product's users: `POST /v1/emails/batch`, `NOTICE` category, within the tenant's daily quota, with the lowest priority and anomaly detection (FR-34, FR-35).
- Any send larger than the tenant's daily quota, or of a commercial nature: with the provider's broadcast product (Resend Broadcasts or another) in a **separate account** and from `news.<product-domain>`, managed by the product itself. Cost reference: Resend Marketing free up to 1,000 contacts and from 40 USD/month for 5,000 ([Resend pricing KB](https://resend.com/docs/knowledge-base/what-is-resend-pricing)).

## 2. Concrete trigger to build it

Any one is enough:

| ID | Trigger | How it is measured |
|---|---|---|
| T-1 | A product needs to send non-transactional communications to ≥ 1,000 recipients more than once a month, for 2 months in a row | Records of the broadcast product |
| T-2 | ≥ 2 products need campaigns and the provider's tool offers no consent record with proof (§4.3) | Compliance review |
| T-3 | The provider's marketing product exceeds 100 USD/month for 3 months | Billing |

When it triggers: a new ADR with the observed metric, and the design in this document as the starting point.

## 3. Mandatory separation (if it is built)

| Dimension | Transactional (current) | Bulk (new) |
|---|---|---|
| Provider account | Production Resend team | **Another** account: a separate Resend team, or Postmark with a Broadcast stream, which uses separate IPs by design ([Postmark](https://postmarkapp.com/message-streams)) |
| Sending domain | `notify.<domain>` or the primary domain | `news.<domain>` (a dedicated subdomain), with aligned SPF, DKIM and DMARC. Careful: it counts towards Gmail's threshold for the primary domain, so the transactional side must also meet the bulk-sender requirements (NFR-21) |
| IP policy | The provider's shared ones | Shared ones from the broadcast pool; dedicated only with > 3,000/day sustained and a warm-up plan |
| Queue | `email_message` | Tables of its own `campaign`, `campaign_recipient`, `campaign_message`; they never share rows or a queue index |
| Worker | `APP_ROLE=worker` | `APP_ROLE=bulk-worker`, with its own limiter and circuit breaker |
| Rate limit | 8 rps per instance (≤ 80 % of the account's limit) | Its account's own; by default 2 rps and a time window of 08:00–20:00 in the tenant's time zone |
| Credentials | Scopes `emails:*`, `templates:write`, `suppressions:write` | A new `campaigns:write` scope in separate keys; never in the product's production key |
| Suppression | Global (bounce, complaint) + tenant (manual) | Also: unsubscribes per list and a global "no marketing" per tenant; campaign complaints suppress **marketing**, not transactional |
| Templates | Categories `SECURITY`, `TRANSACTIONAL`, `NOTICE` | A `MARKETING` category, sendable only through the campaign flow; never through `/v1/emails` |

Code: it can live in the same repository and artifact as a `campaigns` module with its own deployment role (ADR-0001 still holds). Deploying it as a separate service adds no reputation and adds cost.

## 4. Requirements (all **Won't (MVP)**)

| ID | Requirement |
|---|---|
| FR-40 | Separate sending infrastructure |
| FR-41 | Lists and contacts |
| FR-42 | Segments |
| FR-43 | Consent record with proof |
| FR-44 | Double opt-in |
| FR-45 | `List-Unsubscribe` and `List-Unsubscribe-Post` (one click) |
| FR-46 | Unsubscribe page and endpoint |
| FR-47 | Per-list suppression versus global suppression |
| FR-48 | Campaigns: draft, approval, scheduling and cancellation |
| FR-49 | Throttling and sending windows |
| FR-50 | A/B tests |
| FR-51 | Metrics per campaign |
| FR-52 | Limits per plan and per tenant |
| FR-53 | Commercial identification and mandatory content |
| FR-54 | Campaign-specific anti-abuse |

### FR-40 — Separate sending infrastructure
- **AC-40.1** — *Given* `APP_ROLE=bulk-worker`, *when* the configured provider key or account matches the transactional one (`RESEND_API_KEY`), *then* the application does not start.
- **AC-40.2** — *Given* a campaign sending domain, *when* it is registered, *then* it must differ from all of the tenant's transactional `allowedFromDomains` and have SPF, DKIM and DMARC verified (checked on registration with a DNS query).

### FR-41 — Lists and contacts
- **AC-41.1** — A tenant creates lists (`audience`) with a name and a purpose; a contact belongs to 0..N lists, with `email`, optional attributes and a state per list (`SUBSCRIBED`, `PENDING`, `UNSUBSCRIBED`, `CLEANED`).
- **AC-41.2** — Bulk import only accepts contacts with evidence of consent (FR-43); rows without evidence are rejected with a per-row report.
- **AC-41.3** — Isolation through RLS, as in the rest of the service.

### FR-42 — Segments
- **AC-42.1** — A segment is a saved filter on attributes and activity (e.g. "opened in 90 days"), evaluated when the send is created and frozen in `campaign_recipient`.
- **AC-42.2** — A segment never includes `UNSUBSCRIBED`, `CLEANED` or suppressed contacts, nor those with the global "no marketing" mark.

### FR-43 — Consent record with proof
- **AC-43.1** — Every sign-up stores: who (contact), when (timestamp), how (`source`: form, import, API), the exact text shown or its version, IP and user agent if it is web, and the form's identifier; immutable (inserts only).
- **AC-43.2** — *Given* a contact, *when* its consent is queried, *then* the full chain of events (sign-up, confirmation, unsubscribe) is obtained in order.
- **AC-43.3** — Without a current consent record for that list, the contact does not receive the campaign.

### FR-44 — Double opt-in
- **AC-44.1** — Form sign-ups stay `PENDING` and receive a confirmation email (sent through the **transactional** flow, `SECURITY` category); they only become `SUBSCRIBED` when they confirm.
- **AC-44.2** — `PENDING` contacts not confirmed within 7 days are deleted.
- **AC-44.3** — Configurable per list, but **mandatory** for web acquisition lists (a conservative decision regarding art. 5 of Law 8968, which requires express consent; to be confirmed with legal counsel).

### FR-45 — `List-Unsubscribe` and `List-Unsubscribe-Post` (one click)
- **AC-45.1** — Every campaign message carries `List-Unsubscribe: <https://…/u/{token}>, <mailto:…>` and `List-Unsubscribe-Post: List-Unsubscribe=One-Click` (RFC 8058), and both headers are covered by the DKIM signature.
- **AC-45.2** — *Given* a `POST` to the unsubscribe URL with the body `List-Unsubscribe=One-Click`, *then* the unsubscribe is applied without further interaction and responds `200`; the token is single-purpose, unguessable and does not expire before 60 days (CAN-SPAM requires the mechanism to work for at least 30).

### FR-46 — Unsubscribe page and endpoint
- **AC-46.1** — The visible link in the body leads to a page that confirms the unsubscribe **without asking for a login** and offers "unsubscribe from all of {product}'s marketing".
- **AC-46.2** — The unsubscribe takes effect **immediately** in the system (Law 10946 art. 29 requires honouring it immediately according to secondary sources; Gmail requires 48 h; Yahoo, 2 days; CAN-SPAM, 10 business days); a verifiable goal: no send to that address for that list after the `200`.

### FR-47 — Per-list suppression versus global suppression
- **AC-47.1** — Unsubscribing from a list → `UNSUBSCRIBED` in that list only.
- **AC-47.2** — "Unsubscribe from everything" → a `MARKETING_OPT_OUT` mark per tenant: it receives no campaign from that tenant, but **does** receive transactional email.
- **AC-47.3** — A complaint (feedback loop) on a campaign message → the tenant's `MARKETING_OPT_OUT` + suppression in the marketing account; it does not touch the transactional account.
- **AC-47.4** — A hard bounce in a campaign → `CLEANED` in all of the tenant's lists; the global transactional suppression is fed only by hard bounces (they are a fact about the address).

### FR-48 — Campaigns
- **AC-48.1** — A campaign has a `MARKETING` template (pinned version), a list or segment, a campaign sender, `scheduledAt` and a state (`DRAFT` → `PENDING_APPROVAL` → `APPROVED` → `SENDING` → `SENT` \| `CANCELED` \| `PAUSED`).
- **AC-48.2** — Before approving, it requires: a test send to an internal address and the automatic check of FR-53.
- **AC-48.3** — A campaign in `SENDING` can be paused and cancelled; recipients not sent yet are not sent.

### FR-49 — Throttling and sending windows
- **AC-49.1** — A configurable pace per campaign (default 2 msg/s), never above the marketing account's limit.
- **AC-49.2** — A time window per tenant (default 08:00–20:00 in `tenant.timezone`); outside it, sends are rescheduled.
- **AC-49.3** — A campaign to > 5,000 recipients is spread over at least 2 days for new domains (warm-up).

### FR-50 — A/B tests
- **AC-50.1** — **Won't** even in the first version of campaigns: with < 10,000 recipients per campaign the significance is low; reconsidered with real volume.

### FR-51 — Metrics per campaign
- **AC-51.1** — Per campaign: recipients, sent, delivered, bounces (hard and temporary), complaints, unsubscribes and, if tracking is enabled, unique opens and clicks.
- **AC-51.2** — An alert if a campaign's complaint rate exceeds 0.1 %, or its bounce rate 2 %, in the first 1,000 deliveries.

### FR-52 — Limits per plan and per tenant
- **AC-52.1** — Per tenant: maximum contacts, campaigns per month and marketing sends per month; exceeding them → `429`/`422` with its code.
- **AC-52.2** — Marketing limits are independent of the transactional ones (FR-21).

### FR-53 — Commercial identification and mandatory content
- **AC-53.1** — The `MARKETING` template requires, verified by the linter: the sender's name, a valid physical postal address (CAN-SPAM) and a visible unsubscribe link.
- **AC-53.2** — If the campaign is advertising, the subject or the start of the message includes the identification "Publicidad" ("advertising", or "Oferta"/"Invitación" as the case may be), per art. 9 of Law 10946 according to secondary sources (**to be confirmed** with the official text; expected to take effect 12 months after its publication on 2026-06-24).
- **AC-53.3** — Non-deceptive subjects and accurate headers (CAN-SPAM): the `From` corresponds to the tenant and the `Reply-To` is attended.

### FR-54 — Campaign-specific anti-abuse
- **AC-54.1** — Campaigns to > 1,000 recipients (configurable) require an administrator's approval (`PENDING_APPROVAL`), which is audited.
- **AC-54.2** — Automatic pause of the campaign if, after 500 deliveries, complaints > 0.3 % or bounces > 5 %.
- **AC-54.3** — An imported list whose first send exceeds 5 % bounces is marked as a "suspicious list" and blocks new campaigns to it until reviewed.
- **AC-54.4** — A leaked key with `campaigns:write` cannot send without approval above the threshold of AC-54.1.

## 5. Compliance (checked on 2026-10-05; I am not a lawyer)

> **(rev. 2026-10, owner):** every service is for Costa Rica only. CAN-SPAM and GDPR **do not apply** as long as that does not change; they are kept in the table in case a product starts serving users in the US or the EU. Law 8968, Law 10946 (once in force) and Gmail's, Yahoo's and Microsoft's rules do apply.

| Law / policy | Scope | Key requirements for campaigns | Source |
|---|---|---|---|
| **CAN-SPAM** (US) | Commercial email to recipients in the US | Non-deceptive headers; non-deceptive subject; identifying the message as an ad; a physical postal address; an unsubscribe mechanism that works ≥ 30 days after sending; unsubscribes honoured within ≤ 10 business days; liability for third parties sending on your behalf; up to 53,088 USD per violating email. Transactional or relationship messages are exempt from most requirements, but not from the truthful-header ones | [FTC](https://www.ftc.gov/business-guidance/resources/can-spam-act-compliance-guide-business) |
| **GDPR** (EU) | Only if a product offers goods or services to EU residents or monitors them (art. 3.2) | A legal basis (consent for direct email marketing, except for the exceptions in each country's ePrivacy rules), proof of consent, withdrawal as easy as giving it and the right to object. **To be confirmed whether it applies** to any product | — (not checked in this audit; apply only if there are users in the EU) |
| **Law 8968** (Costa Rica) | Personal data of residents of Costa Rica | Express, informed, documented consent (art. 5); international transfer only with authorization (art. 14); ARCO within 5 business days; registration with PRODHAB if the database is "distributed, disseminated or sold" (a shared marketing list could qualify: **to be confirmed**) | [2026 summary](https://www.recordinglaw.com/world-laws/world-data-privacy-laws/costa-rica-data-privacy-laws/) |
| **Law 10946** (Costa Rica; governance of digital services and e-commerce) | Merchants that send commercial communications | Art. 29: no unsolicited or non-consented commercial communications except within a contractual or pre-contractual relationship; an easy unsubscribe procedure, honoured immediately. Art. 9: a communication identifiable as commercial, and the indication "advertising, invitation or offer" at the start of the message. Published on 2026-06-24; in force 12 months later. **To be confirmed with the official text** | [Bufete de Costa Rica](https://bufetedecostarica.com/gobernanza-de-los-servicios-digitales-y-el-comercio-electronico-en-costa-rica-10946/) |
| **Gmail** | Senders to personal Gmail accounts | Everyone: SPF or DKIM, PTR, TLS, spam < 0.3 %. Bulk senders (≈ 5,000/day; subdomains count with the primary domain; the status does not expire): SPF **and** DKIM, aligned DMARC, RFC 8058 one-click unsubscribe in commercial messages and unsubscribes honoured within 48 h; recommended spam < 0.1 %; stricter enforcement since November 2025 | [Gmail FAQ](https://support.google.com/mail/answer/14229414?hl=en) |
| **Yahoo** | Bulk senders | SPF and DKIM, DMARC at least `p=none` passing, spam < 0.3 %, one-click `List-Unsubscribe` and unsubscribes honoured within 2 days | [Yahoo Sender Hub](https://senders.yahooinc.com/best-practices/) |
| **Microsoft (Outlook.com)** | > 5,000 emails/day | Since May 2025: aligned SPF, DKIM and DMARC (at least `p=none`); otherwise, rejection | [PowerDMARC summary](https://powerdmarc.com/microsoft-sender-requirements/) |

## 6. Anti-abuse: what already applies today and what bulk would add

Today, on the transactional side (MVP), against a leaked key or future third-party access:

| Control | Where |
|---|---|
| Scopes: a product's key cannot publish templates | FR-33 |
| CIDR per key | FR-33 |
| Variable URLs limited to the tenant's hosts | AC-07.8 |
| A daily quota set to ~3× the expected volume | FR-21 |
| Automatic pause on anomalous volume (3× the hourly p95 of 14 days), bounces > 4 % or complaints > 0.1 % | FR-34 |
| Global suppression of bounces and complaints | ADR-0012 |
| Auditing of keys, templates and pauses | FR-22 |

Bulk would add prior approval of large campaigns, a pause per campaign, suspicious-list detection and a separate account (FR-40, FR-54).

If access is ever given to **third parties** (another project according to the anti-roadmap): domain verification per tenant, quotas per plan, manual review of the first send, HMAC request signing (phase 2) and provider accounts per client. That is a different product; it needs its own analysis.
