# 06 — Modelo de datos

> Revisión 2026-10-05: RLS desde el MVP (ADR-0008), claves foráneas compuestas, cola con `lock_token` y `priority` (ADR-0010), supresión global/tenant (ADR-0012), ámbitos de keys (ADR-0017), plantillas por idioma (ADR-0018). Adjuntos fuera del MVP (ADR-0015 Rejected): la tabla `email_attachment` queda documentada pero **no se crea**. Ninguna migración se ha aplicado todavía (Fase 0 sin empezar), así que este documento describe directamente la V1.

## 1. Principios

- Base de datos **exclusiva** del email-service. Ningún producto se conecta a ella; todo pasa por la API.
- Migraciones con **Flyway**; una migración aplicada **nunca** se edita.
- Enums como `text` + `CHECK` (evolucionan mejor que los tipos `ENUM` de PostgreSQL).
- Timestamps en `timestamptz` (UTC).
- Datos de forma variable en `jsonb` (variables, payloads, metadatos).
- `tenant_id` en **todas** las tablas de negocio; forma parte de los índices de acceso, de las claves foráneas (compuestas) y de las políticas RLS.
- **Ningún secreto en claro**: de las API keys solo se guardan el prefijo y el hash; las credenciales del proveedor no están en la base de datos.
- **(rev. 2026-10)** Tres roles de base de datos (ADR-0008):

| Rol | Uso | Privilegios |
|---|---|---|
| `email_owner` | Migraciones (`APP_ROLE=migrate`) | Dueño del esquema; DDL. Nunca lo usa la app en ejecución |
| `email_app` | Peticiones de tenant (API) | `SELECT/INSERT/UPDATE/DELETE` sobre tablas de negocio; **(rev. 2026-10-06)** solo `SELECT` sobre `tenant` (su propia fila); en `suppression`, `SELECT`/`INSERT` por columna sin `source_tenant_id` ni `source_message_id`, y `DELETE`; solo `SELECT`/`INSERT` en `audit_log`; **sujeto a RLS**; sin DDL |
| `email_system` | Worker, barridos, purga, webhooks, lookup de API key, administración | `BYPASSRLS`; sin DDL. **(rev. 2026-10-06)** `SELECT/INSERT/UPDATE/DELETE` sobre todas las tablas (incluida `tenant`), salvo `UPDATE`/`DELETE` en `audit_log`. Solo accesible desde los paquetes permitidos (test de arquitectura) |

**(rev. 2026-10-06)** Los tres roles los crea `scripts/init-db-roles.sql` (en local, como superusuario al iniciar el contenedor). `BYPASSRLS` se concede ahí y no en una migración, porque `email_owner` no es superusuario. Si el PostgreSQL gestionado no permite `BYPASSRLS`, se usará la alternativa de ADR-0008: una política `TO email_system USING (true)` por tabla.

## 2. Diagrama

```
┌────────────────────────┐
│        tenant          │  (un producto: pipemend, colmena, chinamo, payverica)
└───┬─────────┬──────┬───┘
    │1:N      │1:N   │1:N
┌───▼────┐ ┌──▼──────────┐ ┌───────▼────────┐
│api_key │ │  template   │ │  suppression   │  (scope TENANT con tenant_id;
└────────┘ └──┬──────────┘ └────────────────┘   scope GLOBAL sin tenant_id)
              │1:N
     ┌────────▼─────────┐
     │ template_version │ (PUBLISHED = inmutable)
     └────────┬─────────┘
              │ 1:N (versión fijada en el envío; FK compuesta con tenant_id)
     ┌────────▼───────────────────────────────┐
     │            email_message               │ ← también ES la cola de trabajo
     │  status, priority, attempts,           │
     │  next_attempt_at, lock_token,          │
     │  lock_expires_at, provider_message_id  │
     └───┬───────────────────┬────────────────┘
         │1:N                │1:N
┌────────▼─────────┐  ┌──────▼───────────┐   ┌───────────────┐   ┌────────────────────┐
│   email_event    │  │ email_attachment │   │   audit_log   │   │ rate_limit_counter │
└──────────────────┘  └──────────────────┘   └───────────────┘   └────────────────────┘
```

Nueve tablas en el MVP: `tenant`, `api_key`, `template`, `template_version`, `email_message`, `email_event`, `suppression`, `audit_log` y `rate_limit_counter` (`email_attachment` solo como diseño de referencia). **(rev. 2026-10)** `scheduled_lock` se elimina: las tareas programadas usan `pg_try_advisory_xact_lock` (ver `05` §5).

## 3. Tablas

### 3.1 `tenant` — producto origen

