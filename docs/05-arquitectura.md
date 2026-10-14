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
- **(rev. 2026-10-11, owner)** Reglas añadidas en H6:
  - no hay ciclos entre los módulos de primer nivel (ArchUnit);
  - `sending` depende de `events` (supresiones, eventos guardados) y `events` nunca de `sending`: si `events` actualiza `email_message`, lo hace con su propio repositorio sobre el DataSource de sistema;
  - `EmailAddresses` (normalización y validación) y `SuppressionHashes` viven en `events`, el dueño de las supresiones;
  - el gestor de transacciones de tenant está en `tenancy` (antes en `common`, que formaba un ciclo).

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
| **Transitorio** | timeout, error de red, `429`, `500`–`504` del proveedor, `409` `concurrent_idempotent_requests` / `resource_locked` (la misma clave sigue en curso), lock vencido | `QUEUED` con backoff (`attempts` ya se incrementó al tomar) |
| **Permanente** | `400`/`422` del proveedor, dirección inválida, contenido rechazado, `409` `invalid_idempotent_request` (misma clave, cuerpo distinto) | `FAILED` inmediato con `failure_code` |
| **Configuración** (rev. 2026-10-10) | `401`, `403` de la API key (`restricted_api_key`, `suspended_api_key`, `invalid_permission`), `404`/`405`: falla nuestra cuenta, no el mensaje | Abre el circuit breaker de inmediato; el mensaje vuelve a `QUEUED` **sin** consumir intento (`PROVIDER_CONFIGURATION` en `attempt_log`); log de error y `email_provider_config_error_total{error}` (alerta) |
| **Configuración del tenant** (rev. 2026-10-10) | `403` `validation_error`: el dominio remitente del tenant no está verificado | Pausa **solo ese tenant** (`pause_reason = PROVIDER_DOMAIN`, auditoría `TENANT_PAUSED`, misma métrica); el mensaje vuelve a `QUEUED` sin consumir intento. Los demás tenants siguen enviando. Se levanta con `POST /admin/v1/tenants/{slug}/resume-sending` |
| **Local** | Error de render | `FAILED` inmediato (`RENDER_ERROR`), sin llamar al proveedor |
| **Bloqueante** | Destinatario suprimido | `FAILED` (`SUPPRESSED`) |
| **Retención** (rev. 2026-10) | Tenant suspendido o en pausa | **No** se toma; sigue `QUEUED` hasta reactivarse |

**(rev. 2026-10-07) Comportamiento implementado en H4:**
- **Cierre con *fencing*:** todo cierre (`SENT`, `FAILED`, vuelta a `QUEUED`) lleva `WHERE status = 'SENDING' AND lock_token = :mine`. Con 0 filas, el worker solo añade `LOST_LOCK` a `attempt_log` y suma `email_lost_lock_total`.
- **Purga de variables (AC-23.5):**
  - el mismo `UPDATE` que pasa a `SENT` elimina las variables `x-sensitive`, pero no fija `finalized_at`, porque `SENT` no es terminal;
  - `FAILED`, incluido el que deja el barrido, elimina las `x-sensitive` y sí fija `finalized_at`.
- **Tenant suspendido o pausado** entre la toma y el envío: el mensaje vuelve a `QUEUED` y se descuenta el intento (`attempts - 1`), porque retener no es fallar.
- **Error inesperado** (BD caída, bug): el mensaje se deja en `SENDING`; lo recupera el barrido al vencer el lock y el proveedor deduplica por `message.id`.
- **Métricas:**
  - `email_time_to_sent_seconds{category}` (NFR-19), de `created_at` a `SENT`;
  - `email_lost_lock_total`;
  - `email_locale_fallback_total`;
  - `email_requests_without_idempotency_total`.

