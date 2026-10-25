# 12 — Project status and local use

> Created on 2026-10-14; revised on 2026-10-15 so that another session can pick up the work without the chat history. Part one: where the project is and what is missing. Part two: how to use the service from other projects locally. Deployment and H9 are paused until the owner makes the infrastructure decisions.

## Part 1 — Current status

### Picking up the work

1. **Read, in this order:**
   - `AGENTS.md`: rules, branches, tags and commands;
   - `docs/10-ai-agent-guide.md`;
   - this document;
   - the specific requirement in `02`.
2. **Stable version:** `main` at `v0.8.1`. It is the one the other projects use locally.
3. **How to work with the owner:**
   - plan first, and wait for their OK before writing code;
   - a `feat/*` or `fix/*` branch from `dev`, with Conventional commits that cite the FRs;
   - CI green before merging into `dev` with `--no-ff`;
   - only the owner merges `dev` → `main`, and then the agent creates and pushes the annotated tag.
4. **CI:** `.github/workflows/ci.yml`, with two jobs:
   - `build`: tests with Testcontainers;
   - `gitleaks`: version pinned in `GITLEAKS_VERSION`; the configuration is `.gitleaks.toml` plus `.gitleaksignore`.

   It runs on `main`, `dev`, `feat/**`, `fix/**` and on pull requests. Before waiting for a CI run, check that the branch is among those triggers.
5. **Next:** part A of H9 (below), item by item and with a plan first. Part B waits for the owner's decisions.

### Milestones and versions