| Columna | Tipo | Notas |
|---|---|---|
| `id` | `uuid` PK | |
| `slug` | `text` UNIQUE | `colmena` |
| `name` | `text` | |
| `status` | `text` CHECK (`ACTIVE`,`SUSPENDED`) | |
| `from_email` / `from_name` | `text` | Remitente por defecto |
| `reply_to` | `text` NULL | |
| `allowed_from_domains` | `text[]` | Dominios válidos como remitente |
| `allowed_link_hosts` | `text[]` | **(rev. 2026-10)** Hosts válidos en variables URL (AC-07.8) |
| `locale` | `text` | **(rev. 2026-10)** BCP-47, default `es-CR` |
| `timezone` | `text` | **(rev. 2026-10)** IANA, default `America/Costa_Rica` |
| `rate_limit_per_minute` | `int` | Default 60 |
| `daily_quota` | `int` | **(rev. 2026-10)** Default 1 000 |
| `retention_days` | `int` | Default 90 |
| `store_rendered_content` | `boolean` | Default `false` (privacidad) |
| `sending_paused_at` | `timestamptz` NULL | **(rev. 2026-10)** Pausa por anomalía (FR-34) |
| `pause_reason` | `text` NULL CHECK (`VOLUME_ANOMALY`,`BOUNCE_RATE`,`COMPLAINT_RATE`,`MANUAL`) | |
| `anomaly_overrides` | `jsonb` NULL | **(rev. 2026-10)** Umbrales propios (AC-34.5) |
| `created_at` / `updated_at` | `timestamptz` | |

### 3.2 `api_key` — credencial de un tenant

| Columna | Tipo | Notas |
|---|---|---|
| `id` | `uuid` PK | |
| `tenant_id` | `uuid` FK | |
| `name` | `text` | "producción", "rotación 2026-09" |
| `key_prefix` | `text` UNIQUE | **(rev. 2026-10)** `esk_{env}_{8 Base62}`; permite buscar sin exponer el secreto |
| `key_hash` | `bytea` | SHA-256 del secreto (32 bytes) |
| `scopes` | `text[]` | **(rev. 2026-10)** ⊆ {`emails:send`,`emails:read`,`templates:write`,`suppressions:write`} |
| `allowed_cidrs` | `cidr[]` | **(rev. 2026-10)** Vacío = cualquier origen |
| `status` | `text` CHECK (`ACTIVE`,`REVOKED`) | |
| `expires_at` | `timestamptz` NULL | |
| `last_used_at` | `timestamptz` NULL | Precisión de minuto |
| `created_at` / `revoked_at` | `timestamptz` | |

### 3.3 `template` y `template_version`

`template`: `id`, `tenant_id`, `key` (slug kebab-case), `name`, `description`, **`category`** (`SECURITY`,`TRANSACTIONAL`,`NOTICE`), **`tracking_enabled`** (`boolean`, default `false`; solo `true` si `category = NOTICE`), `status` (`ACTIVE`,`ARCHIVED`), `created_at`, `updated_at`. `UNIQUE (tenant_id, key)` y `UNIQUE (tenant_id, id)` (para las FK compuestas).

`template_version`:

| Columna | Tipo | Notas |
|---|---|---|
| `id` | `uuid` PK | |
| `template_id` | `uuid` | **(rev. 2026-10)** FK compuesta `(tenant_id, template_id) → template(tenant_id, id)` |
| `tenant_id` | `uuid` | Ya no es una copia sin control: la FK compuesta lo ata al de la plantilla |
| `version` | `int` | Correlativo desde 1. `UNIQUE (template_id, version)` |
| `subject_template` | `text` | Handlebars, sin escapado HTML |
| `html_template` | `text` | Handlebars, escapado HTML; ≤ 256 KB |
| `text_template` | `text` NULL | Alternativa en texto plano, sin escapado HTML |
| `variables_schema` | `jsonb` NULL | Obligatorio para publicar; subconjunto abajo |
| `locale` | `text` | **(rev. 2026-10, ADR-0018)** ∈ `SUPPORTED_LOCALES` (`es-CR`, `en`). Índice único parcial `(template_id, locale) WHERE status = 'PUBLISHED'` |
| `status` | `text` CHECK (`DRAFT`,`PUBLISHED`,`ARCHIVED`) | `PUBLISHED` es inmutable (trigger que rechaza `UPDATE` de contenido) |
| `created_at` / `published_at` | `timestamptz` | |

**(rev. 2026-10)** Subconjunto de JSON Schema admitido en `variables_schema`: un objeto raíz con `required` (array) y `properties`. Cada propiedad admite `type` (`string`, `integer`, `number`, `boolean`, `array`, `object`), `format` (`uri`, `email`, `date-time`), `maxLength` (≤ 2 000; default 500), `maxItems` (≤ 500; default 100), `items` y `properties` (anidación ≤ 3 niveles) y `x-sensitive` (`boolean`). Cualquier otra palabra clave → `422 VALIDATION_ERROR` al guardar.

