# ADR-0020 — Joint publication of the versions of several locales

- **Status:** Accepted (owner, 2026-10-07)
- **Date:** 2026-10-07
- **Author:** agent: Claude (H3)
- **Supersedes:** — (complements ADR-0018)
- **Related requirements:** FR-05, FR-37 (AC-37.6, AC-37.8), FR-22

## Context

ADR-0018 requires all published versions of a template, one per locale, to declare the same `required` variables (AC-37.6), and in H3 one version was published at a time. Together, those two rules prevent changing the `required` set of a template that already has several published locales: publishing the first one clashes with the other, still published with the previous set. Reproduced against the service: `required: [a]` → `[a, b]` with `es-CR` and `en` published gives `422` on both.

## Decision

1. A new endpoint `POST /v1/templates/{key}/publish` (scope `templates:write`) with the body `{ "versions": { "es-CR": 5, "en": 3 } }`: one draft per locale.
2. The given versions are published in **a single transaction**, with the template row locked:
   - each version is validated as when published alone: it is a draft, its `locale` matches the key, it has a schema (AC-05.5) and declares its URL variables as `uri` (AC-04.8);
   - AC-37.6 is checked on the **resulting set**: the new versions plus the published ones of the locales left untouched;
   - the previously published version of each locale is archived;
   - one `TEMPLATE_PUBLISHED` audit entry is written per version;
   - if any check fails, none is published.
3. `POST /v1/templates/{key}/versions/{version}/publish` stays for changes that do not alter `required`; it shares the same code.
4. `scripts/publish-template.sh` uses the joint endpoint whenever more than one locale changes.

## Alternatives considered

| Alternative | For | Against | Why discarded |
|---|---|---|---|
| Accepting the limitation and creating another `key` | No new code | Every variable change forces migrating the `key` in the products | A recurring cost for the owner and the products |
| Relaxing AC-37.6 during a window | No new endpoint | Temporarily leaves incompatible locales published; a send in between may fail | Breaks the guarantee AC-37.6 protects |
| Unpublishing a locale | Simple | Leaves the template without that locale meanwhile | Avoidable loss of service |

## Consequences

- **Positive:** the required variables of any multi-locale template can change without changing its `key`; the script never leaves a half-done publication.
- **Negative / accepted trade-offs:** one more endpoint in the public contract (compatible, NFR-18).

## Review criterion

If there come to be templates with many locales (> 5) or slow publications because of the template row lock.
