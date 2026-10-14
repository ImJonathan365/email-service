# Changelog del proyecto

Registro de avance y decisiones. Formato: hecho / pendiente / decisiones.

## 2026-10-13 — Tests dependientes de la hora: deterministas

- **Causa del fallo de CI** (PR #5, `AuthIpLimitTest:77`):
  - El limitador de `401` por IP del contexto compartido conservaba sus contadores entre tests. Cada test elegía la IP al azar entre 250 y adelantaba el reloj al minuto siguiente del tiempo real, así que dos tests seguidos podían caer en la misma IP y la misma ventana.
  - El segundo encontraba la IP ya saturada y recibía `429` en su primer intento, donde esperaba `401`.
  - Reproducido de forma determinista fijando la misma IP en todos los tests: 5 de 6 fallan en esa línea con el mismo mensaje. Un cambio de minuto a mitad del test produce otro fallo (en la línea 97, no en la 77), así que no era la causa.
- **Arreglo del test:** cada test construye su propio autenticador, ventanas, conjunto de prefijos y alertas sobre un reloj fijo a mitad de minuto y de hora, que avanza explícitamente. Las IPs salen de un contador y no se repiten. Ahora comprueba además el `Retry-After` exacto y la auditoría de la hora siguiente.
- **Producción:** lo que leía la hora del sistema en decisiones de ventana pasa al `Clock`:
  - `MessageProcessor`: ventana de 23 h y próximo intento;
  - `ResendEmailSender`: `Retry-After` con fecha HTTP;
  - `WebhookController`: hora de respaldo;
  - `AcceptanceRateLimiter`: ventanas y `Retry-After`;
  - `RateLimitAudit`: deduplicación horaria por la hora del reloj guardada en la fila.
- **Otros tests auditados y corregidos:**
  - `RateLimitTest`: sin `Thread.sleep` ni hora real; reloj fijo, `Retry-After` exactos y cambio de ventana provocado;
  - `ResendWorkerTest`: reloj congelado, porque el breaker de 59 s frente a 60 s dependía de la duración del test;
  - `ObservabilityTest`: métricas y alertas propias, sin deduplicación compartida;
  - `WebhookTest`: ventana ±600 s (la frontera exacta está en el test unitario con reloj fijo);
  - `ProviderRateLimiterTest`: el test de concurrencia ya no mide `System.nanoTime`, asigna huecos con el tiempo detenido;
  - `ResendEmailSenderTest`: `Retry-After` exacto con reloj fijo.
- **`MutableClock.fixAt`** permite fijar el reloj compartido; `reset()` lo devuelve a la hora del sistema.
- **Fuente de la hora** (docs/05): una decisión usa una sola fuente en sus dos extremos.
  - Los puntos donde se encuentran el `Clock` y `now()` de PostgreSQL están analizados frente a un desfase de segundos. La ventana de 23 h sigue por debajo de las 24 h de idempotencia de Resend con ~59 min de margen.
  - El tiempo aceptación → `SENT` (NFR-19) se calcula ahora en SQL (`sent_at - created_at`), porque mezclar fuentes sesgaba un objetivo de 5 s.
- **Webhooks:** `SvixWebhookVerifierTest` comprueba la frontera exacta en los dos sentidos (300 s aceptado, 301 s rechazado, en pasado y en futuro). El caso aceptado en futuro faltaba.

## 2026-10-12 — H7, parte 3: mantenimiento y observabilidad (H7 completo)

- **Hecho:**
  - **Purgas (FR-23)** en lotes idempotentes con advisory lock, como tareas del worker:
    - variables a los 7 días de un estado terminal o final por tiempo;
    - idempotencia a las 24 h;
    - mensajes y eventos según la retención del tenant; los tags se van en cascada;
    - `rendered_*` y huérfanos a los 30 días, contadores a los 2 días;
    - `audit_log` a los 365 días mediante una función `SECURITY DEFINER` (V5);
    - nunca `QUEUED`/`SENDING` ni supresiones.
  - **Borrado por titular (FR-29):**
    - en todos los tenants y en una transacción: mensajes, eventos y tags en cascada, `cc`/`bcc` limpiados, supresiones con solo el hash;
    - auditado con hash y referencia; idempotente;
    - 2,2 s con el dataset de referencia (AC-29.3, límite 60 s).
  - **Health:** indicadores `queue` (`DEGRADED` con HTTP 200) y `provider` (últimos 5 min, sin *ping*).
  - **Métricas de AC-24.2** que faltaban: estados, cola, retenidos, latencias de aceptación y del proveedor, intentos, tasas de rebote y queja por tenant, filas purgadas.
  - **Alertas de NFR-11** como líneas `ERROR` con el campo `alert`, más `ops/prometheus/alerts.yml` de referencia. Los valores y qué hacer con cada uno están en el README.
  - **Logs** con `tenantSlug` y `messageId` en el worker y los webhooks. Un test verifica que un envío completo no deja direcciones ni variables en los logs.
  - **Configuración nueva:** `VARIABLES_RETENTION_DAYS`, `IDEMPOTENCY_RETENTION_HOURS`, `SENT_FINAL_AFTER_HOURS` y `QUEUE_AGE_ALERT_SECONDS`, validadas.
  - **Test de valores por defecto:** fuera del perfil de test, el límite de `401` por IP es 30 (petición del owner).
  - **Reloj hacia atrás:** el conjunto de prefijos y la deduplicación de alertas lo toleran.
- **Propuestas pendientes de confirmar por el owner:**
  - `DELIVERED` cuenta como final por tiempo igual que `SENT` (AC-23.9);
  - `FAILED_RATE` exige al menos 20 mensajes en la hora;
  - alertas añadidas: `PROVIDER_CONFIGURATION` y `AUTH_FAILURE_SPIKE`.

## 2026-10-12 — H7, parte 2: protección

- **Hecho:**
  - **Rate limit y cuota por tenant** (FR-21):
    - contador en PostgreSQL en la transacción del `INSERT`; minuto UTC y día del calendario del tenant;
    - `429 RATE_LIMITED` con `Retry-After`; sin consumo en rechazos ni repeticiones idempotentes; un `FAILED/SUPPRESSED` sí consume;
    - límites aplicados sin redesplegar;
    - auditoría agregada por tenant, ventana y hora.
  - **`401` repetidos por IP** (docs/08 §4, esquema del owner):
    - prefijos de keys activas en memoria, refrescados cada 60 s y al emitir o revocar;
    - por encima de `AUTH_FAILURES_PER_MINUTE_PER_IP` (30), `429` sin consulta a la BD solo para keys ausentes, mal formadas o de prefijo desconocido;
    - un prefijo conocido sigue la verificación completa, así que la revocación es inmediata;
    - auditoría `AUTHENTICATION_FAILURES_BY_IP` por IP y hora; métrica `email_auth_key_lookups_total` para verificar que no hubo consulta.
  - **Anomalías y pausa automática** (FR-34):
    - tarea `detectAnomalies` cada 5 min (volumen contra el p95 horario de 14 días, rebotes, quejas), con los mínimos de volumen como suelo;
    - `anomalyOverrides` por tenant en el `PATCH` de administración;
    - pausa auditada, con métrica y alerta.
  - **Alertas estructuradas** (`alert`, `alertSubject`) deduplicadas 15 min. La pausa por dominio no verificado de H5 usa la misma alerta `TENANT_PAUSED`.
  - **Tareas periódicas del worker** (`PeriodicTasks`), base de las purgas de la parte 3.
  - **Test del trigger de tags** con un `INSERT` multifila y varias sentencias en una transacción, más el borrado en cascada (pedido por el owner para H8).
- **Pendiente (parte 3):** purgas, borrado por titular, indicadores `queue`/`provider`, métricas y alertas restantes y documentación de las alertas en el README.

## 2026-10-12 — H7, parte 1: consultas

- **Hecho:**
  - **`GET /v1/emails/{id}`** (FR-19):
    - estado, plantilla y versión, `locale`, destinatarios, intentos, `providerMessageId`, marcas de tiempo, `lastError` y el historial de eventos;
    - `404` para otro tenant.
  - **Contenido renderizado:** con `storeRenderedContent`, el worker guarda una segunda renderización con las variables `x-sensitive` como `[REDACTED]` (AC-19.3).
  - **`GET /v1/emails`** (FR-20): filtros `status`, `templateKey`, `to`, `tag`, `createdAfter` y `createdBefore`, con cursor opaco.
  - **AC-20.2 medido:** `./gradlew benchmarkOperations`, con el dataset de referencia definido por el owner (4 M mensajes, 2,8 M en un tenant y 400 k en cada uno de los otros tres), a través de la API.
    - p95 entre 6 y 13 ms en los 14 casos, incluidos un tag inexistente y una dirección sin mensajes.
    - Hallazgo: con RLS, PostgreSQL solo usa un índice para condiciones *leakproof*. `lower()` y `@>` no lo son, y el filtro `to` hacía un recorrido secuencial (p95 990 ms).
    - V4 lo resuelve con la columna generada `to_email_lower`, la tabla `email_message_tag` (llenada por trigger y con RLS `FORCE`) e índices con desempate por `id`.
  - **Tests nuevos:**
    - un test de catálogo exige RLS `FORCE` en toda tabla con `tenant_id`;
    - el test del rol `migrate` cuenta los archivos de migración en lugar de un número fijo.
  - **Errores de parámetros:** un parámetro de query mal formado responde `422`. Un segmento de ruta mal formado sigue respondiendo `404`.
  - **`WebhookDatabaseFailureTest`** queda `@Isolated`.
  - **docs/02 AC-20.2:** corregida la definición del dataset.

## 2026-10-11 — Tests: un solo contexto de Spring compartido

- **Hecho:**
  - **Contextos de Spring Test:** de 7 a 4 creados; al final de la suite solo queda abierto 1 (log de caché de Spring Test).
    - `WorkerTest`, `ResendWorkerTest` y `WebhookDatabaseFailureTest` usan ahora el contexto común. Los dobles de test compartidos (`SharedTestBeans`: proveedor espía y reloj controlable) se programan y se restauran; ya no se declaran beans por clase.
    - El `503` se prueba con errores reales de BD (privilegio revocado, tabla renombrada), sin `@MockitoSpyBean`.
    - Siguen aparte, con `@DirtiesContext`, los que cambian configuración global: `SendingPolicyConfigTest` y los arranques con `APP_ROLE=worker` y `APP_ENV=production`.
  - **Pools en test:** 5 de tenant y 3 de sistema. Producción sigue con 10 y 4.
  - **Conexiones:** el contenedor de test vuelve a las 100 por defecto.
- **Mediciones:** la suite tarda lo mismo (46 s antes; 42–47 s después). Lo que baja son las conexiones abiertas a la vez.

## 2026-10-11 — H6: eventos y supresiones

- **Hecho:**
  - **`POST /webhooks/{provider}`** (FR-16):
    - `WebhookVerifier` en `provider`, con `SvixWebhookVerifier` propio: cuerpo crudo, tiempo constante, uno o dos secretos, ventana ±5 min, verificado con el ejemplo publicado por Svix;
    - respuestas `204`, `401`, `400`, `404`, `413` y `503` solo ante un error de BD;
    - dedupe por `svix-id`;
    - los rechazos se cuentan y se registran como mucho una vez por IP y minuto.
  - **Eventos** (FR-17):
    - mapeo completo de docs/02 §2.1 y estados por precedencia, incluidos `DELIVERED → BOUNCED/COMPLAINED`, `email.failed` y `email.suppressed`;
    - payload minimizado por lista blanca;
    - huérfanos guardados con `204`.
  - **Supresiones** (FR-10, ADR-0012):
    - globales desde rebotes, quejas y `email.suppressed`, en la transacción del evento e independientes del estado;
    - espejo de `suppression.added/removed`.
  - **API de supresiones** (FR-18) y `GET/DELETE /admin/v1/suppressions`, con cursor opaco.
  - **ArchUnit:** sin ciclos entre módulos de primer nivel; `events` no depende de `sending`; `sending` y `events` solo usan los puertos de `provider`.
  - **README:** registro del endpoint en Resend, rotación del secreto y prueba temporal con un túnel que solo expone `/webhooks/*`.
- **Decisiones del owner (2026-10-11):**
  - **Eventos que llegan con el mensaje en `QUEUED`/`SENDING`:** se guardan sin tocar el estado y el worker los aplica tras su cierre a `SENT`.
  - **Mensaje que nosotros cerramos en `FAILED` y el proveedor sí procesó:** sigue `FAILED`, con WARN y `email_event_after_failure_total`.
  - **Rebote o queja con `cc`/`bcc`:** no se suprime el `to`; se aplica el estado y la supresión llega por `suppression.added`.
  - **Correlación:** primero la etiqueta `message_id`, después `data.email_id`; si apuntan a mensajes distintos, el evento queda huérfano.
  - **`POST` repetido → `200`; `DELETE` inexistente → `404`.**
  - **`EmailAddresses` y `SuppressionHashes` pasan a `events`.**
- **Otros cambios:**
  - el gestor de transacciones de tenant pasa de `common` a `tenancy`, para romper un ciclo que ya existía;
  - el contenedor de test admite 300 conexiones;
  - `.gitignore` ignora `.env.*` (salvo `.env.example`).
- **Pendiente:**
  - purga de huérfanos a los 30 días (tarea de mantenimiento);
  - historial de eventos en `GET /v1/emails/{id}` (H7);
  - registrar el endpoint real cuando exista la cuenta de staging (owner).

## 2026-10-10 — H5: proveedor real (Resend)

- **Hecho:**
  - **`ResendEmailSender`** (cliente HTTP del JDK, sin SDK):
    - `Idempotency-Key = message.id` en cada intento;
    - etiquetas `message_id=<uuid>` y `{name: tag, value: "true"}`;
    - del error solo se guarda su `name`;
    - `RESEND_API_KEY` obligatoria con `MAIL_PROVIDER=resend`;
    - `RESEND_API_URL` debe ser `https` fuera de `local`.
  - **Clasificación** (docs/05 §4), con un test por código:
    - `2xx` → `SENT`;
    - `400`/`422` → permanente;
    - `409 invalid_idempotent_request` → `PROVIDER_IDEMPOTENCY_CONFLICT`;
    - los otros `409`, `429` (con `Retry-After`), `5xx`, timeout y red → transitorios;
    - nuevas clases **Configuración** y **Configuración del tenant**.
  - **`supportsIdempotency()`** en el puerto `EmailSender`: aviso al arrancar si es `false` (SMTP).
  - **Protección del proveedor:**
    - `WORKER_PROVIDER_RPS` por instancia, sin ráfaga;
    - circuit breaker propio (AC-13.9): 5 fallos → 60 s abierto → un único envío de prueba;
    - abierto, no se toman mensajes, y los ya tomados vuelven sin intento.
  - **Errores de configuración:**
    - **key** (`401`, `403` de la key, `404`/`405`): abren el breaker de inmediato y no consumen intento; métrica `email_provider_config_error_total{error}`;
    - **dominio no verificado** (`403 validation_error`): pausa solo ese tenant (`pause_reason = PROVIDER_DOMAIN`, migración V3, auditoría `TENANT_PAUSED`).
  - **`POST /admin/v1/tenants/{slug}/resume-sending`** (adelantado de H7):
    - idempotente;
    - auditado como `TENANT_RESUMED` con la razón anterior y la nota.
  - **Tests con WireMock,** sin red real ni correos:
    - respuesta perdida (NFR-04): misma clave y mismo cuerpo, un solo `SENT`;
    - limitador;
    - breaker;
    - key revocada con 20 mensajes;
    - dominio no verificado con otro tenant que sigue enviando.
  - **README:** pasos de staging (equipo de Resend, dominio con SPF/DKIM/DMARC, key, variables) y prueba manual.
- **Decisiones del owner (2026-10-10):**
  - cliente HTTP del JDK;
  - solo `invalid_idempotent_request` es permanente;
  - `RESEND_API_URL` configurable;
  - etiquetas `message_id`;
  - errores de configuración sin consumir intentos, aunque la ventana de 23 h sigue aplicando (`RETRY_WINDOW_EXCEEDED`);
  - pausa por tenant ante un dominio no verificado.
- **Pendiente:** webhooks de Resend (H6). La cuenta y el dominio de staging los prepara el owner.

## 2026-10-10 — NFR-09: caché de Gradle en el build de Docker

- **Hecho:**
  - `Dockerfile`: *cache mount* de BuildKit para `/root/.gradle` en los pasos de dependencias y `bootJar`. La imagen final no cambia (563 MB).
  - NFR-09 ajustado: ≤ 90 s con la caché de capas o de Gradle disponible; ≤ 150 s en un clon totalmente en frío (dominado por la descarga de dependencias).
- **Mediciones:**
  - H4: 13 s con la caché de capas; ≈ 100 s en frío.
  - Ahora: 39 s con la caché de capas invalidada y el *cache mount* disponible.
  - `--no-cache` también descarta el *cache mount*, así que mide el caso totalmente en frío. Con la red lenta del día tardó 717–1150 s.

## 2026-10-08 — H4: envío de extremo a extremo

- **Hecho:**
  - **Configuración validada** de envío, worker y reintentos (AC-13.3, AC-13.7, AC-09.4, AC-15.3).
  - **Puerto `EmailSender`** con `SmtpEmailSender` (Mailpit) y `NoopEmailSender`. El cliente SMTP solo existe con `MAIL_PROVIDER=smtp`.
  - **Roles:** API y Swagger solo con `APP_ROLE=api|all`; el worker solo expone Actuator.
  - **`POST /v1/emails`:**
    - idempotencia antes de todo y a prueba de concurrencia (AC-08.4);
    - validaciones de FR-07 y FR-09;
    - versión fijada por idioma con caída al del tenant;
    - regla de URL y render de prueba;
    - supresión global y por tenant;
    - punto de inserción del rate limit (H7).
  - **Worker:**
    - toma corta por prioridad con `attempts++` y `lock_token`;
    - cierre con *fencing*;
    - backoff con jitter y `Retry-After`;
    - barrido de locks vencidos bajo advisory lock;
    - purga de `x-sensitive` al pasar a `SENT` o a un estado terminal;
    - `email_time_to_sent_seconds` por categoría.
  - **`scripts/smoke-test.sh`:** envío de extremo a extremo con verificación en la API de Mailpit.
- **Decisiones del owner (2026-10-07):**
  - rate limit en H7, con el punto de inserción ya en la transacción;
  - circuit breaker y `WORKER_PROVIDER_RPS` en H5;
  - `spring-boot-starter-mail` confinado a `provider`;
  - `sendAt` → `422` hasta H8;
  - `GET /v1/emails/{id}` en H7;
  - purga de `x-sensitive` en el `UPDATE` con *fencing* y métrica de tiempo hasta `SENT`.
- **Decisiones del agente, a revisar:**
  - `MAIL_PROVIDER=noop` no arranca en `production`;
  - el campo opcional `from` (AC-09.2) se añade a `07` §3, porque no figuraba en la tabla;
  - un mensaje creado ya en `FAILED`/`SUPPRESSED` no guarda variables;
  - el intento se descuenta si el tenant queda retenido entre la toma y el envío;
  - los endpoints de plantillas tienen un tope de cuerpo de 1 MiB.
- **Medido (NFR-09), clon limpio con `cp .env.example .env && docker compose up --build`:**
  - con la caché de capas de Docker, 13 s hasta readiness;
  - sin caché, 89 s de build (casi todo descargas de Gradle) + 11 s de arranque con un volumen nuevo, ≈ 100 s;
  - seed y smoke test pasan.
  - **Pendiente de decisión del owner:** el arranque totalmente en frío supera por ≈ 10 s el objetivo de 90 s.

## 2026-10-06 (noche) — H3: plantillas

- **Hecho:**
  - **Motor Handlebars.java 4.5.5 endurecido** (ADR-0011):
    - un escáner en lista blanca revisa cada `{{…}}` antes de compilar;
    - un registro de helpers cerrado y un cargador que no carga nada;
    - solo `MapValueResolver`;
    - el render vuelve a escanear la fuente;
    - escapado por contexto;
    - `formatDate`, `formatNumber` y `formatMoney` con el locale y la zona horaria. Nashorn queda excluido.
  - **Linter HTML con jsoup** (AC-04.7, AC-04.8), en modo XML para no perder etiquetas mal ubicadas.
  - **Validador propio** del subconjunto de `variablesSchema` (`06` §3.3), sin dependencia de JSON Schema.
  - **API `/v1/templates`:**
    - crear, listar, detalle con contenido;
    - borradores por locale (crear, editar, borrar);
    - publicación inmutable que archiva la versión anterior del mismo locale;
    - previsualización con la regla de URL de AC-07.8.
    - Aislamiento por tenant en cada endpoint y publicación auditada.
  - **Plantillas como código** (`05` §9): `templates/{tenant}/{templateKey}/`, `scripts/publish-template.sh`, `demo/password-reset` en `es-CR` y `en`, publicadas por `seed-local.sh`, y validadas en CI por `TemplatesAsCodeTest`.
- **Decisiones del owner (2026-10-06):**
  - `DELETE` de borradores;
  - `locale` y la regla de URL en la previsualización;
  - `previewVariables` en `template.json`;
  - el script omite los locales sin cambios y el detalle incluye el contenido;
  - `jq`, declarado en `mise.toml`.
- **Ajuste de documentación:** el ejemplo de AC-37.5 en `en` es `CRC1,500.00` (salida congelada de la JDK 25).
- **Decisión del owner (2026-10-07):** publicación conjunta (ADR-0020, AC-37.8): `POST /v1/templates/{key}/publish` publica varios locales en una transacción, todo o nada. AC-37.6 se valida sobre el conjunto resultante. `publish-template.sh` la usa cuando cambian varios locales. Con esto desaparece la limitación de no poder cambiar `required`.
- **Fuera de H3** (dependen del envío, H4): fijar la versión en el mensaje, la caída al locale del tenant al enviar (AC-05.3/05.4, 37.2–37.4) y la prioridad (AC-36.2/36.3).

## 2026-10-06 (tarde) — H2: tenancy

- **Hecho:**
  - Errores `application/problem+json` con el catálogo cerrado de `07` §11; `X-Request-Id` en toda respuesta; IP de origen con `TRUSTED_PROXIES`.
  - Administración (FR-02): `X-Admin-Key` contra `ADMIN_API_KEYS`; crear, listar, actualizar, suspender y reactivar tenants; valores por defecto de AC-02.5.
  - API keys (FR-03, FR-33): emisión `esk_{env}_{prefix}_{secret}` (solo prefijo y SHA-256 en la BD), listado sin secretos, revocación idempotente, ámbitos y CIDR fijados al emitir.
  - Autenticación de `/v1/**` (FR-01, FR-33): comparación en tiempo constante, `401` uniforme, `403` por ámbito, CIDR o tenant suspendido, `last_used_at` por minuto y contexto de tenant (RLS) para el resto de la petición. `@RequiresScope` es obligatorio en cada handler `/v1`.
  - Auditoría (FR-22) en la misma transacción que la acción.
  - OpenAPI en dos grupos (AC-25.3): `contracts/email-service.openapi.json` (productos) y `contracts/email-service-admin.openapi.json`.
  - `APP_ROLE=migrate` arranca un contexto mínimo (solo configuración y Flyway).
  - `scripts/seed-local.sh`: tenant `demo` y dos keys.
- **Medido: coste de RLS (ADR-0008)** con `./gradlew benchmarkRls`. PostgreSQL 18 en Docker local, 100 000 mensajes en 20 tenants, 5 000 transacciones por caso, dos ejecuciones. El plan sigue usando `ix_msg_tenant_created`; la política queda como un único `One-Time Filter`.

  | Consulta (p50, µs por transacción) | `email_app` + `set_config` + RLS | `email_system` + `set_config` | `email_system` solo consulta |
  |---|---|---|---|
  | Últimos 20 del tenant | 144–158 | 133–137 | 91–92 |
  | Por tenant + id | 127–128 | 119–122 | 82–91 |

  - El ida y vuelta extra de `set_config` cuesta ~30–45 µs (+40–45 % sobre consultas tan pequeñas).
  - La evaluación de la política sola cuesta +5–15 % en p50.
  - p95/p99 son ruidosos en este entorno.
  - En total, +40–65 µs por transacción: ~3–5 % del camino de aceptación medido (1,38 ms, `04` §2).
- **Decisiones del owner (2026-10-06, tras H2):**
  - ADR-0019 (Accepted): sin caché de API keys (revocación en la siguiente petición, AC-03.4 revisado); el criterio de coste de RLS de ADR-0008 se mide sobre la transacción completa y **no** está activado; no se optimiza `set_config`.
  - Catálogo de errores: se añaden `405 METHOD_NOT_ALLOWED` y `415 UNSUPPORTED_MEDIA_TYPE`.
  - Los `401` con keys desconocidas se cuentan como métrica (`email_auth_unauthenticated_total`), sin escribir en la BD; auditoría agregada y rate limit de `401` en H7.
  - Dos contratos OpenAPI (AC-25.2 revisado).
  - `ADMIN_API_KEYS` solo para `APP_ROLE=api|all`, ≥ 32 caracteres (`05` §6).
  - Ramas: `main` siempre desplegable, `dev` de integración, `feat/*` por hito (`AGENTS.md`).

## 2026-10-06 — H1: esquema, roles y RLS

- **Decisiones (owner, 2026-10-06):**
  - `BYPASSRLS` de `email_system` se concede en `scripts/init-db-roles.sql` (requiere superusuario), no en `V2__rls.sql`. Si el PostgreSQL gestionado no lo permite, se usará la alternativa de ADR-0008 (política `TO email_system USING (true)`).
  - `tenant` pasa a tener RLS con `FORCE` (política `id = app.tenant_id`). `email_app` solo tiene `SELECT` sobre su propia fila; el alta, la modificación y el borrado de tenants solo se hacen con `email_system`.
  - `email_system` tiene `SELECT/INSERT/UPDATE/DELETE` sobre todas las tablas, salvo `UPDATE`/`DELETE` en `audit_log`.
  - En `suppression`, `email_app` no puede leer `source_tenant_id` ni `source_message_id` (permisos por columna): un tenant no sabe qué otro tenant originó una supresión global.
  - Las políticas usan `nullif(current_setting('app.tenant_id', true), '')::uuid`: en una conexión reutilizada, tras una transacción con `set_config(..., true)`, la variable vale `''` y no `NULL`, y `''::uuid` daría error en lugar de 0 filas.
- **Hecho:**
  - `docs/06` §1 y §5 actualizados con lo anterior.
  - H1 (esqueleto):
    - `APP_ROLE` con validación de configuración al arrancar;
    - los dos `DataSource` y `set_config` por transacción de tenant;
    - V1/V2 y el script de roles;
    - health (liveness/readiness), Swagger y `contracts/email-service.openapi.json` con test de diferencias;
    - ArchUnit, Docker Compose y CI (build, tests, gitleaks).
  - **RSS medido (ADR-0009):** 300 MiB (306 896 KiB, ≈ 314 MB) en reposo con `-Xmx256m`, `APP_ROLE=all`, imagen `eclipse-temurin:25-jre`. Cumple el criterio de ≤ 350 MB.
- **Pendiente:** el resto de validaciones de configuración y los indicadores `provider`/`queue` llegan con el hito que los usa (H2–H7).

## 2026-10-05 (tarde) — Respuestas del owner: H0 cerrado

- **Decisiones:**
  - ADR-0008 a 0014, 0016 y 0017 → Accepted; ADR-0002 a 0007 → Superseded.
  - ADR-0015 (adjuntos) → **Rejected**: ningún producto envía comprobantes. FR-30 → Won't; `email_attachment` no se crea; UC-13 DEPRECATED.
  - FR-31 `sendAt` → Should (H8).
  - Nuevo ADR-0018 (Accepted) y FR-37: plantillas por idioma, `es-CR` por defecto y `en` disponible.
  - AC-09.5: `fromName` por petición (Colmena envía en nombre de cada negocio).
  - AC-10.1: `suppressionReason` en la respuesta, para que el producto use una dirección alternativa.
  - NFR-20: solo Costa Rica (GDPR y CAN-SPAM no aplican); Colmena como encargada de los datos de los clientes de sus negocios (productos, deudas).
  - Transferencia internacional: pendiente, no bloquea el desarrollo.
  - ADR-0009: confirmado que todos los servicios son Spring Boot y los frontends TypeScript.
- **Pendiente (no bloquea H1):**
  - hosting (CIDR, `BYPASSRLS`);
  - textos legales de privacidad de cada producto y términos de Colmena;
  - consulta legal sobre la normativa de cobro para los recordatorios de deuda.
- **Siguiente:** Fase 0, hito H1.

## 2026-10-05 — Auditoría independiente y revisión de la documentación

- **Hecho:**
  - Auditoría completa de `/docs`, los ADR y `AGENTS.md`: 4 hallazgos bloqueantes, 17 graves, 22 menores y 3 mejoras (`auditoria/2026-10-05-informe-auditoria.md`).
  - Versiones, precios, límites de proveedores y normativa verificados con fuentes citadas.
  - Medido el camino de aceptación (REST/JSON frente a binario, keep-alive, lote) y la cola en PostgreSQL.
  - Documentos 01–10, README y `AGENTS.md` reescritos con las correcciones (marcadas **(rev. 2026-10)**).
  - Documento nuevo `11-correo-masivo.md`.
  - ADR nuevos 0008–0017 (estado `Proposed`).
  - ADR 0002–0007: solo cambia su línea de *Estado* ("en proceso de supersesión").
- **Decisiones propuestas** (detalle en `auditoria/resumen-de-decisiones.md`):
  - RLS desde el MVP con FK compuestas (ADR-0008, supersede 0007).
  - Stack ratificado con justificación medible (ADR-0009, supersede 0006).
  - Cola con *fencing*, prioridad e idempotencia hacia el proveedor; semántica al menos una vez (ADR-0010, supersede 0002).
  - Plantillas endurecidas (ADR-0011, supersede 0005).
  - Supresión global/tenant (ADR-0012).
  - Correo masivo fuera por ahora, con disparador (ADR-0013).
  - REST + keep-alive + lote (ADR-0014).
  - Adjuntos acotados en el MVP (ADR-0015).
  - Resend ratificado con datos verificados (ADR-0016, supersede 0003).
  - API keys con ámbitos y CIDR (ADR-0017, supersede 0004).
- **Requisitos:**
  - Nuevos: FR-29 a FR-36 y FR-40 a FR-54 (estos últimos, Won't, en `11`); NFR-19 a NFR-21.
  - FR-28 pasa a DEPRECATED (dividido en FR-30/31/32).
  - FR-23 sube de Should a Must.
  - Ningún ID renumerado.
- **Cambios de enums antes de la primera migración** (no hay datos):
  - `email_event.type`: `BOUNCED_HARD` → `BOUNCED`, `BOUNCED_SOFT` → `DELIVERY_DELAYED`; nuevos `SENT`, `SUPPRESSED`, `OTHER`.
  - `failure_code`: `TENANT_SUSPENDED` y `TEMPLATE_NOT_PUBLISHED` pasan a DEPRECATED; nuevos `PROVIDER_FAILED`, `PROVIDER_SUPPRESSED`, `PROVIDER_IDEMPOTENCY_CONFLICT`, `RETRY_WINDOW_EXCEEDED` y `LOCK_EXPIRED`.
  - Tabla `scheduled_lock` eliminada del diseño.
- **Contrato con los productos:** cambios y notas de migración en `07` §12.1. Como no hay ninguna versión en producción, se aplican sobre `/v1` (NFR-18).
- **Pendiente:**
  - H0: cerrado en la entrada de la tarde (arriba).

## 2026-09-20 — Documentación base

- **Hecho:** `/docs` completa (visión y alcance, requisitos funcionales con criterios de aceptación, no funcionales, stack, arquitectura, modelo de datos, API interna, seguridad, roadmap, guía para agentes de IA) y ADR-0001 a ADR-0007.
- **Decisiones cerradas:**
  - un solo artefacto desplegable;
  - cola en PostgreSQL con SKIP LOCKED;
  - Resend como proveedor del MVP detrás de `EmailSender`;
  - secretos fuera de la base de datos;
  - plantillas versionadas inmutables y logic-less;
  - Java 25 + Spring Boot 4.1 + PostgreSQL 18;
  - multi-tenancy por columna `tenant_id`.
- **Pendiente:** iniciar la Fase 0 (hito H1: esqueleto + Docker Compose + CI).