### 3.4 `email_message` — envío **y** trabajo en cola

| Columna | Tipo | Notas |
|---|---|---|
| `id` | `uuid` PK | Identificador público del envío (UUIDv7); también es la `Idempotency-Key` hacia el proveedor |
| `tenant_id` | `uuid` FK | |
| `idempotency_key` | `text` NULL | `UNIQUE (tenant_id, idempotency_key)`; se pone a `NULL` a las 24 h |
| `request_hash` | `char(64)` NULL | SHA-256 del payload canónico (AC-08.2); se pone a `NULL` junto con la clave |
| `template_id` / `template_version_id` | `uuid` | **(rev. 2026-10)** FK compuestas con `tenant_id`; versión **fijada** al aceptar |
| `category` | `text` | **(rev. 2026-10)** Copia de la categoría de la plantilla |
| `locale` | `text` | **(rev. 2026-10, ADR-0018)** Locale efectivo de la versión fijada |
| `priority` | `smallint` | **(rev. 2026-10)** 0 `SECURITY`, 1 `TRANSACTIONAL`, 2 `NOTICE` |
| `to_email` / `to_name` | `text` | Un destinatario principal |
| `cc` / `bcc` | `text[]` NULL | Máx. 5 cada uno |
| `from_email` / `from_name` / `reply_to` | `text` | Resueltos en la aceptación; `from_name` puede venir de la petición (AC-09.5) |
| `variables` | `jsonb` | Necesarias para renderizar y reintentar; purgadas según AC-23.5 |
| `variables_purged_at` | `timestamptz` NULL | **(rev. 2026-10)** |
| `rendered_subject` / `rendered_html` / `rendered_text` | `text` NULL | Solo si `store_rendered_content`; con `x-sensitive` redactadas |
| `status` | `text` CHECK (`QUEUED`,`SENDING`,`SENT`,`DELIVERED`,`BOUNCED`,`COMPLAINED`,`FAILED`,`CANCELED`) | Ver `02` §2 |
| `attempts` | `int` | Default 0; **(rev. 2026-10)** se incrementa al **tomar** el mensaje |
| `first_attempt_at` | `timestamptz` NULL | **(rev. 2026-10)** Para la ventana de AC-13.7 |
| `next_attempt_at` | `timestamptz` | Momento a partir del cual es elegible (también `sendAt`) |
| `send_at` | `timestamptz` NULL | **(rev. 2026-10)** Valor pedido por el cliente (FR-31), solo informativo |
| `lock_token` | `uuid` NULL | **(rev. 2026-10)** *Fencing token* de la toma actual |
| `lock_expires_at` | `timestamptz` NULL | Protección ante caída del worker |
| `locked_by` | `text` NULL | Identificador de instancia (diagnóstico) |
| `provider` | `text` NULL | `resend`, `smtp`, … |
| `provider_message_id` | `text` NULL | Correlación con webhooks |
| `failure_code` / `failure_detail` | `text` NULL | Ver §4; `failure_detail` ≤ 500 caracteres, sin datos personales |
| `attempt_log` | `jsonb` NULL | Últimos 10 intentos: fecha, código, mensaje |
| `tags` | `text[]` NULL | Para filtrar (`signup`, `invoice`); ASCII `[a-z0-9_-]`, ≤ 10, ≤ 64 caracteres |
| `metadata` | `jsonb` NULL | Datos opacos del producto (máx. 4 KB) |
| `tracking_enabled` | `boolean` | **(rev. 2026-10)** Copia de la plantilla |
| `first_opened_at` / `first_clicked_at` | `timestamptz` NULL | |
| `open_count` / `click_count` | `int` | Default 0 |
| `created_at` / `updated_at` / `sent_at` / `delivered_at` / `finalized_at` | `timestamptz` | **(rev. 2026-10)** `finalized_at` = momento en que llegó a estado terminal (para la purga) |

Índices:

```sql
CREATE INDEX ix_msg_queue ON email_message (priority, next_attempt_at)
    WHERE status = 'QUEUED';                              -- la cola (rev. 2026-10: con prioridad)
CREATE INDEX ix_msg_stuck ON email_message (lock_expires_at)
    WHERE status = 'SENDING';                             -- barrido de locks
CREATE INDEX ix_msg_tenant_created ON email_message (tenant_id, created_at DESC);
CREATE INDEX ix_msg_tenant_status ON email_message (tenant_id, status, created_at DESC);
CREATE INDEX ix_msg_tenant_to ON email_message (tenant_id, lower(to_email), created_at DESC);
CREATE INDEX ix_msg_to_global ON email_message (lower(to_email));   -- (rev. 2026-10) borrado por titular (FR-29)
CREATE INDEX ix_msg_purge ON email_message (finalized_at)
    WHERE variables_purged_at IS NULL AND finalized_at IS NOT NULL; -- (rev. 2026-10) purga de variables
CREATE UNIQUE INDEX uq_msg_provider_id ON email_message (provider, provider_message_id)
    WHERE provider_message_id IS NOT NULL;                -- correlación con webhooks
CREATE UNIQUE INDEX uq_msg_idempotency ON email_message (tenant_id, idempotency_key)
    WHERE idempotency_key IS NOT NULL;
```

