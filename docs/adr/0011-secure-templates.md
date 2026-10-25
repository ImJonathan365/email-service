# ADR-0011 — Versioned, logic-less templates with context-aware escaping, a linter and a helper whitelist

- **Status:** Accepted (owner, 2026-10-05)
- **Date:** 2026-10-05
- **Author:** agent: Claude (documentation audit)
- **Supersedes:** ADR-0005
- **Related requirements:** FR-04, FR-05, FR-06, FR-07 (AC-07.5, AC-07.8), FR-33, FR-36, NFR-15

## Context

ADR-0005 decided on templates in the database, versioned, immutable once published, pinned per message, with logic-less Handlebars and `{{{ }}}` forbidden. The first three decisions are **kept**. The audit found that the protection against injection and XSS was not closed:

1. Handlebars also allows unescaped output with `{{& variable}}` ([handlebars.java README](https://github.com/jknack/handlebars.java)).
2. Handlebars.java registers helpers such as `partial`, `block`, `embedded`, `precompile`, `i18n` and `log` by default. `partial` and `embedded` load content through the `TemplateLoader`, and the library has fixed path traversal vulnerabilities in its loaders ([4.5.x releases](https://github.com/jknack/handlebars.java/releases)).
3. Reflection-based value resolvers (`JavaBeanValueResolver`, `MethodValueResolver`) allow invoking public getters or methods of the context objects.
4. HTML escaping does not protect URLs (`href="javascript:…"`, or a phishing domain), CSS or unquoted attributes.
5. HTML escaping was applied to the subject and the plain text, which corrupts characters (`&amp;`).
6. The product's own API key could publish templates, so a leaked key published arbitrary content.

## Decision

1. Kept: templates in the database, `DRAFT`/`PUBLISHED`/`ARCHIVED` versions, immutable once published and pinned per message (`template_version_id`).
2. **Engine:** hardened Handlebars.java.
   - It is created with a `TemplateLoader` that **loads nothing**.
   - All default helpers are **removed**, and only these are registered: `if`, `unless`, `each`, `with`, `formatDate`, `formatNumber` and `formatMoney`.
   - The context is always a `Map` built from JSON, and **only** `MapValueResolver` is used.
3. **Syntax validation on save:** `{{{`, `{{&`, `{{>`, `{{#>` and any helper or block identifier outside the whitelist are rejected → `422 UNSAFE_TEMPLATE_CONSTRUCT`.
4. **HTML context linter** (with a real HTML parser, e.g. jsoup):
   - forbids variables inside `<script>`, `<style>`, `style` attributes, `on*` attributes and unquoted attributes;
   - forbids the `<script>`, `<iframe>`, `<object>`, `<embed>` and `<form>` tags;
   - requires every variable in `href`/`src` to be declared with `format: uri`.
5. **Context-aware escaping:** HTML in `htmlTemplate`; none in `subjectTemplate` and `textTemplate` (the subject also loses CR/LF).
6. **URLs in variables:** on acceptance, every `format: uri` variable must be `https` and its host must belong to `tenant.allowedLinkHosts` → otherwise `422 UNSAFE_URL`. With `APP_ENV=local` only, `http`/`https` to `localhost` and `*.localhost` are accepted as well (ADR-0021).
7. **A schema is required to publish**, with a defined subset of JSON Schema (`06` §3.3), including `x-sensitive` for the variables that are purged on sending and redacted in stored content.
8. **Deterministic rendering:** there is no "current date" helper; formatting uses explicit `tenant.locale`/`tenant.timezone`.
9. **Rendering on acceptance too** (result discarded): render errors are synchronous `422`s.
10. **Template management only with the `templates:write` scope** (FR-33), which the key deployed in a product never has.
11. **Open and click tracking** only in the `NOTICE` category, and disabled by default.
12. **Limits:** HTML source ≤ 256 KB; a warning if the rendered HTML exceeds 100 KB (Gmail clipping, figure to be confirmed).

## Alternatives considered

| Alternative | For | Against | Why discarded |
|---|---|---|---|
| jmustache (pure Mustache) | No helpers or partials by default; smaller surface | Also has `{{& }}`; without formatting helpers everything would have to be preformatted in the product; less syntax validation | Fallback option if Handlebars.java stops being maintained |
| Sanitizing the rendered HTML (OWASP Java HTML Sanitizer) | Defence in depth | May alter legitimate email designs (tables, inline styles); per-message cost | Not adopted in the MVP; the linter at publication covers the risk |
| Templates in the repository (files) | Review in PRs | Every text change needs a deployment | Same as in ADR-0005 |
| Thymeleaf or FreeMarker | More powerful | They evaluate expressions (SSTI) | Same as in ADR-0005 |

## Consequences

- **Positive:** a leaked product key can no longer publish content; URL injection is limited to the product's own hosts; subject and text without corruption; minimal engine surface.
- **Negative / accepted trade-offs:**
  - templates cannot share partials (header and footer are repeated in each template; accepted, there are few);
  - the linter is our own code that must be tested (coverage ≥ 90 %, NFR-12);
  - Handlebars.java has a slow release cadence (the latest series is 4.5.x; the exact date of the latest release to be confirmed).

## Review criterion

- Handlebars.java with no releases for 24 months or with a CVE unpatched for 30 days → migrate to jmustache.
- A need for i18n per locale or shared partials (→ design a controlled composition).
- Template editing by non-technical people (→ dashboard, phase 1).