**(rev. 2026-10-10) Comportamiento implementado en H5 (Resend):**
- **`ResendEmailSender`:** `POST /emails` con el cliente HTTP del JDK. Cada intento lleva `Idempotency-Key = message.id` y la etiqueta `message_id=<uuid>`; las etiquetas del producto van como `{name: tag, value: "true"}`. Del error del proveedor solo se guarda su `name` (p. ej. `Resend 422 validation_error`), nunca el mensaje, que puede repetir direcciones.
- **`supportsIdempotency()`:** el puerto declara si el proveedor deduplica por clave. Si no (SMTP), el worker lo advierte al arrancar: una respuesta perdida puede duplicar el correo.
- **`WORKER_PROVIDER_RPS`:** cada instancia espacia sus llamadas 1/RPS sin ráfaga, de modo que ninguna ventana de un segundo supera RPS. Las instancias no se coordinan: la suma se ajusta por configuración (NFR-21).
- **Circuit breaker (AC-13.9),** código propio, sin Resilience4j:
  - se abre tras `CIRCUIT_BREAKER_FAILURES` fallos transitorios seguidos, o de inmediato con un error de configuración;
  - abierto, el worker no toma mensajes, y los ya tomados en la misma tanda vuelven a `QUEUED` sin intento (`CIRCUIT_OPEN`);
  - tras `CIRCUIT_BREAKER_OPEN_SECONDS` deja pasar **un** envío de prueba: si va bien se cierra, si falla se abre otro periodo;
  - gauge `email_circuit_breaker_open`.
- **Ventana de 23 h:** los errores de configuración no consumen intentos, pero la ventana se cuenta desde `first_attempt_at`. Si la cuenta sigue rota más de 23 h, el mensaje termina en `FAILED` (`RETRY_WINDOW_EXCEEDED`) aunque `attempts` no haya subido.
- **Métricas nuevas:**
  - `email_provider_config_error_total{error}` (alerta);
  - `email_provider_idempotency_conflict_total` (indica un render no determinista, AC-12.7);
  - `email_circuit_breaker_open`.
- **Webhooks de Resend:** no se implementan aquí, llegan en H6.

Backoff: `1 min → 5 min → 15 min → 1 h → 6 h` con jitter ±20 %; `MAX_ATTEMPTS` = 6 (5 esperas + 1). Ventana total < 23 h, validada al arrancar (AC-13.7). Un `429` con `Retry-After` respeta ese valor.

**(rev. 2026-10-11) Comportamiento implementado en H6 (eventos):**
- **Webhook:**
  - `SvixWebhookVerifier` (propio, sin SDK) detrás de `WebhookVerifier`: HMAC-SHA256 sobre `svix-id.svix-timestamp.<cuerpo crudo>`, comparación en tiempo constante, uno o dos secretos `whsec_`, ventana ±`WEBHOOK_TOLERANCE_SECONDS`;
  - se verifica con el ejemplo publicado por Svix;
  - el endpoint solo existe con `APP_ROLE=api|all`.
- **Una transacción por evento** (rol `email_system`):
  - `INSERT … ON CONFLICT (provider, provider_event_id) DO NOTHING`: un duplicado no tiene efectos;
  - después, el cambio de estado por precedencia (cada `UPDATE` enumera los estados de los que puede salir) y las supresiones, independientes entre sí.
- **Eventos tempranos y fallo con evento posterior:** ver `02` §2.
- **Supresiones desde eventos:** `bounced` → `HARD_BOUNCE`, `complained` → `COMPLAINT`, `suppressed` y `suppression.added` → `PROVIDER`, todas globales; `suppression.removed` solo retira `PROVIDER`. Regla de `cc`/`bcc`: AC-10.3.
- **Payload minimizado por lista blanca:**
  - se guardan: `type`, `created_at`, `data.email_id`, `data.created_at`, `tags.message_id`, `bounce.type/subType`, `suppressed.type`, los timestamps de click y open, `click.link` sin query ni fragmento, y en `suppression.*` los campos `id`, `origin` y `source_id`;
  - nunca se guardan direcciones, asunto, cabeceras, IP ni user agent.
- **Métricas:**
  - `email_webhook_events_total{type,outcome}` (`outcome` = `processed`, `duplicate` u `orphan`);
  - `email_webhook_rejected_total{reason}`;
  - `email_webhook_correlation_conflict_total`;
  - `email_complaints_total{tenant}` (alerta) y `email_delivery_delayed_total{tenant}`;
  - `email_event_after_failure_total`;
  - `email_suppressions_created_total{reason}`.
- **Fuera de H6:** la purga de huérfanos a los 30 días (AC-16.5, tarea `purgeExpiredData`) y el historial de eventos en `GET /v1/emails/{id}` (H7).