### 3.5 `email_attachment` — NO SE CREA EN EL MVP (FR-30 Won't, ADR-0015 Rejected)

Diseño de referencia por si aparece un caso de uso real:

| Columna | Tipo | Notas |
|---|---|---|
| `id` | `uuid` PK | |
| `tenant_id` | `uuid` | FK compuesta `(tenant_id, message_id) → email_message(tenant_id, id)` `ON DELETE CASCADE` |
| `message_id` | `uuid` | |
| `position` | `smallint` | Orden (determinismo, AC-12.7) |
| `filename` | `text` | ≤ 100 caracteres |
| `content_type` | `text` CHECK (`application/pdf`,`application/xml`,`text/xml`) | |
| `size_bytes` | `int` | |
| `sha256` | `char(64)` | Entra en el `request_hash` |
| `content` | `bytea` NULL | Se pone a `NULL` (con `content_purged_at`) al llegar el mensaje a `SENT` o a un estado terminal |
| `content_purged_at` | `timestamptz` NULL | |

La fila (sin contenido) se conserva hasta la purga del mensaje, para que `GET /v1/emails/{id}` siga mostrando nombre, tipo, tamaño y `sha256`.

### 3.6 `email_event` — eventos del proveedor

| Columna | Tipo | Notas |
|---|---|---|
| `id` | `uuid` PK | |
| `tenant_id` | `uuid` NULL | `null` si el evento es huérfano |
| `message_id` | `uuid` NULL | `null` si no se pudo correlacionar; FK compuesta con `tenant_id` cuando ambos existen |
| `provider` | `text` | |
| `provider_event_id` | `text` | `svix-id` en Resend. `UNIQUE (provider, provider_event_id)` → dedupe |
| `type` | `text` CHECK (`SENT`,`DELIVERED`,`DELIVERY_DELAYED`,`BOUNCED`,`COMPLAINED`,`OPENED`,`CLICKED`,`FAILED`,`SUPPRESSED`,`OTHER`) | **(rev. 2026-10)** `BOUNCED_HARD` → `BOUNCED`; `BOUNCED_SOFT` → `DELIVERY_DELAYED` (renombrados antes de la primera migración; ver `02` §2.1) |
| `provider_type` | `text` | **(rev. 2026-10)** Tipo original (`email.bounced`…) |
| `occurred_at` | `timestamptz` | Según el proveedor |
| `received_at` | `timestamptz` | Default `now()` |
| `payload` | `jsonb` | **(rev. 2026-10)** Minimizado (AC-17.1) |

### 3.7 `suppression` — direcciones bloqueadas (rev. 2026-10, ADR-0012)

| Columna | Tipo | Notas |
|---|---|---|
| `id` | `uuid` PK | |
| `scope` | `text` CHECK (`GLOBAL`,`TENANT`) | |
| `tenant_id` | `uuid` NULL | Obligatorio si `scope = TENANT`; `NULL` si `GLOBAL` (CHECK) |
| `email` | `text` NULL | Normalizado en minúsculas; `NULL` tras un borrado por titular (FR-29) |
| `email_hash` | `bytea` | HMAC-SHA256(`SUPPRESSION_HASH_KEY`, email normalizado); clave de búsqueda |
| `reason` | `text` CHECK (`HARD_BOUNCE`,`COMPLAINT`,`PROVIDER`,`MANUAL`) | `MANUAL` ⇒ `TENANT`; el resto ⇒ `GLOBAL` (CHECK) |
| `source_tenant_id` | `uuid` NULL | Tenant cuyo envío la originó (diagnóstico, solo visible para admin) |
| `source_message_id` | `uuid` NULL | |
| `note` | `text` NULL | |
| `created_at` | `timestamptz` | |

Unicidad: `UNIQUE (email_hash) WHERE scope = 'GLOBAL'` y `UNIQUE (tenant_id, email_hash) WHERE scope = 'TENANT'`. RLS: el rol `email_app` ve las filas `TENANT` de su tenant y las `GLOBAL` (solo lectura; política separada para `SELECT`).

