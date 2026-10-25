# ADR-0018 — Templates per locale (es-CR by default, English available) in the MVP

- **Status:** Accepted; complemented by ADR-0020 (joint multi-locale publication)
- **Date:** 2026-10-05
- **Author:** agent: Claude, from the owner's answer to open question 14
- **Complements:** ADR-0011 (it does not supersede it: it triggers its review criterion "a need for i18n per locale")
- **Related requirements:** FR-04, FR-05, FR-07, FR-37

## Context

All emails are sent in Spanish today, but the owner wants English already implemented and usable without later development. The previous design left "i18n per locale" for phase 2 and had only one `locale` per tenant, used to format dates and amounts.

## Decision

1. Each template **version** has a `locale` ∈ `SUPPORTED_LOCALES` (default `es-CR,en`). A template can have at most **one `PUBLISHED` version per locale**; publishing a version archives only the previously published one of **its own** locale.
2. `POST /v1/emails` accepts an optional `locale`. Resolution:
   1. the requested `locale`, if there is a published version in that locale;
   2. otherwise, `tenant.locale` (default `es-CR`);
   3. if that does not exist either, `422 TEMPLATE_NOT_PUBLISHED`.

   The response states the effective `locale`, and the message stores it together with the pinned version.
3. The `formatDate`, `formatNumber` and `formatMoney` helpers use the message's **effective** locale and `tenant.timezone`.
4. All locales of a template share the `key`, the category and the required `variablesSchema`. On publication, the new version's schema is validated as compatible with the other published versions: same `required` variables.
5. No automatic translation and no "master" templates: each language is a version written by a person.

## Alternatives considered

| Alternative | For | Against | Why discarded |
|---|---|---|---|
| A separate template per language (`welcome-en`) | Zero model changes | The product has to know the available languages; keys are duplicated | A worse contract for the products |
| A text catalogue (key-based i18n) inside a single template | A single HTML | More logic in the engine (against ADR-0011) | Contradicts the logic-less model |
| Leaving it for phase 2 | Less work now | The owner wants it available | Discarded by the owner |

## Consequences

- **Positive:** English is available without changing the contract later; the product only sends `locale`.
- **Negative / accepted trade-offs:** each template has to be maintained in two languages to use English; if the translation is missing, it silently falls back to Spanish (visible in the response and in the metric `email_locale_fallback_total`).

## Review criterion

A need for more than 3 languages, or for texts shared across templates (→ controlled partials).
