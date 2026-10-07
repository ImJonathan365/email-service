# 05 — Arquitectura

> Revisión 2026-10-05: flujo de aceptación reordenado (idempotencia antes del rate limit), worker con *fencing*, prioridad e idempotencia hacia el proveedor (ADR-0010), dos `DataSource` y RLS (ADR-0008), anomalías (FR-34), configuración completa.

## 1. Vista general

```
   PRODUCTOS (tenants)                        EMAIL-SERVICE (un solo artefacto)                    EXTERIOR
┌──────────────────────┐        ┌──────────────────────────────────────────────────┐
│  PipeMend            │        │                                                  │
│  Colmena             │        │   ┌────────────────────────────────────────────┐ │
│  Chinamo             │───────▶│   │  API  (APP_ROLE=api)                       │ │
│  Payverica           │ HTTPS  │   │  • Auth API key + ámbitos + CIDR           │ │
│  Bearer esk_live_... │keep-   │   │  • Idempotencia → validación → render      │ │
└──────────────────────┘alive   │   │    → rate limit → INSERT (una transacción) │ │
                                │   │  • Plantillas (CRUD + linter + preview)    │ │
                                │   │  • Consultas de envíos y eventos           │ │
                                │   │  • /admin/v1 (solo red privada)            │ │
                                │   └───────────────┬────────────────────────────┘ │
                                │  email_app (RLS)  │ INSERT (commit)               │
                                │                   ▼                               │
                                │   ┌────────────────────────────────────────────┐ │
                                │   │      PostgreSQL 18 (exclusiva, RLS)        │ │
                                │   │  tenant · api_key · template ·             │ │
                                │   │  template_version · email_message (COLA) · │ │
                                │   │  email_attachment · email_event ·          │ │
                                │   │  suppression · audit_log ·                 │ │
                                │   │  rate_limit_counter                        │ │
                                │   └───────────────▲──────────────┬─────────────┘ │
                                │  email_system     │ toma corta   │              │ │
                                │  (BYPASSRLS)      │ SKIP LOCKED  ▼              │ │
                                │   ┌───────────────┴────────────────────────────┐ │      ┌───────────────┐
                                │   │  WORKER  (APP_ROLE=worker)                 │ │      │  Proveedor    │
                                │   │  • Toma por prioridad (huecos libres)      │ │─────▶│  (Resend)     │
                                │   │  • Render (versión fijada)                 │ │ HTTPS│ Idempotency-  │
                                │   │  • EmailSender.send(idempotencyKey=id)     │ │◀─────│ Key = msg.id  │
                                │   │  • Cierre con lock_token (fencing)         │ │      └───────┬───────┘
                                │   │  • Barrido, purga, anomalías, métricas     │ │              │ webhook
                                │   └────────────────────────────────────────────┘ │              │ (Svix)
                                │   ┌────────────────────────────────────────────┐ │              │
                                │   │  /webhooks/{provider}  (en el rol API)     │◀┼──────────────┘
                                │   │  • WebhookVerifier: cuerpo crudo + ventana │ │
                                │   │  • Dedupe por svix-id                      │ │       ┌──────────────┐
                                │   │  • Estado + supresiones (email_system)     │ │       │  Mailpit     │
                                │   └────────────────────────────────────────────┘ │  dev  │ (SMTP local) │
                                └──────────────────────────────────────────────────┘◀──────└──────────────┘
```

**Un artefacto, cuatro roles** seleccionados por `APP_ROLE`:

| Rol | Contiene | Uso |
|---|---|---|
| `api` | Controladores REST, webhooks entrantes, administración | Instancias detrás del balanceador |
| `worker` | Envío, reintentos, barridos, purga, anomalías | 1 instancia por defecto (N con `WORKER_PROVIDER_RPS` repartido) |
| `all` | API + worker | Desarrollo local y despliegues pequeños (default) |
| `migrate` | **(rev. 2026-10)** Ejecuta Flyway con `email_owner` y termina | Paso previo de cada despliegue |

No hay microservicios adicionales, ni broker, ni caché externa, ni almacenamiento de objetos (NFR-10).

## 2. Módulos internos

```
com.emailservice
├── tenancy        # Tenant, ApiKey (ámbitos, CIDR), autenticación, contexto de tenant, administración
├── templates      # Template, TemplateVersion, motor Handlebars endurecido, linter, validación y preview
├── sending        # API de envío y lote, idempotencia, sendAt, cola, worker, política de reintentos, prioridad
├── provider       # EmailSender + WebhookVerifier (puertos) y sus implementaciones Resend / Smtp / Noop
├── events         # Webhooks entrantes, eventos, supresiones (global/tenant)
├── ratelimit      # Contadores por tenant y cuotas
├── abuse          # (rev. 2026-10) Detección de anomalías y pausa automática (FR-34)
├── audit          # Registro de acciones sensibles
├── maintenance    # (rev. 2026-10) Purga, borrado por titular, barridos
└── common         # Errores problem+json, configuración y su validación, logging/MDC, métricas
```