### 3.8 `audit_log`

`id`, `tenant_id` NULL, `actor_type` (`ADMIN`,`API_KEY`,`SYSTEM`), `actor_id` NULL, `action` (p. ej. `API_KEY_REVOKED`, `TENANT_PAUSED`, `DATA_SUBJECT_ERASED`), `resource_type`, `resource_id`, `ip`, `request_id`, `metadata` `jsonb`, `created_at`. Índice `(tenant_id, created_at DESC)`. Solo inserciones: `email_app` y `email_system` tienen `INSERT` y `SELECT`, no `UPDATE`/`DELETE`; la purga la ejecuta una función `SECURITY DEFINER` propiedad de `email_owner`.

### 3.9 `rate_limit_counter`

`tenant_id`, `window_kind` (`MINUTE`,`DAY`), `window_start` (`timestamptz`), `count` (`int`). PK `(tenant_id, window_kind, window_start)`. Se incrementa con `INSERT … ON CONFLICT DO UPDATE SET count = count + 1 RETURNING count`, **(rev. 2026-10)** dentro de la transacción del `INSERT` del mensaje (AC-21.5); las ventanas viejas se purgan a diario. La ventana `DAY` se calcula en la zona horaria del tenant.

### 3.10 `scheduled_lock` — DEPRECATED (rev. 2026-10)

Sustituida por `pg_try_advisory_xact_lock(hashtext('<nombre de tarea>'))`. No se crea.

## 4. Códigos de fallo (`failure_code`)

| Código | Clase | Reintenta | Estado |
|---|---|---|---|
| `SUPPRESSED` | Bloqueante | No | Vigente |
| `TENANT_SUSPENDED` | — | — | **DEPRECATED** (rev. 2026-10): un tenant suspendido retiene la cola (AC-02.3); queda solo como error HTTP 403 |
| `RENDER_ERROR` | Local | No | Vigente |
| `TEMPLATE_NOT_PUBLISHED` | — | — | **DEPRECATED** (rev. 2026-10): no puede ocurrir en el worker (la versión se fija al aceptar); queda solo como error HTTP 422 |
| `PROVIDER_REJECTED` | Permanente | No | Vigente |
| `INVALID_RECIPIENT` | Permanente | No | Vigente |
| `PROVIDER_FAILED` | Permanente (`email.failed`) | No | **Nuevo** |
| `PROVIDER_SUPPRESSED` | Permanente (`email.suppressed`) | No | **Nuevo** |
| `PROVIDER_IDEMPOTENCY_CONFLICT` | Permanente (409 del proveedor) | No | **Nuevo** |
| `PROVIDER_UNAVAILABLE` | Transitorio | Sí | Vigente (en `attempt_log`) |
| `PROVIDER_RATE_LIMITED` | Transitorio | Sí | Vigente (en `attempt_log`) |
| `PROVIDER_TIMEOUT` | Transitorio | Sí | Vigente (en `attempt_log`) |
| `LOCK_EXPIRED` | Transitorio (reclamado por el barrido) | Sí | **Nuevo** (en `attempt_log`) |
| `MAX_ATTEMPTS_EXCEEDED` | Final | No | Vigente |
| `RETRY_WINDOW_EXCEEDED` | Final | No | **Nuevo** (AC-13.7) |
| `CANCELED_BY_CLIENT` | Final | No | Vigente |

## 5. DDL de referencia (extracto: `V1__core.sql`, `V2__rls.sql`)