| Milestone | Content | Tag |
|---|---|---|
| H1–H3 | Skeleton, tenancy and templates | `v0.3.0-h3` |
| H4 | End-to-end sending: queue, worker, SMTP → Mailpit | `v0.4.0-h4` |
| H5 | Real provider (Resend), circuit breaker and limiter | `v0.5.0-h5` |
| H6 | Webhooks, states by precedence and suppressions | no tag of its own: included in `v0.7.0-h7` (owner's decision) |
| H7 | Operations and protection: queries, quotas, anomalies, metrics, alerts, purges, data-subject erasure | `v0.7.0-h7` |
| H8 | Batch sending, `sendAt` and cancellation | `v0.8.0-h8` |
| — | Local use from other projects: Docker network, idempotent seed, ADR-0021, simulated events, templates from any repository (part 2) | `v0.8.1` |

### H9 (Production), part A: what can be done in the repository

None of these items needs infrastructure decisions. They are ordered by dependency: the audit and the simulated product go first, because the rest builds on them.

1. **Phase 0 closing audit**, with the details in the table below.
   - Each criterion of `09` must end up marked as done, with its evidence (a test or a document), or as pending, with the reason.
   - The biggest part is the coverage of the **Must** FRs.
2. **A simulated product in `examples/`.** It does not exist yet. It is a minimal project that does what a real product will do, following only part 2 of this document:
   - its own `docker-compose.yml`, joined to `email-service-net`;
   - a `.env` with `EMAIL_SERVICE_URL` and `EMAIL_SERVICE_API_KEY`, plus a versioned `.env.example`;
   - its templates in its own folder, published with `publish-template.sh` by path;
   - sends with an `Idempotency-Key` derived from a domain event (`07`): a single one, a batch and one with `sendAt`;
   - reading the state with `GET /v1/emails/{id}`.

   It counts as the "first product integrated locally" and is the proof of the integration guide. It must have its own end-to-end test script, which reaches Mailpit and simulates an event. No real product logic: the service must not know about any tenant.
3. **An end-to-end test guide with real Resend.** The README has the loose pieces, but a single procedure that joins them is missing:
   - **Pieces that already exist in the README:**
     - "Sending through Resend from staging": Resend team, domain, key and variables;
     - "Resend webhooks in staging": a proxy that lets only `/webhooks/*` through and a temporary `cloudflared` tunnel;
     - the test addresses `delivered@resend.dev`, `bounced@resend.dev` and `complained@resend.dev`.
   - **What the procedure has to cover,** step by step and from a development machine:
     - the app with `APP_ENV=staging` and `MAIL_PROVIDER=resend`, against the compose PostgreSQL;
     - `ALLOWED_RECIPIENT_DOMAINS=resend.dev`;
     - the tunnel, with the temporary endpoint registered in Resend and its secret;
     - one send to each `@resend.dev` address, checking the state (`DELIVERED`, `BOUNCED`, `COMPLAINED`) and the global suppressions;
     - the clean-up: tunnel, endpoint and test suppressions.
   - **To run it** the staging Resend account is needed (part B), but the document can be written and reviewed before.
   - It still never sends to real addresses, and the tests still never call Resend.
4. **Generic deployment manifests:**
   - the order `migrate → api → worker`;
   - the variables per role with their production values, except the secrets;
   - without choosing a hosting provider yet.
5. **PostgreSQL backups:**
   - an encrypted backup script and a restore script;
   - a restore rehearsal against a throwaway database, kept as a test or as a repeatable procedure.
6. **The list in `08` §10, only the items that are checked in code:**
   - secret scanning;
   - minimal Actuator: today `/actuator/health` shows the components; what is exposed outside local must be reviewed;
   - roles and RLS;
   - product keys without `templates:write` or `suppressions:write`;
   - isolation tests.
7. **A README integration guide under 30 minutes:** part 2 of this document without what is local-only, tested with the simulated product of item 2.

### H9 (Production), part B: what waits for the owner's decisions

Each item depends on a decision in the following list.

| Item | Depends on |
|---|---|
| Sending domain per product, with SPF, DKIM and DMARC verified in Resend | Domain |
| Staging and production Resend teams; webhook endpoint registered and signature verified with a real event | Resend accounts, domain, hosting |
| Secrets loaded in the manager: `ADMIN_API_KEYS`, `SUPPRESSION_HASH_KEY` (with an offline backup), `RESEND_API_KEY`, `MAIL_WEBHOOK_SIGNING_SECRET` and database passwords | Secrets |
| Staging deployed; NFR-19 measured there (acceptance → `SENT`, p95 ≤ 5 s) | Hosting, Resend accounts |
| Production deployed: PostgreSQL with an active encrypted backup and the **restore tested once**, TLS, and only `/webhooks/*` public | Hosting, secrets |
| Alerts connected to the hosting's logs (the `alert` field, README "Operations") | Hosting |
| The first real product sending in production | All of the above, plus that product's legal texts |

**The owner's pending decisions:**
- **Domain:** which sending domain or subdomain each product uses (for example, `mail.colmena.cr`), and who manages its DNS.
- **Hosting:**
  - where `api`, `worker` and PostgreSQL run;
  - how only `/webhooks/*` is exposed and the rest stays on a private network;
  - where the backups go.
- **Resend accounts:** staging and production teams, plan and limits.
- **Secrets:** which secrets manager is used and who has access; where the offline backup of `SUPPRESSION_HASH_KEY` is kept.
- **Legal texts** (`08` §6.1 and §10), which block each product's go-live, not development:
  - each product's privacy notice, which must cover the transfer to the provider in the US (Law 8968, art. 14);
  - Colmena's terms with its businesses.
- **H7 proposals still unconfirmed:**
  - AC-23.9: `DELIVERED` counts as final after 72 h, so that its variables are purged;
  - `03` NFR-11: the `PROVIDER_CONFIGURATION` and `AUTH_FAILURE_SPIKE` alerts, and the minimum of 20 messages for `FAILED_RATE`.

  They are implemented as proposed.

### Phase 0 closing audit (`09`)

Status as of 2026-10-15. "Done" only when there is concrete evidence.

| Criterion of `09` | Status | Evidence or what is missing |
|---|---|---|
| A real product in production sends its emails through the service | Pending | H9 part B |
| `docker compose up` from a clean clone sends to Mailpit without external credentials | Done | `scripts/smoke-test.sh` (AC-15.2), tested on 2026-10-14 |
| **Must** FRs with their criteria covered by tests | **To be audited** | See the following note |
| Multi-tenant isolation tests, no-context (RLS) and cross-FK tests | Done, to be confirmed in the audit | `RowLevelSecurityTest`, `SchemaInvariantsTest` and the per-endpoint tests of H2–H8; it remains to check that every `/v1/**` endpoint has its own |
| With the provider down, the API accepts and the queue drains when it recovers | Done, to be confirmed in the audit | The H5 circuit breaker tests (`ResendWorkerTest`); AC-11.2 has no test that names it |
| "Lost response" test: a single send at the simulated provider | Done | `ResendWorkerTest` (WireMock) |
| NFR-19 measured in staging | Pending | There is no staging (part B) |
| The list in `08` §10 complete | Pending | Part A, item 6, and part B |
| README with an integration guide in < 30 minutes | Pending | Part A, item 7 |

**Coverage of the Must FRs:**
- **Method:** a mechanical cross-check between the ACs in `02` (those of the FRs marked Must in the table in §1) and the references in `src/test/java`: `ac_XX_Y` names or `AC-XX.Y` comments.
- **Result:** of the **150** Must ACs, **122** have a test that names them. The remaining **28** are: AC-01.4, 02.5, 02.6, 03.6, 05.3, 10.3, 11.1, 11.2, 11.4, 11.5, 12.1, 12.4, 14.1, 14.3, 14.4, 14.5, 14.6, 14.7, 15.2, 15.4, 16.6, 21.6, 23.6, 23.8, 23.9, 29.4, 33.2 and 33.4.
- **What to do with each one:** find the test that already covers it under another name and reference the AC in it, or write the missing test.
  - Several have likely tests: for example, 14.3 and 14.7 with ArchUnit, 21.6 in `BatchAnomalyTest`, 33.2 with `INSUFFICIENT_SCOPE` and 15.2 with the smoke test.
  - Others cannot be verified with a test, and must be marked as such:
    - AC-23.6 does not apply, because FR-30 is rejected;
    - AC-23.9 waits for the owner's confirmation;
    - AC-29.4 is a procedure in `08` §6;
    - AC-14.5 is a design property.
- **Without evidence, the AC is not considered covered.**

## Part 2 — Using the service from other projects locally

### 1. Starting the service

From `main`, which is always deployable (or pinned to a tag: `git switch --detach v0.8.1`):

```bash
git switch main && git pull
cp -n .env.example .env        # only the first time; .env is not versioned
grep -q '^MAIL_WEBHOOK_SIGNING_SECRET=' .env \
  || grep '^MAIL_WEBHOOK_SIGNING_SECRET=' .env.example >> .env   # see the note below
docker compose up -d --build
```

Containers and services:
- `app`, `postgres` and `mailpit` have `restart: unless-stopped`: they start again with Docker until you stop them with `docker compose stop`.
- The API is at `http://localhost:8080`, Mailpit at `http://localhost:8025` and PostgreSQL at `localhost:5432`, all only on `127.0.0.1`.

**`MAIL_WEBHOOK_SIGNING_SECRET` in `.env`.** A `.env` copied before `v0.8.1` does not have this variable. The second line of the block above copies it from `.env.example` only if it is missing.
- It is a **fictional** secret, local only; gitleaks accepts it by its exact value (`.gitleaks.toml`).
- Without it, `/webhooks/resend` responds `404` and `scripts/simulate-event.sh` says so.
- If the app was already running, it has to be recreated with `docker compose up -d` to read it.
- Never put the secret of a real Resend endpoint here.

**Data:**
- `docker compose down` (without `-v`) keeps the database.
- **`docker compose down -v` deletes the PostgreSQL volume**, and with it every tenant, key, template, message and suppression. Afterwards, steps 2 and 4 have to be repeated in each project and the keys in their `.env` files updated.

### 2. One tenant per project

```bash
SLUG=colmena ./scripts/seed-local.sh
```

**The first time:**
- it creates the `colmena` tenant, with the sender `no-reply@colmena.localhost` and `allowedLinkHosts: ["colmena.localhost"]`;
- it prints two keys **only once**: the product key (`emails:send`, `emails:read`) and the operator key (`templates:write`, `suppressions:write`, `emails:read`);
- it publishes the templates in `templates/colmena/`, if they exist in this repository.

**Later runs:** the script touches nothing and says so. The stored keys keep working.

**Lost keys:** `--new-keys` issues a new pair. The previous ones stay valid until they are revoked (`GET /admin/v1/tenants/{slug}/api-keys` to see their `id`, `DELETE /admin/v1/api-keys/{id}`).

### 3. Where to store the keys

In the project's `.env`, which is **not versioned** (it must be in its `.gitignore`):

```bash
# Colmena's .env
EMAIL_SERVICE_URL=http://email-service:8080       # from a container; from the host, http://localhost:8080
EMAIL_SERVICE_API_KEY=esk_test_...                # product key
EMAIL_SERVICE_TEMPLATES_KEY=esk_test_...          # operator key: only for publishing templates
```

**Key rules:**
- The product's code only uses the product key.
- The operator key is for the publishing script. It does not go in the product's image or container (FR-33).
- Local keys start with `esk_test_` and exist only in the local database: each environment issues its own keys.

### 4. Templates in the product's repository

Each template is a folder with `template.json`, one `{locale}.html` per language and, optionally, a `{locale}.txt`. The format is in `05` §9 and the example in `templates/demo/password-reset/`.

```
colmena/
└── emails/
    └── password-reset/
        ├── template.json
        ├── es-CR.html
        └── en.html
```

Publishing from the product's repository, with the folder's path:

```bash
set -a; source .env; set +a
EMAIL_SERVICE_URL=http://localhost:8080 ../email-service/scripts/publish-template.sh emails/password-reset
```

How the script works:
- The operator key decides the tenant.
- Running it again without changes creates no versions.
- It publishes only if the preview of every locale passes.
- It needs `curl` and `jq`.

**Links in the templates:** with `APP_ENV=local` (the one in `.env.example`), URL variables also accept `http`/`https` to `localhost` and `*.localhost`, such as `http://colmena.localhost:3000/reset?t=…` (ADR-0021). In staging and production only `https` and the tenant's `allowedLinkHosts` are valid.

### 5. The Docker network

The service creates the `email-service-net` network (a fixed name, whatever the folder) and the app has the alias `email-service` in it. In each project's `docker-compose.yml`:

```yaml
services:
  app:                                  # the product's service that sends email
    environment:
      EMAIL_SERVICE_URL: http://email-service:8080
      EMAIL_SERVICE_API_KEY: ${EMAIL_SERVICE_API_KEY}
    networks: [default, email-service]

networks:
  email-service:
    name: email-service-net
    external: true
```

Notes:
- **Start order:** the email-service goes first. If the network does not exist, Docker refuses to start the project with `network email-service-net declared as external, but could not be found`.
- **The project's own network:** `default` is still the project's network, so its internal services are not exposed to the email-service.
- **From the host:** a product that runs outside Docker (for example, `npm run dev`) uses `http://localhost:8080`.

### 6. What changes when deploying

In the product's code, nothing. The client reads two variables, and the rest of the contract (`07`) is the same in every environment:

| Variable | Local | Staging / production |
|---|---|---|
| `EMAIL_SERVICE_URL` | `http://email-service:8080` | The internal URL of the deployed service (H9) |
| `EMAIL_SERVICE_API_KEY` | `esk_test_…` from the seed | A key issued in that environment (`esk_live_…` in production), from the secrets manager |

What does change is on the service's side, not the product's:
- the tenant's real sender and `allowedLinkHosts`;
- links, which must be `https`;
- in staging, the recipient allowlist.

### 7. Viewing the emails and simulating events

**Emails:** everything the worker sends arrives in Mailpit, at `http://localhost:8025`. It never goes out to the internet.

**Events:** Mailpit generates no delivery events, so they are simulated:

```bash
./scripts/simulate-event.sh <messageId> delivered     # or bounced, complained, delayed
```

**How it works:**
- The `messageId` is the `id` that `POST /v1/emails` returned.
- The script sends to `POST /webhooks/resend` an event in Resend's format, signed with Svix and the local secret from `.env`. It follows the same path as a real one: signature verification, state by precedence and suppressions. There is no special endpoint or shortcut.

**Effects:**
- `bounced` and `complained` create a **global** suppression of the address. Later sends to it are accepted (`202`) but end `FAILED` with `failureCode: SUPPRESSED`, without reaching Mailpit (AC-10.1).
- To remove it: `DELETE /admin/v1/suppressions/{email}` with the `X-Admin-Key` header.
- Many `bounced` or `complained` events in a row can pause the tenant because of its bounce or complaint rate (FR-34), as in production. It is lifted with `resume-sending` (item 8).
- `delayed` is recorded without changing the state.
- A `messageId` that does not exist is accepted (`204`) and suppresses nobody.

### 8. Raising the tenant's limits

By default, each tenant has 60 sends per minute, 1,000 per day and volume anomaly detection (a pause if an hour exceeds 3× the usual, with a minimum of 50). For local load tests:

```bash
set -a; source .env; set +a
curl -X PATCH http://localhost:8080/admin/v1/tenants/colmena \
  -H "X-Admin-Key: ${ADMIN_API_KEYS%%,*}" -H 'Content-Type: application/json' \
  -d '{"rateLimitPerMinute": 600, "dailyQuota": 50000, "anomalyOverrides": {"minHourly": 5000}}'
```

If the tenant was already paused because of an anomaly: `POST /admin/v1/tenants/colmena/resume-sending` with `{"note": "local tests"}`.

### 9. Local limitations

- **No real provider:**
  - no real delivery, no SPF/DKIM/DMARC and no provider events; events only exist if they are simulated;
  - `opened` and `clicked` are not simulated.
- **No deduplication at the provider:** SMTP has no `Idempotency-Key`, so a retry after a lost response can duplicate an email in Mailpit. It does not happen with Resend (ADR-0010, ADR-0016).
- **Local links:** links to `localhost` are accepted locally, but a template that only works with them will fail in staging. Test with `https` and an `allowedLinkHosts` host too.
- **Data:**
  - a single local PostgreSQL for every project;
  - `down -v` deletes everyone's data.
- **Shared network:** every project sees the email-service on `email-service-net`, and the isolation between them comes from the keys (and RLS), as in production.
- **Clock:** the service uses the container's time. `sendAt` and the quota windows depend on it.
