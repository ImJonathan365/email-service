# ADR-0005 — Versioned templates, immutable once published, logic-less

- **Status:** Superseded by ADR-0011 (2026-10-05)
- **Date:** 2026-09-20
- **Related requirements:** FR-04, FR-05, FR-06, NFR-15

## Context
Each product has its own template (logo, colours, style). Templates live in the database so they can change without a deployment. That raises two risks: (a) a change breaking emails in flight or making what was sent irreproducible, and (b) a dynamic template becoming a code-execution (SSTI) or XSS vector.

## Decision
1. A template is identified by `key` within the tenant, with numbered versions.
2. `DRAFT` versions can be edited; when published they become `PUBLISHED` and are **immutable**. Publishing a new one archives the previous one, which stays usable if a message references it.
3. When a send is accepted, `template_version_id` is **pinned**: a retry renders exactly the same thing, and the history is reproducible.
4. A **logic-less** engine (Handlebars.java) with HTML escaping by default; unescaped interpolation (`{{{ }}}`) is forbidden and rejected when the template is validated.
5. An optional `variablesSchema` per version allows validating the variables before accepting the send.
6. Rendered content is stored only if the tenant enables `storeRenderedContent` (privacy).

## Alternatives considered
| Alternative | For | Against | Why discarded |
|---|---|---|---|
| Templates in the repository (files) | Versioned with git, reviewable in PRs | Every copy change needs a deployment; useless if someone else edits them one day | Discarded (an export to git can be kept as a backup) |
| Templates in the provider (Resend/Postmark) | Less code of our own | Ties the content to the provider; harder to migrate and version | Contradicts ADR-0003 |
| Thymeleaf or FreeMarker | More powerful | They evaluate expressions: SSTI surface with dynamically sourced templates | Discarded for security |
| Mutable versions | More convenient | Irreproducible history; an editing mistake affects sends in flight | Discarded |

## Consequences
- Positive: reproducible history; template changes without deployments; minimal injection surface.
- Negative: publishing a fix requires a new version (intended); variable validation is a subset of JSON Schema, not the full standard.

## Review criterion
A need for i18n per locale, for shared partials across templates or for editing by non-technical people (→ dashboard, phase 1).

---
> **Status note (2026-10-05):** only the *Status* line of this ADR was changed; its accepted text is not edited. The owner accepted ADR-0011, which supersedes it, on 2026-10-05.