**(rev. 2026-10-12) Comportamiento implementado en H7, parte 2:**
- **Rate limit y cuota** (FR-21):
  - el contador `rate_limit_counter` se incrementa en la transacción del `INSERT`, después de las validaciones;
  - la ventana `MINUTE` es UTC y la `DAY` sigue el calendario del tenant (`timezone`). **(rev. 2026-10-13)** Ambas se calculan con el `Clock` de la aplicación (ver "Fuente de la hora");
  - al superarse, `429 RATE_LIMITED` con `Retry-After` hasta el fin de la ventana; el rollback deshace el incremento, así que el rechazo no consume cuota;
  - los límites se leen en cada petición, así que un `PATCH` aplica sin redesplegar;
  - auditoría agregada: una fila `TENANT_RATE_LIMITED` por tenant, ventana y hora (owner), en su propia transacción;
  - métrica `email_rate_limited_total{tenant,window}`.
- **`detectAnomalies`** (FR-34), tarea del worker cada 5 min bajo advisory lock, sobre `created_at`:
  - **volumen:** aceptados en los últimos 60 min contra `max(multiplicador × p95 horario de los 14 días anteriores, mínimo horario)`, contando como cero las horas sin mensajes;
  - **rebotes:** `BOUNCED / (SENT + DELIVERED + BOUNCED + COMPLAINED)` de 24 h, con ≥ 200 de esos resultados;
  - **quejas:** `COMPLAINED / (DELIVERED + COMPLAINED)` de 7 días con ≥ 1 000 entregados, o 3 quejas en 24 h;
  - orden de prioridad: quejas, rebotes, volumen;
  - al pausar: auditoría `TENANT_PAUSED` (SYSTEM, con lo observado y el umbral), métrica `email_tenant_paused_total{reason}` y alerta `TENANT_PAUSED`.
- **Tareas periódicas del worker:** `PeriodicTasks` las ejecuta en hilos virtuales, cada una con su intervalo. El barrido de locks sigue en el bucle de la cola.
- **Alertas:** líneas de log ERROR con los campos `alert` y `alertSubject`, deduplicadas 15 min por instancia (owner). La lista completa de alertas llega con la parte 3.

**(rev. 2026-10-12) Comportamiento implementado en H7, parte 3:**
- **Health:**
  - indicador `queue` (todos los roles): `DEGRADED` con HTTP 200 por encima de `QUEUE_AGE_ALERT_SECONDS`; no cuenta los tenants retenidos;
  - indicador `provider` (worker): resultados de los últimos 5 min sin *ping*. `DOWN` con el breaker abierto o tras un error de configuración; `DEGRADED` si al menos la mitad de 5 o más llamadas fallaron de forma transitoria;
  - ninguno de los dos forma parte de las sondas;
  - se muestran los componentes, no sus detalles.
- **Métricas nuevas (AC-24.2):**
  - `email_messages{status}` (últimas 24 h);
  - `email_queue_depth`, `email_queue_oldest_seconds` y `email_queue_held{tenant}`;
  - `email_acceptance_seconds` y `email_provider_latency_seconds{provider,outcome}`;
  - `email_send_attempts` (intentos hasta `SENT` o `FAILED`);
  - `email_tenant_bounce_rate{tenant}` y `email_tenant_complaint_rate{tenant}`;
  - `email_purged_rows_total{data}`.
- **Alertas** (README, "Alertas"): `QUEUE_AGE`, `FAILED_RATE`, `TENANT_PAUSED`, `CIRCUIT_BREAKER_OPEN`, `LOST_LOCK`, `IDEMPOTENCY_CONFLICT`, `COMPLAINT_RATE`, `PROVIDER_CONFIGURATION` y `AUTH_FAILURE_SPIKE`.
- **Logs (AC-24.3):** el worker y los webhooks añaden `tenantSlug` y `messageId` al MDC. `EmailAddresses.masked` es la única forma admitida de mostrar una dirección; hoy ninguna línea de log la necesita.

**(rev. 2026-10-13) Fuente de la hora: el `Clock` de la aplicación o `now()` de PostgreSQL.**

Regla: una decisión usa una sola fuente en sus dos extremos. Si compara con columnas escritas por la base de datos, la decisión se toma en SQL con `now()`. Si su estado vive en memoria o lo escribe la aplicación, usa el bean `Clock`, que los tests pueden fijar.

