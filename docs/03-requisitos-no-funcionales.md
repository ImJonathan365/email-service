# 03 — Requisitos no funcionales

> Revisión 2026-10-05. Las entradas nuevas o cambiadas llevan **(rev. 2026-10)**. Los IDs no se renumeran.

Cada NFR es verificable. **(rev. 2026-10)** Entorno de referencia para las cifras: una instancia de app de 1 vCPU / 1 GB de RAM y un PostgreSQL gestionado de 1 vCPU / 1 GB en la misma región. El entorno anterior (BD de 2 vCPU / 4 GB) era incompatible con NFR-16: cuesta 60,90 USD/mes en DigitalOcean, frente a 15,15 USD el de 1 vCPU / 1 GB ([precios consultados el 2026-10-05](https://www.digitalocean.com/pricing/managed-databases)).

| ID | Categoría | Requisito | Prioridad |
|---|---|---|---|
| NFR-01 | Rendimiento | Latencia de aceptación | Must |
| NFR-02 | Rendimiento | Throughput de envío | Should |
| NFR-03 | Fiabilidad | Durabilidad de los mensajes aceptados | Must |
| NFR-04 | Fiabilidad | Al menos una vez, con deduplicación en el proveedor (rev. 2026-10) | Must |
| NFR-05 | Disponibilidad | La API sobrevive a la caída del proveedor | Must |
| NFR-06 | Escalabilidad | Escalado horizontal sin cambios de diseño | Should |
| NFR-07 | Seguridad | Ver `08-seguridad.md` (resumen aquí) | Must |
| NFR-08 | Aislamiento | Multi-tenancy estricta con RLS desde el MVP (rev. 2026-10) | Must |
| NFR-09 | Operabilidad | Arranque con un comando y despliegue simple | Must |
| NFR-10 | Simplicidad | Presupuesto de complejidad | Must |
| NFR-11 | Observabilidad | Métricas, logs y trazas correlacionadas | Must |
| NFR-12 | Mantenibilidad | Estructura, estilo y pruebas | Must |
| NFR-13 | Portabilidad | Local, VPS o contenedor gestionado | Should |
| NFR-14 | Datos | Retención, respaldo y restauración | Should |
| NFR-15 | Privacidad | Minimización de datos personales | Must |
| NFR-16 | Costo | Operación económica | Should |
| NFR-17 | Manejo de errores | Errores de API uniformes | Must |
| NFR-18 | Compatibilidad | Evolución del contrato sin romper clientes | Must |
| NFR-19 | Rendimiento | Tiempo hasta el envío (nuevo) | Must |
| NFR-20 | Cumplimiento | Ley 8968 y obligaciones de privacidad (nuevo) | Must |
| NFR-21 | Fiabilidad | Límites y reputación del proveedor (nuevo) | Must |

---

### NFR-01 — Latencia de aceptación (Must)

- `POST /v1/emails`: **p95 ≤ 150 ms**, p99 ≤ 400 ms, medido en el servidor (sin la red del cliente) y con el proveedor **caído** (no debe influir).
- **(rev. 2026-10)** `POST /v1/emails/batch` con 100 elementos: p95 ≤ 500 ms.
- Consultas (`GET /v1/emails/{id}`): p95 ≤ 100 ms.
- Listados paginados: p95 ≤ 500 ms con el dataset de referencia de AC-20.2.
- Webhooks entrantes: p95 ≤ 300 ms, siempre < 2 s.
- **(rev. 2026-10)** Referencia medida (ADR-0014): el camino de aceptación completo (parse, auth, validación, 2 UPSERT de rate limit, INSERT idempotente y COMMIT) tarda 1,38 ms p50 / 2,33 ms p95 en el servidor contra PostgreSQL local; la BD aporta el 96 %. El presupuesto de 150 ms deja margen para la red hasta la BD gestionada y para la JVM; si se incumple, la causa estará en la BD o en el pool, no en el protocolo.

### NFR-02 — Throughput de envío (Should) (rev. 2026-10)

- El throughput hacia el proveedor está acotado por **su** límite, no por el servicio: Resend permite **10 peticiones/segundo por equipo** por defecto ([documentación, 2026-10-05](https://resend.com/docs/api-reference/introduction)). Objetivo: un worker sostiene `WORKER_PROVIDER_RPS` (default 8) mensajes/s contra un proveedor simulado con 100 ms de latencia y no supera nunca ese ritmo (test con WireMock que cuenta peticiones por segundo).
- La API acepta **≥ 200 solicitudes/segundo** en una instancia de referencia (medido: 634 req/s con 16 clientes concurrentes en un contenedor de 2 vCPU que compartía CPU con la BD y con el cliente).
- Objetivo de negocio del primer año: < 100 000 correos/mes (≈ 0,04 msg/s de media). Con 8 msg/s hay unas 200 veces de margen sobre la media y holgura para picos; **no** se optimiza más allá (NFR-10).

### NFR-03 — Durabilidad (Must)

- Un `202` implica que el mensaje está confirmado (`COMMIT`) en PostgreSQL. No existe cola en memoria ni buffer volátil.
- Matar el proceso (`kill -9`) en cualquier punto no pierde mensajes: los `SENDING` con lock vencido vuelven a `QUEUED` automáticamente.
- Ningún mensaje permanece `QUEUED` (elegible) o `SENDING` indefinidamente: existe una métrica de antigüedad y un barrido de locks vencidos cada 60 s. **(rev. 2026-10)** Los mensajes retenidos por tenant suspendido o pausado se miden aparte (AC-02.6).

### NFR-04 — Al menos una vez, con deduplicación en el proveedor (Must) (rev. 2026-10)

Sustituye a "exactamente una vez desde la perspectiva del usuario", que el diseño anterior no podía garantizar (ADR-0010).

- **Garantía de no pérdida:** todo mensaje aceptado se intenta hasta un estado terminal (`SENT`→…, `FAILED`, `CANCELED`). Preferimos un duplicado improbable a un correo perdido.
- **Garantía de no duplicado:**
  - Con `Idempotency-Key` del cliente, N llamadas idénticas producen **un** mensaje (FR-08).
  - Cada intento hacia el proveedor lleva `Idempotency-Key = message.id`. Resend deduplica durante **24 h** ([documentación](https://resend.com/docs/dashboard/emails/idempotency-keys)), y el diseño garantiza que todos los intentos de un mensaje ocurren en < 23 h (AC-13.7).
  - El *fencing* por `lock_token` impide que dos workers cierren el mismo mensaje (AC-12.3).
- **Riesgo residual documentado:**
  1. Si se cambia a un proveedor sin idempotencia, vuelve la ventana de duplicado por timeout. `EmailSender` declara `supportsIdempotency()`, y el arranque emite una advertencia si es `false`.
  2. Un render no determinista invalida la deduplicación; el proveedor responde `409` y el caso se detecta (AC-13.8).
  3. Duplicados aguas arriba (el producto llama dos veces sin `Idempotency-Key`) no son detectables.
- **Verificación:** test con WireMock que acepta el envío y corta la conexión antes de responder; el reintento lleva la misma `Idempotency-Key` y el simulador cuenta **un** envío.

### NFR-05 — Resiliencia ante el proveedor (Must)

- Con el proveedor caído: la API sigue aceptando; los mensajes se acumulan; al recuperarse se drenan solos.
- **(rev. 2026-10)** Timeouts de 3 s (conexión) y 10 s (lectura). Circuit breaker según AC-13.9: 5 fallos transitorios seguidos lo abren durante 60 s; implementado a mano (~50 líneas), sin dependencias nuevas.
- El health check refleja el estado degradado, pero **no** devuelve `DOWN` por el proveedor (eso mataría instancias sanas).

### NFR-06 — Escalabilidad (Should)

- Varias instancias de API detrás de un balanceador, sin estado en memoria (el rate limit y la cola viven en PostgreSQL). La caché de API keys (≤ 60 s) y el límite por IP de los `401` son por instancia, a propósito.
- Varios workers en paralelo sin envíos duplicados (`FOR UPDATE SKIP LOCKED` + `lock_token`). **(rev. 2026-10)** Con N workers, `WORKER_PROVIDER_RPS` se divide entre N (NFR-21).
- **(rev. 2026-10)** Criterio para reevaluar la cola (ADR-0010), sustituyendo a "> 50 mensajes/s", que equivalía a ~130 M de correos/mes y nunca se habría disparado:
  - la consulta de toma tiene p95 > 50 ms durante 3 días (medido hoy: 2,4–4,8 ms con 12 800 mensajes en cola), **o**
  - la antigüedad de la cola supera 60 s con el proveedor sano durante 3 días, **o**
  - el volumen supera 3 M de correos/mes, **o**
  - se necesita fan-out a varios consumidores independientes.

  Antes de eso, **no** se introduce un broker.

### NFR-07 — Seguridad (Must)

Resumen (detalle en `08-seguridad.md`):
- secretos solo en el entorno o en un secrets manager;
- API keys guardadas como hash, con ámbitos y CIDR opcionales;
- TLS o red privada cifrada en todo tránsito;
- firma de webhooks verificada;
- límites y detección de anomalías por tenant;
- validación estricta de entrada y de plantillas;
- auditoría de acciones sensibles;
- sin secretos ni contenido de correos en los logs.

### NFR-08 — Aislamiento multi-tenant (Must) (rev. 2026-10)

- **Primera barrera (código):** **toda** consulta que toque datos de tenant lleva `tenant_id` en el `WHERE`; los repositorios lo exigen en su firma.
- **Segunda barrera (base de datos), desde el MVP (ADR-0008):** RLS con `ENABLE` y `FORCE ROW LEVEL SECURITY` en todas las tablas con `tenant_id`. La política compara con `current_setting('app.tenant_id', true)::uuid`; si la variable no está fijada, la consulta no devuelve filas (*fail-closed*).
- **Invariantes en la BD:** claves foráneas compuestas `(tenant_id, …)` impiden que un mensaje referencie una plantilla o versión de otro tenant.
- Los identificadores son UUID no adivinables; acceder a un recurso de otro tenant devuelve `404`.
- Las rutas que deben cruzar tenants (toma de la cola, barridos, purga, correlación de webhooks, lookup de API key por prefijo y supresiones globales) usan un segundo `DataSource` con el rol `email_system`, confinado por un test de arquitectura a los paquetes `sending.worker`, `events`, `tenancy.auth`, `tenancy.admin`, `abuse` y `maintenance`.
- Tests obligatorios:
  1. Por cada endpoint `/v1/**`, un caso "tenant A no ve/afecta recursos de tenant B".
  2. Un test que ejecuta, con el rol de la app y **sin** `app.tenant_id`, un `SELECT` sobre cada tabla y espera 0 filas.
  3. Un test que intenta insertar un `email_message` con un `template_version_id` de otro tenant y espera una violación de FK.

### NFR-09 — Operabilidad (Must)

- `cp .env.example .env && docker compose up --build` levanta app + PostgreSQL + Mailpit, con migraciones aplicadas automáticamente, en ≤ 90 s.
- Despliegue en producción: **una imagen** y roles por variable de entorno (`APP_ROLE=api|worker|all|migrate`).
- **(rev. 2026-10)** Las migraciones Flyway se ejecutan como paso previo del despliegue con `APP_ROLE=migrate` y el rol de BD `email_owner`; las instancias `api`/`worker` corren con roles sin privilegios DDL y **no** migran al arrancar en producción. En local (`APP_ROLE=all`, `APP_ENV=local`) se migra al arrancar por comodidad.
- Las migraciones son compatibles hacia atrás (una versión nueva de la app debe convivir con la anterior durante un despliegue *rolling*).
- Configuración solo por variables de entorno (12-factor). **(rev. 2026-10)** La configuración se valida al arrancar (coherencia `MAX_ATTEMPTS`/backoff, ventana < 23 h, allowlist en `staging`, secretos obligatorios); si falla, no arranca.

### NFR-10 — Presupuesto de complejidad (Must)

Regla explícita para frenar la sobreingeniería:

- **Un** servicio desplegable, **una** base de datos, **cero** brokers externos, **cero** cachés externas, **cero** almacenamiento de objetos en el MVP.
- Dependencias de infraestructura permitidas en producción: PostgreSQL y el proveedor de correo. Añadir cualquier otra (Redis, RabbitMQ, Kafka, Elasticsearch, S3…) requiere un ADR con una métrica real que lo justifique.
- Las únicas indirecciones permitidas "por si acaso" son `EmailSender`/`WebhookVerifier` (proveedor) y el motor de plantillas.
- Si una funcionalidad requiere más de ~200 líneas de infraestructura nueva y no está en `02`, se consulta antes de implementarla.

### NFR-11 — Observabilidad (Must)

- Logs JSON a stdout con `timestamp`, `level`, `service`, `requestId`, `tenantSlug` y `messageId` cuando aplique. **(rev. 2026-10)** Retención en el destino de logs: 30 días; direcciones de correo enmascaradas.
- `requestId`: se toma de `X-Request-Id` si viene (validado: ≤ 64 caracteres `[A-Za-z0-9-_]`); si no, se genera; se devuelve siempre en la respuesta.
- Métricas Micrometer (ver FR-24) accesibles por Actuator; exportación a Prometheus por configuración.
- Trazas distribuidas: fuera del MVP (basta con `requestId`); **(rev. 2026-10)** el exportador OTLP se puede activar por configuración sin cambios de código.
- **(rev. 2026-10)** Alertas mínimas: antigüedad de cola > 300 s; tasa de `FAILED` > 5 % en 1 h; tenant pausado (FR-34); circuit breaker abierto > 10 min; `email_lost_lock_total` > 0; `PROVIDER_IDEMPOTENCY_CONFLICT` > 0; tasa de queja por tenant > 0,1 % en 7 días.

### NFR-12 — Mantenibilidad (Must)

- Paquetes por capacidad (`sending`, `templates`, `tenancy`, `events`, `provider`, `ratelimit`, `abuse`, `audit`, `maintenance`, `common`), no por capa técnica.
- Sin frameworks de ORM pesados ni generación mágica: acceso a datos explícito y legible.
- Cobertura ≥ 75 % global; ≥ 90 % en render de plantillas, linter de plantillas, política de reintentos, idempotencia, autenticación y ámbitos.
- Tests de integración con Testcontainers (PostgreSQL con los mismos roles y políticas RLS que producción) y un servidor HTTP simulado para el proveedor; **ningún test envía correo real**.
- Formato y análisis estático en CI; build reproducible. **(rev. 2026-10)** Tests de arquitectura (ArchUnit) para las reglas de dependencia de `05` §2 y para el uso del `DataSource` de sistema.

### NFR-13 — Portabilidad (Should)

- Imagen `linux/amd64` y `linux/arm64`.
- Funciona igual en Docker Compose local, en un VPS con Docker y en un contenedor gestionado (Fly.io, Render, ECS…). Sin dependencias de un proveedor de nube concreto en el MVP.

### NFR-14 — Retención, respaldo y restauración (Should)

- Retención configurable por tenant (default 90 días) con purga diaria (FR-23).
- **(rev. 2026-10)** Respaldo: PITR del PostgreSQL gestionado (RPO ≤ 5 min) o `pg_dump` diario cifrado (RPO ≤ 24 h). RTO ≤ 4 h. Respaldos cifrados en reposo y con retención ≤ 35 días, para que la purga por retención y los borrados por titular dejen de existir también en los respaldos dentro de un plazo acotado.
- Procedimiento de restauración probado al menos una vez antes de producción y después cada 6 meses; documentado en el README.

### NFR-15 — Privacidad (Must)

- Se guarda lo mínimo: dirección del destinatario, variables de la plantilla y metadatos. El HTML renderizado se guarda **solo** si el tenant lo habilita (`storeRenderedContent`), y con las variables `x-sensitive` sustituidas por `[REDACTED]`.
- **(rev. 2026-10)** Las variables se purgan a los 7 días de llegar el mensaje a su estado final, y las `x-sensitive` al enviar (AC-23.5). Los payloads de eventos se minimizan (AC-17.1).
- Las variables pueden contener datos personales: nunca se registran en logs.
- Documentado en el README: el proveedor de correo procesa direcciones y contenido como encargado del tratamiento, en EE. UU.
- **(rev. 2026-10)** El seguimiento de aperturas y clics está desactivado por defecto y prohibido en plantillas `SECURITY` (AC-04.11).

### NFR-16 — Costo (Should) (rev. 2026-10)

- Operación objetivo: **≤ 25 USD/mes** mientras basten el plan gratuito del proveedor (3 000 correos/mes, 100/día) y una BD gestionada de 1 vCPU/1 GB (15,15 USD en DigitalOcean); **≤ 50 USD/mes** con Resend Pro (20 USD por 50 000 correos). Precios consultados el 2026-10-05; se revisan antes de contratar.
- El diseño evita componentes con costo fijo adicional (brokers, cachés, clúster, almacenamiento de objetos).
- Nota: el límite del plan gratuito de Resend es de **100 correos/día**; un solo pico (p. ej., un aviso a todos los clientes de un producto) lo agota y bloquea las recuperaciones de contraseña del resto del día. En producción con más de un producto se asume Resend Pro.

### NFR-17 — Errores de API uniformes (Must)

- Todos los errores en `application/problem+json` (RFC 9457) con `type`, `title`, `status`, `detail`, `instance`, y las extensiones `code` (constante en MAYÚSCULAS) y `requestId`.
- Nunca se exponen stacktraces, SQL ni respuestas crudas del proveedor.
- Los códigos `code` son un enum cerrado documentado en `07-api-interna.md`. **(rev. 2026-10)** Añadir un `code` nuevo **no** es un cambio incompatible si el cliente sigue la regla de `07` §1 (tratar un `code` desconocido según su clase HTTP).

### NFR-18 — Evolución del contrato (Must)

- Versionado en la ruta (`/v1`). Cambios compatibles: añadir campos opcionales en respuestas, campos opcionales en peticiones o códigos de error nuevos.
- Cambios incompatibles (renombrar/eliminar campos, cambiar semántica, añadir valores a un enum de **estado** que el cliente interpreta): nueva versión de ruta + ADR.
- Los clientes deben ignorar campos desconocidos; queda escrito en la documentación de la API.
- **(rev. 2026-10)** Mientras no exista una versión desplegada en producción (Fase 0 sin terminar), los cambios a `/v1` se registran en `CHANGELOG.md` con su nota de migración para los productos, sin abrir `/v2`. Desde el primer despliegue en producción, la regla general aplica sin excepciones.

### NFR-19 — Tiempo hasta el envío (Must, nuevo)

- Aceptación → `SENT`: **p95 ≤ 5 s** y p99 ≤ 15 s para mensajes `SECURITY` y `TRANSACTIONAL`, con el proveedor sano y sin reintentos pendientes; medido por la métrica `email_time_to_sent_seconds` por categoría.
- Un mensaje `SECURITY` nunca espera detrás de mensajes `NOTICE` elegibles (AC-36.3).
- `WORKER_POLL_INTERVAL_MS` (default 1 000) contribuye con hasta 1 s; no se añade LISTEN/NOTIFY salvo que este NFR se incumpla.

### NFR-20 — Cumplimiento (Ley 8968 y privacidad) (Must, nuevo)

No soy abogado: lo siguiente es la lectura técnica de obligaciones verificadas en fuentes secundarias el 2026-10-05, a validar con asesoría legal.

- **Transferencia internacional:** la Ley 8968 no tiene régimen de adecuación; la transferencia a un encargado en el extranjero (el proveedor de correo, en EE. UU.) requiere autorización expresa e informada del titular (art. 14). Responsabilidad de **cada producto**: su aviso de privacidad y su consentimiento deben cubrirla. El email-service documenta en el README qué datos recibe el proveedor.
- **Derechos ARCO:** se atienden en **5 días hábiles**; el email-service ofrece FR-29 para la supresión de datos.
- **Brechas de seguridad:** el Reglamento (Decreto 37554-JP, arts. 38–39) exige notificar a los afectados y a PRODHAB en **5 días hábiles** desde el incidente; integrado en `08` §9.
- **Registro ante PRODHAB:** obligatorio para bases de datos que se distribuyen, difunden o comercializan; las de uso interno están exentas. El email-service se considera de uso interno (**a confirmar** con asesoría).
- **(rev. 2026-10, owner) Colmena trata datos de los clientes de sus clientes** (productos, deudas). En esos correos, el negocio cliente de Colmena es el **responsable**, Colmena es **encargada** y el email-service y el proveedor son **subencargados**. Implicaciones:
  - el contrato o los términos de Colmena con cada negocio deben cubrir el envío de correos a sus clientes y la transferencia al proveedor en EE. UU.;
  - los recordatorios de deuda son datos especialmente delicados: se marcan `x-sensitive` (montos, conceptos) y no se activa el seguimiento;
  - si la gestión de cobro está regulada (horarios, frecuencia de contacto), la regla vive en Colmena, no aquí: **a confirmar** con asesoría qué normativa de cobro aplica a recordatorios por correo.
- **(rev. 2026-10, owner) Ámbito geográfico:** solo usuarios en Costa Rica. GDPR y CAN-SPAM no aplican mientras eso no cambie; los requisitos de Gmail, Yahoo y Microsoft sí aplican (dependen del buzón, no del país).
- **Estado de la transferencia internacional (pregunta abierta 9):** **pendiente, no bloquea el desarrollo**; es un requisito del checklist de salida a producción de cada producto (`08` §10).
- **Reforma pendiente:** el expediente 23.097 (nueva ley de protección de datos) no consta como aprobado; estado a 2026 **a confirmar**.
- **Correo comercial:** fuera del alcance de este servicio (ADR-0013); sus requisitos están en `11-correo-masivo.md`.

### NFR-21 — Límites y reputación del proveedor (Must, nuevo)

- La suma de `WORKER_PROVIDER_RPS` de todas las instancias que comparten cuenta de proveedor ≤ 80 % del límite contratado (Resend por defecto: 10 rps por equipo → 8 rps).
- **Staging y producción usan equipos (cuentas) de proveedor distintos**: no comparten límite de tasa, lista de supresión ni reputación.
- Todos los dominios remitentes tienen SPF, DKIM y DMARC (al menos `p=none`, alineado) verificados antes de enviar; es requisito de Gmail, Yahoo y Microsoft incluso para el correo transaccional.
- Tasa de quejas objetivo < 0,1 % por tenant y nunca ≥ 0,3 % (umbrales de Gmail). FR-34 pausa antes de llegar a 0,3 %.
- Riesgo aceptado y documentado: todos los productos comparten **una** cuenta de proveedor (supresión, límite y revisión de cuenta comunes). Ver la pregunta abierta sobre cuentas por producto en `docs/auditoria/preguntas-abiertas.md`.