Reglas de dependencia (verificables con ArchUnit):

- `sending` y `events` dependen de `provider` **solo** a través de `EmailSender` y `WebhookVerifier`.
- Ningún módulo importa clases del SDK del proveedor fuera de `provider`.
- `templates` no conoce `sending`; recibe texto y variables, devuelve texto renderizado.
- Toda consulta de tenant pasa por un repositorio que exige `tenantId` y usa `tenantDataSource` (rol `email_app`, RLS).
- **(rev. 2026-10)** `systemDataSource` (rol `email_system`) solo se inyecta en `sending.worker`, `events`, `tenancy.auth`, `tenancy.admin`, `abuse` y `maintenance`.

## 3. Flujo de envío (camino feliz) (rev. 2026-10)

```
1.  POST /v1/emails            (producto; conexión reutilizada)
2.  Autenticación por API key → contexto de tenant; ámbito emails:send; CIDR         [FR-01, FR-33]
3.  Tenant ACTIVE y no pausado                                                       [FR-02, FR-34]
4.  BEGIN; set_config('app.tenant_id', …, true)                                       [ADR-0008]
5.  Idempotencia: si existe (tenant_id, key) → mismo hash ⇒ 200 original; distinto ⇒ 409   [FR-08]
6.  Validación de payload, direcciones, cabeceras, fromName, sendAt                  [FR-07, FR-09, FR-31]
7.  Resolución de plantilla por locale → template_version_id fijado; categoría → prioridad [FR-05, FR-36, FR-37]
8.  Validación de variables (esquema + URL https en allowedLinkHosts)                 [FR-07]
9.  Render de prueba (resultado descartado)                                           [AC-07.5]
10. Supresión (to/cc/bcc; global + tenant)                                            [FR-10]
11. Rate limit (UPSERT minuto/día) — después de validar                               [FR-21]
12. INSERT email_message (QUEUED, next_attempt_at = now() o sendAt) ON CONFLICT DO NOTHING   [FR-11, FR-31]
13. COMMIT → 202 Accepted { id, status }            ← el cliente ya puede seguir

--- asíncrono (worker, rol email_system) ---
14. Toma: SELECT … QUEUED, elegible, tenant activo ORDER BY priority, next_attempt_at
    FOR UPDATE SKIP LOCKED LIMIT <huecos libres>; UPDATE SENDING, attempts+1, lock_token   [ADR-0010]
    (COMMIT inmediato: ninguna llamada de red con locks abiertos)
15. Revalidar supresión y estado del tenant
16. Render de subject/html/text con la versión fijada                                 [FR-12]
17. Limitador de tasa (WORKER_PROVIDER_RPS) + circuit breaker
18. EmailSender.send(..., idempotencyKey = message.id) → providerMessageId           [FR-14]
19. UPDATE … SET SENT WHERE id AND lock_token = :token AND status = 'SENDING'         [AC-12.3]
20. Purga inmediata de variables x-sensitive                                          [AC-23.5]
--- más tarde ---
21. Webhook (Svix): verificar firma sobre el cuerpo crudo + ventana + dedupe por svix-id   [FR-16]
22. INSERT email_event (payload minimizado); avance de estado por precedencia         [FR-17]
23. Si bounced/complained/suppressed → supresión GLOBAL (independiente del estado)     [FR-10, ADR-0012]
```

## 4. Política de reintentos y clasificación de errores

| Clase | Ejemplos | Acción |
|---|---|---|
| **Transitorio** | timeout, error de red, `429`, `500`–`504` del proveedor, lock vencido | `QUEUED` con backoff (`attempts` ya se incrementó al tomar) |
| **Permanente** | `400`/`422` del proveedor, dirección inválida, dominio no verificado, contenido rechazado, `409` de idempotencia | `FAILED` inmediato con `failure_code` |
| **Local** | Error de render | `FAILED` inmediato (`RENDER_ERROR`), sin llamar al proveedor |
| **Bloqueante** | Destinatario suprimido | `FAILED` (`SUPPRESSED`) |
| **Retención** (rev. 2026-10) | Tenant suspendido o en pausa | **No** se toma; sigue `QUEUED` hasta reactivarse |

