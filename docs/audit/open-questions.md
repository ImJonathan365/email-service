# Open questions for the owner — audit of 2026-10-05

**Status:** answered by the owner on 2026-10-05. Only the legal points (which do **not** block development) and the hosting decisions, made once the MVP exists, remain pending.

| # | Question | The owner's answer | What changed | Status |
|---|---|---|---|---|
| 1 | Do you accept ADR-0008 to 0017? | Accepts | Accepted: 0008–0014, 0016 and 0017. 0015 is rejected because of answer 2 | Closed |
| 2 | Do Chinamo/Colmena send receipts with XML + PDF? | They do not | FR-30 → Won't; ADR-0015 Rejected; `email_attachment` is not created; exports go as a signed link | Closed |
| 3 | One Resend account per product or a shared one? | Accepts the proposal | One shared account in production and another for staging; global suppression (ADR-0012) | Closed |
| 4 | `sendAt` in the MVP? | Yes | FR-31 → Should (milestone H8) | Closed |
| 5 | Volume and quota per tenant | Accepts | `dailyQuota` = 1,000 by default, adjustable per tenant | Closed |
| 6 | Stable outgoing IPs for `allowedCidrs`? Hosting? | Defined with the MVP; accepts the proposal | `allowedCidrs` empty by default; an encrypted private network or TLS required | Pending (does not block) |
| 7 | Does the managed PostgreSQL allow `BYPASSRLS`? | Agrees with the alternative | If not: a `TO email_system USING (true)` policy. Check when choosing the hosting | Pending (does not block; locally there is no problem) |
| 8 | Users in the EU or the US? | Costa Rica only | GDPR and CAN-SPAM do not apply (NFR-20, `11` §5) | Closed |
| 9 | Does each product's privacy notice cover the transfer to the provider in the US? | Does not understand it; pending if it does not block | Explanation below. Marked as a **go-live** requirement, not a development one (`08` §10) | Pending (does not block development) |
| 10 | Will the new services still be Java? | Yes, they are all Spring Boot | ADR-0009 is confirmed | Closed |
| 11 | TypeScript in any product? | Yes, in the frontends | Node's portfolio score confirmed (ADR-0009) | Closed |
| 12 | Does Colmena process data of its customers' customers? | Yes (products, debts) | Colmena = processor; email-service and Resend = sub-processors; `fromName` per business (AC-09.5); debt amounts `x-sensitive`; collection regulation to be confirmed with legal counsel (NFR-20) | Closed (with a pending legal point that does not block) |
| 13 | Alternative channel for critical emails to someone who complained? | Better to a specific address | The response carries `suppressionReason`; the product retries to another registered address of the user (AC-10.1, `07` §12) | Closed |
| 14 | Language | Everything in Spanish, but with English implemented and configurable | Template versions per locale (`es-CR`, `en`), `locale` per message falling back to Spanish (FR-37, ADR-0018) | Closed |
| 15 | Gmail clipping and the provider's retention | Accepts | No changes (explanation below) | Closed |
| 16 | Phase 0 timeline | No problem | No changes | Closed |

## Clarifications

**Question 9, in simple words.** When a product sends an email through the email-service, the address and the content of the email end up on Resend's servers, in the US. Law 8968 treats that as an "international transfer" of personal data and asks that the person has authorized it. In practice it is enough for each product's privacy policy or terms (the ones the user accepts when signing up) to say something like: "we use external providers, some of them outside Costa Rica, to send you emails; we share your email address and the content of the message with them only for that purpose". In Colmena, since it sends emails to its businesses' customers, Colmena's terms with each business must cover it too. **It is a legal text, not code**: it does not block development, but it is worth having before sending real emails to real users. Confirm it with a lawyer.

**Question 15.** (a) Gmail clips emails with very large HTML (around 100 KB) and shows "view entire message": that is why the preview warns above 100 KB. (b) Resend keeps a copy of each sent email for about 30 days; it matters for data-subject erasure (`08` §6). Neither requires a decision.