| Fuente | Decisiones | Por qué |
|---|---|---|
| `now()` de PostgreSQL | Toma de la cola (`next_attempt_at <= now()`), locks y barrido, `first_attempt_at`, `sent_at`, `finalized_at`, purgas, anomalías, métricas de cola y tiempo aceptación → `SENT` (NFR-19), expiración de API keys | Los datos y el corte se miden con el mismo reloj, en la misma sentencia |
| `Clock` de la aplicación | Circuit breaker, `401` por IP, refresco de prefijos, deduplicación de alertas, ventanas del rate limit y su auditoría horaria, `Retry-After`, ventana de firma de webhooks, ventana de 23 h y próximo intento del worker | Estado en memoria o calculado por la aplicación; los tests lo fijan y lo avanzan sin depender de cuándo corren |

**Puntos donde se encuentran las dos fuentes**, con un desfase de *s* segundos entre la aplicación y PostgreSQL:

1. **Ventana de 23 h** (`first_attempt_at` lo escribe PostgreSQL y el worker lo compara con el `Clock`). La ventana efectiva queda en 23 h ± *s*. El límite que importa es el de idempotencia de Resend, 24 h desde el primer intento: queda 1 h de margen menos la duración de un intento (≤ `LOCK_TIMEOUT_SECONDS`, 60 s), así que es seguro mientras *s* < ~59 min. Con NTP, *s* es de milisegundos. La validación de arranque (AC-13.7) ya exige que backoff × 1,2 + intentos × lock < 23 h.
2. **Próximo intento** (`next_attempt_at` lo escribe la aplicación y la toma de la cola lo compara con `now()`). El reintento se adelanta o retrasa *s*. El backoff mínimo es de 60 s ±20 %, y un `Retry-After` adelantado unos segundos solo produce otro `429` transitorio. Seguro.
3. **Ventanas del rate limit** (`window_start` lo escribe la aplicación y la purga de contadores usa `now()` con un margen de 2 días). Seguro.
4. **Hora de respaldo de un webhook sin `created_at`** (guardada en `occurred_at` junto a `received_at` de la BD). Solo afecta al orden de eventos que el proveedor no fechó. Seguro.
5. **Corregido aquí:** el tiempo aceptación → `SENT` restaba `created_at` de la BD a la hora de la aplicación, y unos segundos de desfase sesgaban un objetivo de 5 s (NFR-19). Ahora `markSent` lo devuelve calculado en SQL (`sent_at - created_at`).

**Rate limit con varias instancias de la API.** Pasó de `now()` de PostgreSQL al `Clock`:
- **Por qué:** para que todas las decisiones de la aplicación usen una misma fuente que los tests pueden fijar. Antes los tests dependían del segundo del minuto real y usaban `sleep`.
- **Qué no cambia:** el contador sigue siendo una sola fila por tenant, ventana y periodo en PostgreSQL, compartida por todas las instancias (AC-21.2).
- **Qué cambia:** cada instancia elige la ventana con su reloj. Con un desfase de *s* entre instancias, solo las peticiones de los *s* segundos alrededor del cambio de minuto pueden contarse en la ventana vecina. Las ventanas fijas ya permiten hasta 2 × límite en 60 s seguidos que crucen el cambio de minuto, así que este efecto no cambia la garantía.
- **Requisito:** las instancias deben tener la hora sincronizada (NTP), como ya exige la validación de firmas de webhooks (±5 min).

Quedan fuera de la regla:
- `System.nanoTime` como reloj monótono: el limitador de llamadas al proveedor (inyectable) y el intervalo del bucle del worker;
- la hora de los UUID v7.

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

**(rev. 2026-10-12)** H7 completa la tabla con `PeriodicTasks`:
- **`purgeVariables`** cada 15 min y **`purgeIdempotencyKeys`** cada hora.
- **`purgeExpiredData`** diaria. Empieza 10 min después del arranque, para que despliegues frecuentes no la dejen sin ejecutar.
- **`emitQueueMetrics`** cada 30 s: gauges, y alertas `QUEUE_AGE` y `FAILED_RATE`.
- **`detectAnomalies`** cada 5 min.
- **`providerAlerts`** cada 30 s: breaker abierto más de 10 min.
- **Purgas en lotes:** 5 000 filas por lote, cada lote en su propia transacción con el advisory lock de la tarea.