Backoff: `1 min → 5 min → 15 min → 1 h → 6 h` con jitter ±20 %; `MAX_ATTEMPTS` = 6 (5 esperas + 1). Ventana total < 23 h, validada al arrancar (AC-13.7). Un `429` con `Retry-After` respeta ese valor.

**Salvaguardas anti-duplicados (rev. 2026-10):**
1. `Idempotency-Key = message.id` hacia el proveedor (deduplicación de 24 h).
2. Cierre con `lock_token`: un worker que perdió el lock no cierra ni reintenta.
3. Si el mensaje ya tiene `provider_message_id`, nunca se vuelve a llamar al proveedor.

## 5. Tareas programadas (dentro del worker)

| Tarea | Frecuencia | Qué hace |
|---|---|---|
| `reclaimStuckMessages` | 60 s | `SENDING` con lock vencido → `QUEUED` (o `FAILED` si se agotaron los intentos) |
| `detectAnomalies` | 5 min | **(rev. 2026-10)** Volumen, rebotes y quejas por tenant → pausa (FR-34) |
| `purgeVariables` | 15 min | **(rev. 2026-10)** AC-23.5 |
| `purgeExpiredData` | Diaria | Retención por tenant (mensajes, eventos, contenido renderizado, huérfanos) |
| `purgeIdempotencyKeys` | Horaria | Claves de idempotencia vencidas |
| `emitQueueMetrics` | 30 s | Profundidad, antigüedad y mensajes retenidos |

Con varias instancias de worker, cada tarea se ejecuta dentro de `pg_try_advisory_xact_lock(hashtext('<tarea>'))`: si otra instancia la tiene, se salta esa vuelta. **(rev. 2026-10)** Se elimina la tabla `scheduled_lock`. No se añade Quartz ni ShedLock.

## 6. Configuración (variables de entorno) (rev. 2026-10)

| Variable | Default | Descripción |
|---|---|---|
| `APP_ENV` | `local` | `local` \| `staging` \| `production` |
| `APP_ROLE` | `all` | `api` \| `worker` \| `all` \| `migrate` |
| `DB_URL` | — | PostgreSQL |
| `DB_APP_USER` / `DB_APP_PASSWORD` | — | Rol `email_app` (RLS) |
| `DB_SYSTEM_USER` / `DB_SYSTEM_PASSWORD` | — | Rol `email_system` (worker, webhooks, admin) |
| `DB_OWNER_USER` / `DB_OWNER_PASSWORD` | — | Rol `email_owner`, solo con `APP_ROLE=migrate` |
| `ADMIN_API_KEYS` | — | Credenciales de `/admin/v1/**`, separadas por comas (obligatoria, sin default). **(rev. 2026-10-06)** Solo se exige con `APP_ROLE=api\|all` (el worker y `migrate` nunca la reciben); cada una de ≥ 32 caracteres |
| `TRUSTED_PROXIES` | *(vacío)* | CIDR de proxies cuya `X-Forwarded-For` se acepta |
| `MAIL_PROVIDER` | `smtp` | `resend` \| `smtp` \| `noop` |
| `RESEND_API_KEY` | — | Solo si `MAIL_PROVIDER=resend` |
| `MAIL_WEBHOOK_SIGNING_SECRET` | — | Secreto del endpoint de webhooks (Svix); admite dos valores separados por coma durante una rotación |
| `WEBHOOK_TOLERANCE_SECONDS` | `300` | Ventana de timestamp |
| `SMTP_HOST` / `SMTP_PORT` | `mailpit` / `1025` | Modo desarrollo |
| `ALLOW_SMTP_IN_PRODUCTION` | `false` | Fail-fast si `production` + `smtp` |
| `PROVIDER_CONNECT_TIMEOUT_MS` / `PROVIDER_READ_TIMEOUT_MS` | `3000` / `10000` | Timeouts hacia el proveedor |
| `WORKER_CONCURRENCY` | `4` | Envíos simultáneos (y máximo por toma) |
| `WORKER_POLL_INTERVAL_MS` | `1000` | Sondeo de la cola |
| `WORKER_PROVIDER_RPS` | `8` | Límite de tasa por instancia hacia el proveedor (NFR-21) |
| `MAX_ATTEMPTS` | `6` | = nº de esperas + 1 (validado) |
| `RETRY_BACKOFF_SECONDS` | `60,300,900,3600,21600` | Backoff (ventana total < 23 h, validado) |
| `LOCK_TIMEOUT_SECONDS` | `60` | Expiración del lock por mensaje |
| `CIRCUIT_BREAKER_FAILURES` / `CIRCUIT_BREAKER_OPEN_SECONDS` | `5` / `60` | Circuit breaker |
| `DEFAULT_RATE_LIMIT_PER_MINUTE` | `60` | Por tenant |
| `DEFAULT_DAILY_QUOTA` | `1000` | Por tenant (fijar ~3× el volumen esperado) |
| `DEFAULT_RETENTION_DAYS` | `90` | Por tenant |
| `VARIABLES_RETENTION_DAYS` | `7` | Tras el estado final (AC-23.5) |
| `IDEMPOTENCY_RETENTION_HOURS` | `24` | AC-08.5 |
| `SENT_FINAL_AFTER_HOURS` | `72` | `SENT` sin eventos = final por tiempo |
| `MAX_REQUEST_BYTES` | `262144` | Tamaño de payload |
| `MAX_BATCH_ITEMS` / `MAX_BATCH_BYTES` | `100` / `1048576` | Endpoint de lote |
| `SEND_AT_MAX_DAYS` | `30` | FR-31 |
| `SUPPORTED_LOCALES` | `es-CR,en` | FR-37 |
| `SUPPRESSION_REJECT_MODE` | `accept` | `accept` (202 + `FAILED`) \| `reject` (422) |
| `SUPPRESSION_HASH_KEY` | — | Clave HMAC de `email_hash` (secreto, obligatoria) |
| `ALLOWED_RECIPIENT_DOMAINS` | *(vacío)* | Allowlist de destino; **obligatoria** en `staging` |
| `QUEUE_AGE_ALERT_SECONDS` | `300` | Umbral de `DEGRADED` y de alerta |
| `ANOMALY_VOLUME_MULTIPLIER` / `ANOMALY_MIN_HOURLY` | `3` / `50` | FR-34 |
| `BOUNCE_PAUSE_RATE` / `COMPLAINT_PAUSE_RATE` | `0.04` / `0.001` | FR-34 |