```sql
-- V1__core.sql (ejecutado por email_owner)
CREATE TABLE tenant (
    id                     uuid PRIMARY KEY,
    slug                   text        NOT NULL UNIQUE CHECK (slug ~ '^[a-z0-9-]{2,40}$'),
    name                   text        NOT NULL,
    status                 text        NOT NULL CHECK (status IN ('ACTIVE','SUSPENDED')),
    from_email             text        NOT NULL,
    from_name              text        NOT NULL,
    reply_to               text,
    allowed_from_domains   text[]      NOT NULL DEFAULT '{}',
    allowed_link_hosts     text[]      NOT NULL DEFAULT '{}',
    locale                 text        NOT NULL DEFAULT 'es-CR',
    timezone               text        NOT NULL DEFAULT 'America/Costa_Rica',
    rate_limit_per_minute  int         NOT NULL DEFAULT 60   CHECK (rate_limit_per_minute > 0),
    daily_quota            int         NOT NULL DEFAULT 1000 CHECK (daily_quota > 0),
    retention_days         int         NOT NULL DEFAULT 90   CHECK (retention_days BETWEEN 7 AND 400),
    store_rendered_content boolean     NOT NULL DEFAULT false,
    sending_paused_at      timestamptz,
    pause_reason           text CHECK (pause_reason IN ('VOLUME_ANOMALY','BOUNCE_RATE','COMPLAINT_RATE','MANUAL')),
    anomaly_overrides      jsonb,
    created_at             timestamptz NOT NULL DEFAULT now(),
    updated_at             timestamptz NOT NULL DEFAULT now(),
    CHECK ((sending_paused_at IS NULL) = (pause_reason IS NULL))
);

CREATE TABLE api_key (
    id            uuid PRIMARY KEY,
    tenant_id     uuid        NOT NULL REFERENCES tenant(id) ON DELETE CASCADE,
    name          text        NOT NULL,
    key_prefix    text        NOT NULL UNIQUE,
    key_hash      bytea       NOT NULL CHECK (length(key_hash) = 32),
    scopes        text[]      NOT NULL DEFAULT '{emails:send,emails:read}'
                  CHECK (scopes <@ ARRAY['emails:send','emails:read','templates:write','suppressions:write']),
    allowed_cidrs cidr[]      NOT NULL DEFAULT '{}',
    status        text        NOT NULL CHECK (status IN ('ACTIVE','REVOKED')),
    expires_at    timestamptz,
    last_used_at  timestamptz,
    created_at    timestamptz NOT NULL DEFAULT now(),
    revoked_at    timestamptz,
    CHECK ((status = 'REVOKED') = (revoked_at IS NOT NULL))
);
CREATE INDEX ix_api_key_tenant ON api_key (tenant_id, status);

CREATE TABLE template (
    id               uuid PRIMARY KEY,
    tenant_id        uuid NOT NULL REFERENCES tenant(id),
    key              text NOT NULL CHECK (key ~ '^[a-z0-9]+(-[a-z0-9]+)*$'),
    name             text NOT NULL,
    description      text,
    category         text NOT NULL CHECK (category IN ('SECURITY','TRANSACTIONAL','NOTICE')),
    tracking_enabled boolean NOT NULL DEFAULT false,
    status           text NOT NULL CHECK (status IN ('ACTIVE','ARCHIVED')),
    created_at       timestamptz NOT NULL DEFAULT now(),
    updated_at       timestamptz NOT NULL DEFAULT now(),
    UNIQUE (tenant_id, key),
    UNIQUE (tenant_id, id),
    CHECK (NOT tracking_enabled OR category = 'NOTICE')
);

CREATE TABLE template_version (
    id                uuid PRIMARY KEY,
    tenant_id         uuid NOT NULL,
    template_id       uuid NOT NULL,
    version           int  NOT NULL CHECK (version >= 1),
    subject_template  text NOT NULL CHECK (length(subject_template) <= 500),
    html_template     text NOT NULL CHECK (octet_length(html_template) <= 262144),
    text_template     text,
    variables_schema  jsonb,
    locale            text NOT NULL DEFAULT 'es-CR' CHECK (locale IN ('es-CR','en')),
    status            text NOT NULL CHECK (status IN ('DRAFT','PUBLISHED','ARCHIVED')),
    created_at        timestamptz NOT NULL DEFAULT now(),
    published_at      timestamptz,
    UNIQUE (template_id, version),
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, template_id) REFERENCES template (tenant_id, id),
    CHECK (status = 'DRAFT' OR (variables_schema IS NOT NULL AND published_at IS NOT NULL))
);
CREATE UNIQUE INDEX uq_tv_published_locale ON template_version (template_id, locale)
    WHERE status = 'PUBLISHED';                           -- (rev. 2026-10) una publicada por idioma

CREATE TABLE email_message (
    id                   uuid PRIMARY KEY,
    tenant_id            uuid        NOT NULL REFERENCES tenant(id),
    idempotency_key      text CHECK (length(idempotency_key) <= 256),
    request_hash         char(64),
    template_id          uuid        NOT NULL,
    template_version_id  uuid        NOT NULL,
    category             text        NOT NULL CHECK (category IN ('SECURITY','TRANSACTIONAL','NOTICE')),
    priority             smallint    NOT NULL CHECK (priority BETWEEN 0 AND 2),
    locale               text        NOT NULL DEFAULT 'es-CR',
    to_email             text        NOT NULL,
    to_name              text,
    cc                   text[] CHECK (cardinality(cc) <= 5),
    bcc                  text[] CHECK (cardinality(bcc) <= 5),
    from_email           text        NOT NULL,
    from_name            text        NOT NULL,
    reply_to             text,
    variables            jsonb       NOT NULL DEFAULT '{}'::jsonb,
    variables_purged_at  timestamptz,
    rendered_subject     text,
    rendered_html        text,
    rendered_text        text,
    status               text        NOT NULL CHECK (status IN
                            ('QUEUED','SENDING','SENT','DELIVERED','BOUNCED','COMPLAINED','FAILED','CANCELED')),
    attempts             int         NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    first_attempt_at     timestamptz,
    next_attempt_at      timestamptz NOT NULL DEFAULT now(),
    send_at              timestamptz,
    lock_token           uuid,
    lock_expires_at      timestamptz,
    locked_by            text,
    provider             text,
    provider_message_id  text,
    failure_code         text,
    failure_detail       text CHECK (length(failure_detail) <= 500),
    attempt_log          jsonb,
    tags                 text[] CHECK (cardinality(tags) <= 10),
    metadata             jsonb CHECK (pg_column_size(metadata) <= 4096),
    tracking_enabled     boolean     NOT NULL DEFAULT false,
    first_opened_at      timestamptz,
    first_clicked_at     timestamptz,
    open_count           int         NOT NULL DEFAULT 0,
    click_count          int         NOT NULL DEFAULT 0,
    created_at           timestamptz NOT NULL DEFAULT now(),
    updated_at           timestamptz NOT NULL DEFAULT now(),
    sent_at              timestamptz,
    delivered_at         timestamptz,
    finalized_at         timestamptz,
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, template_id)         REFERENCES template (tenant_id, id),
    FOREIGN KEY (tenant_id, template_version_id) REFERENCES template_version (tenant_id, id),
    -- Invariantes de la cola (rev. 2026-10)
    CHECK ((status = 'SENDING') = (lock_token IS NOT NULL AND lock_expires_at IS NOT NULL)),
    CHECK (status NOT IN ('SENT','DELIVERED','BOUNCED','COMPLAINED') OR provider_message_id IS NOT NULL
           OR provider = 'smtp'),
    CHECK (status <> 'FAILED' OR failure_code IS NOT NULL)
);
```