**(rev. 2026-10-07)** En H4 existe `reclaimStuckMessages`; las demás tareas llegan con sus hitos. Con varias instancias de worker, cada tarea se ejecuta dentro de `pg_try_advisory_xact_lock(hashtext('<tarea>'))`: si otra instancia la tiene, se salta esa vuelta. **(rev. 2026-10)** Se elimina la tabla `scheduled_lock`. No se añade Quartz ni ShedLock.

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
| `AUTH_FAILURES_PER_MINUTE_PER_IP` | `30` | **(rev. 2026-10-12)** Fallos de autenticación por IP y minuto antes de limitar a esa IP las peticiones sin prefijo de key conocido (`08` §4) |
| `MAIL_PROVIDER` | `smtp` | `resend` \| `smtp` \| `noop`. **(rev. 2026-10-07)** `noop` (no envía nada) no arranca con `APP_ENV=production`; con `noop` no se crea ningún cliente SMTP |
| `RESEND_API_KEY` | — | Secreto; **obligatoria** con `MAIL_PROVIDER=resend` (rev. 2026-10-10). Nunca se registra en logs |
| `RESEND_API_URL` | `https://api.resend.com` | (rev. 2026-10-10) Base de la API; debe ser `https` salvo con `APP_ENV=local` (pruebas contra un stub) |
| `MAIL_WEBHOOK_SIGNING_SECRET` | — | Secreto del endpoint de webhooks (Svix); admite dos valores separados por coma durante una rotación. **(rev. 2026-10-11)** Obligatorio con `MAIL_PROVIDER=resend` y `APP_ROLE=api\|all`; cada valor `whsec_` + base64. Sin él, `/webhooks/resend` no existe |
| `WEBHOOK_TOLERANCE_SECONDS` | `300` | Ventana de timestamp (1–3600) |
| `SMTP_HOST` / `SMTP_PORT` | `mailpit` / `1025` | Modo desarrollo |
| `ALLOW_SMTP_IN_PRODUCTION` | `false` | Fail-fast si `production` + `smtp` |
| `PROVIDER_CONNECT_TIMEOUT_MS` / `PROVIDER_READ_TIMEOUT_MS` | `3000` / `10000` | Timeouts hacia el proveedor |
| `WORKER_CONCURRENCY` | `4` | Envíos simultáneos (y máximo por toma) |
| `WORKER_POLL_INTERVAL_MS` | `1000` | Sondeo de la cola |
| `WORKER_PROVIDER_RPS` | `8` | Límite de tasa por instancia hacia el proveedor (NFR-21) |
| `MAX_ATTEMPTS` | `6` | = nº de esperas + 1 (validado) |
| `RETRY_BACKOFF_SECONDS` | `60,300,900,3600,21600` | Backoff (ventana total < 23 h, validado) |
| `LOCK_TIMEOUT_SECONDS` | `60` | Expiración del lock por mensaje |
| `CIRCUIT_BREAKER_FAILURES` / `CIRCUIT_BREAKER_OPEN_SECONDS` | `5` / `60` | Circuit breaker (AC-13.9); ambos ≥ 1 |
| `DEFAULT_RATE_LIMIT_PER_MINUTE` | `60` | Por tenant |
| `DEFAULT_DAILY_QUOTA` | `1000` | Por tenant (fijar ~3× el volumen esperado) |
| `DEFAULT_RETENTION_DAYS` | `90` | Por tenant |
| `VARIABLES_RETENTION_DAYS` | `7` | Tras el estado final (AC-23.5). **(rev. 2026-10-12)** De 1 a 90 |
| `IDEMPOTENCY_RETENTION_HOURS` | `24` | AC-08.5. **(rev. 2026-10-12)** Al menos 24: una repetición dentro de la ventana debe encontrar su mensaje |
| `SENT_FINAL_AFTER_HOURS` | `72` | `SENT` sin eventos = final por tiempo |
| `MAX_REQUEST_BYTES` | `262144` | Tamaño de payload |
| `MAX_BATCH_ITEMS` / `MAX_BATCH_BYTES` | `100` / `1048576` | Endpoint de lote |
| `SEND_AT_MAX_DAYS` | `30` | FR-31 |
| `SUPPORTED_LOCALES` | `es-CR,en` | FR-37 |
| `SUPPRESSION_REJECT_MODE` | `accept` | `accept` (202 + `FAILED`) \| `reject` (422) |
| `SUPPRESSION_HASH_KEY` | — | Clave HMAC de `email_hash` (secreto, obligatoria). **(rev. 2026-10-07)** ≥ 32 caracteres; se exige en todos los roles salvo `migrate` |
| `ALLOWED_RECIPIENT_DOMAINS` | *(vacío)* | Allowlist de destino; **obligatoria** en `staging` |
| `QUEUE_AGE_ALERT_SECONDS` | `300` | Umbral de `DEGRADED` y de alerta. **(rev. 2026-10-12)** Al menos 30 |
| `ANOMALY_VOLUME_MULTIPLIER` / `ANOMALY_MIN_HOURLY` | `3` / `50` | FR-34. **(rev. 2026-10-12)** Valores por defecto de cada tenant. `anomalyOverrides` puede cambiar el multiplicador y subir el mínimo horario, nunca bajarlo |
| `BOUNCE_PAUSE_RATE` / `COMPLAINT_PAUSE_RATE` | `0.04` / `0.001` | FR-34; ajustables por tenant con `anomalyOverrides`. Ambos entre 0 y 1 |

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
├── templates/                    # (rev. 2026-10-06) plantillas como código: {tenant}/{templateKey}/ (§9)
└── scripts/
    ├── init-db-roles.sql         # crea email_owner, email_app, email_system (local)
    ├── seed-local.sh             # tenant demo + API keys (envío y plantillas) + plantilla de ejemplo
    ├── publish-template.sh       # (rev. 2026-10-06) publica una plantilla de templates/ (§9)
    └── smoke-test.sh             # envío de prueba de extremo a extremo contra Mailpit