Ninguna credencial vive en la base de datos ni en el repositorio (`08-seguridad.md`). La configuración se valida al arrancar (NFR-09).

## 7. Despliegue

**Local** (`docker compose up`): `app` (rol `all`, migra al arrancar con `APP_ENV=local`), `postgres:18-alpine` (con un script de inicio que crea los tres roles), `axllent/mailpit`.

**Producción (recomendado, simple):**

```
Internet ──TLS──▶ Balanceador / proxy ──▶ /webhooks/*            → app (APP_ROLE=api) ×1–2
                          │              (resto de rutas: solo red privada)
Productos ──red privada cifrada/TLS──▶ app (APP_ROLE=api)
                                       app (APP_ROLE=worker) ×1
                                       job (APP_ROLE=migrate) en cada despliegue
                                               │
                                     PostgreSQL gestionado (PITR, respaldos cifrados)
```

- La misma imagen para todos los roles; solo cambia una variable de entorno.
- Secuencia de despliegue: `migrate` → *rolling* de `api` → `worker`. Las migraciones deben ser compatibles hacia atrás (NFR-09).
- **(rev. 2026-10)** Solo `/webhooks/*` es accesible públicamente por HTTPS. `/v1/**` y `/admin/v1/**` quedan en red privada; `/admin/v1/**` además restringido por IP en el balanceador.
- Escalado: más instancias `api` si sube la latencia de aceptación; más `worker` solo si crece la antigüedad de la cola con el proveedor sano y por debajo de su límite de tasa (repartir `WORKER_PROVIDER_RPS`).

## 8. Estructura del repositorio

```
email-service/
├── AGENTS.md                     # reglas resumidas para agentes de IA
├── README.md                     # incluye: datos que recibe el proveedor, restauración probada, operación
├── docker-compose.yml
├── .env.example
├── .github/workflows/ci.yml
├── docs/                         # ← fuente de verdad
│   ├── adr/
│   └── auditoria/
├── contracts/
│   └── email-service.openapi.json
├── src/main/java/com/emailservice/{tenancy,templates,sending,provider,events,ratelimit,abuse,audit,maintenance,common}
├── src/main/resources/
│   ├── db/migration/             # Flyway V1__core.sql, V2__rls.sql, …
│   └── application.yaml
├── src/test/java/…               # unitarios + integración (Testcontainers con roles/RLS, WireMock) + ArchUnit
└── scripts/
    ├── init-db-roles.sql         # crea email_owner, email_app, email_system (local)
    ├── seed-local.sh             # tenant demo + API keys (envío y plantillas) + plantilla de ejemplo
    └── smoke-test.sh             # envío de prueba de extremo a extremo contra Mailpit
```