```sql
-- V2__rls.sql (ejecutado por email_owner) — patrón para cada tabla con tenant_id
-- (rev. 2026-10-06) nullif(): en una conexión reutilizada, tras una transacción con
-- set_config(..., true), la variable vale '' (no NULL) y ''::uuid daría error en vez de 0 filas.
ALTER TABLE email_message ENABLE ROW LEVEL SECURITY;
ALTER TABLE email_message FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON email_message
    USING      (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
-- Ídem en api_key, template, template_version, email_event,
-- rate_limit_counter y audit_log.

-- (rev. 2026-10-06) tenant: cada tenant solo ve su propia fila; nadie la escribe salvo email_system
ALTER TABLE tenant ENABLE ROW LEVEL SECURITY;
ALTER TABLE tenant FORCE  ROW LEVEL SECURITY;
CREATE POLICY tenant_self ON tenant FOR SELECT
    USING (id = nullif(current_setting('app.tenant_id', true), '')::uuid);

-- suppression: el tenant ve las suyas y las globales; solo escribe las suyas
ALTER TABLE suppression ENABLE ROW LEVEL SECURITY;
ALTER TABLE suppression FORCE  ROW LEVEL SECURITY;
CREATE POLICY supp_read ON suppression FOR SELECT
    USING (scope = 'GLOBAL' OR tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
CREATE POLICY supp_write ON suppression FOR INSERT
    WITH CHECK (scope = 'TENANT' AND tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);
CREATE POLICY supp_delete ON suppression FOR DELETE
    USING (scope = 'TENANT' AND tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

-- Permisos (rev. 2026-10-06)
GRANT USAGE ON SCHEMA public TO email_app, email_system;
GRANT SELECT, INSERT, UPDATE, DELETE
    ON api_key, template, template_version, email_message, email_event, rate_limit_counter
    TO email_app, email_system;
GRANT SELECT, INSERT ON audit_log TO email_app, email_system;          -- solo inserciones
GRANT SELECT ON tenant TO email_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON tenant TO email_system;
-- Un tenant no debe saber qué otro tenant originó una supresión global
GRANT SELECT (id, scope, tenant_id, email, email_hash, reason, note, created_at),
      INSERT (id, scope, tenant_id, email, email_hash, reason, note, created_at),
      DELETE
    ON suppression TO email_app;
GRANT SELECT, INSERT, UPDATE, DELETE ON suppression TO email_system;
-- BYPASSRLS de email_system: en scripts/init-db-roles.sql (requiere superusuario), no aquí.
```

En cada transacción de tenant, la aplicación ejecuta primero `SELECT set_config('app.tenant_id', :tenantId, true)` (el valor vive solo hasta el fin de la transacción; un pool de conexiones no puede filtrarlo a otra petición).

## 6. Consultas clave del worker (rev. 2026-10, ADR-0010)

Toma de trabajo (transacción corta, autocommit):