```

## 9. Plantillas como código (rev. 2026-10-06)

Las plantillas de cada producto se versionan en este repositorio y se publican con un script a través de la API. Nadie edita HTML en producción a mano, y cada cambio pasa por revisión y por CI.

**Estructura** — `templates/{tenant}/{templateKey}/`:

| Archivo | Contenido |
|---|---|
| `template.json` | `key` (igual al nombre de la carpeta), `name`, `description`, `category`, `trackingEnabled`, `subject` (un asunto por locale), `variablesSchema` (subconjunto de `06` §3.3) y `previewVariables` (datos de ejemplo, ficticios, para la previsualización) |
| `{locale}.html` | Cuerpo HTML; uno por cada locale de `subject` (obligatorio) |
| `{locale}.txt` | Alternativa en texto plano (opcional) |

Ejemplo: `templates/demo/password-reset/` (`es-CR` y `en`).

**Publicar** — `EMAIL_SERVICE_TEMPLATES_KEY=esk_... [BASE_URL=...] scripts/publish-template.sh {tenant} {templateKey}`:

1. La key debe pertenecer al tenant y tener `templates:write` y `emails:read`; es la key de operador, nunca la de un producto (FR-33).
2. Si la plantilla no existe, la crea. Si existe con otra categoría, falla: cambiar la categoría exige una plantilla nueva (AC-36.4).
3. Compara cada locale con su versión publicada (asunto, HTML, texto y esquema) y omite los que no cambiaron. Repetir el script sin cambios no crea versiones.
4. Crea un borrador por cada locale cambiado y lo previsualiza con `previewVariables`, con las mismas reglas que un envío real (esquema, escapado, `allowedLinkHosts`).
5. Solo si todas las previsualizaciones pasan, publica. Con un solo locale cambiado usa la publicación individual; con varios, la publicación conjunta (ADR-0020): todos los locales en una transacción, así que se puede cambiar el conjunto `required` de una plantilla multilingüe. Si algo falla, no queda nada publicado: el script borra los borradores que creó y termina con error mostrando el `problem+json`.

Requiere `curl` y `jq` (`jq` está declarado en `mise.toml`). `scripts/seed-local.sh` publica todas las plantillas de `templates/{slug}/` del tenant de demo.

**En CI**, `TemplatesAsCodeTest` pasa cada `template.json` por las mismas comprobaciones que el servicio al guardar, publicar y previsualizar. Una plantilla rota falla el build antes de llegar al script.

**CI:** el workflow no usa mise ni `jq`. Java lo instala `setup-java`, ningún job ejecuta los scripts, y `TemplatesAsCodeTest` valida las plantillas en Java. `jq` solo hace falta para ejecutar los scripts en local (y en los runners de GitHub ya viene instalado).