```sql
WITH picked AS (
    SELECT m.id
    FROM email_message m
    JOIN tenant t ON t.id = m.tenant_id
    WHERE m.status = 'QUEUED'
      AND m.next_attempt_at <= now()
      AND t.status = 'ACTIVE' AND t.sending_paused_at IS NULL
    ORDER BY m.priority, m.next_attempt_at
    FOR UPDATE OF m SKIP LOCKED
    LIMIT :freeSlots                         -- huecos libres de concurrencia, no un lote fijo
)
UPDATE email_message m
SET status           = 'SENDING',
    attempts         = m.attempts + 1,      -- se cuenta al tomar: el barrido no puede generar bucles infinitos
    first_attempt_at = coalesce(m.first_attempt_at, now()),
    lock_token       = gen_random_uuid(),
    lock_expires_at  = now() + make_interval(secs => :lockTimeout),
    locked_by        = :instanceId,
    updated_at       = now()
FROM picked
WHERE m.id = picked.id
RETURNING m.*;
```

Cierre con *fencing* (sin transacción abierta durante la llamada HTTP):

```sql
UPDATE email_message
SET status = 'SENT', provider = :provider, provider_message_id = :pmid, sent_at = now(),
    lock_token = NULL, lock_expires_at = NULL, locked_by = NULL, updated_at = now()
WHERE id = :id AND status = 'SENDING' AND lock_token = :token;
-- 0 filas ⇒ el lock se perdió: registrar en attempt_log y métrica; NO reenviar.
```

Barrido de mensajes atascados (cada 60 s, con `pg_try_advisory_xact_lock`):

```sql
UPDATE email_message
SET status = CASE WHEN attempts >= :maxAttempts THEN 'FAILED' ELSE 'QUEUED' END,
    failure_code = CASE WHEN attempts >= :maxAttempts THEN 'MAX_ATTEMPTS_EXCEEDED' END,
    finalized_at = CASE WHEN attempts >= :maxAttempts THEN now() END,
    next_attempt_at = now(),
    lock_token = NULL, lock_expires_at = NULL, locked_by = NULL, updated_at = now(),
    attempt_log = coalesce(attempt_log, '[]'::jsonb) || jsonb_build_object('at', now(), 'code', 'LOCK_EXPIRED')
WHERE status = 'SENDING' AND lock_expires_at < now();
```

Medido (2026-10-05, PostgreSQL 16 local, 12 800 mensajes en cola): la toma con `LIMIT 4` tarda entre 2,4 y 4,8 ms.

## 7. Consultas de operación

```sql
-- Profundidad y antigüedad de la cola elegible (métrica y alerta); excluye tenants retenidos
SELECT count(*) AS pending,
       coalesce(extract(epoch FROM now() - min(m.next_attempt_at)), 0) AS oldest_seconds
FROM email_message m JOIN tenant t ON t.id = m.tenant_id
WHERE m.status = 'QUEUED' AND m.next_attempt_at <= now()
  AND t.status = 'ACTIVE' AND t.sending_paused_at IS NULL;

-- Salud de envío por tenant en las últimas 24 h
SELECT t.slug, m.status, count(*)
FROM email_message m JOIN tenant t ON t.id = m.tenant_id
WHERE m.created_at > now() - interval '24 hours'
GROUP BY 1, 2 ORDER BY 1, 2;

-- Tasas de rebote y queja por tenant (FR-34)
SELECT tenant_id,
       count(*) FILTER (WHERE status = 'BOUNCED')::numeric
         / nullif(count(*) FILTER (WHERE status IN ('SENT','DELIVERED','BOUNCED','COMPLAINED')), 0) AS bounce_rate,
       count(*) FILTER (WHERE status = 'COMPLAINED')::numeric
         / nullif(count(*) FILTER (WHERE status IN ('DELIVERED','COMPLAINED')), 0) AS complaint_rate
FROM email_message
WHERE created_at > now() - interval '7 days'
GROUP BY 1;
```

## 8. Retención (rev. 2026-10)

| Dato | Retención por defecto |
|---|---|
| `email_message` + `email_event` | `tenant.retention_days` (90) |
| `variables` | Estado final + 7 días (`VARIABLES_RETENTION_DAYS`); las `x-sensitive`, al pasar a `SENT`/terminal |
| `rendered_html` / `rendered_text` | 30 días (se vacían antes que la fila) |
| `idempotency_key` / `request_hash` | 24 h (se ponen a `NULL`) |
| `email_event` huérfanos | 30 días |
| `audit_log` | 365 días |
| `suppression` | Indefinida (protege la reputación); tras un borrado por titular solo queda `email_hash` |
| `rate_limit_counter` | 2 días |
| Logs (fuera de la BD) | 30 días en el destino de logs |
| Respaldos | ≤ 35 días |
