# 04 — Stack tecnológico y justificación

> Revisión 2026-10-05: versiones verificadas, justificación del lenguaje reescrita con matriz ponderada y medidas (ADR-0009), comparativa de proveedores corregida (ADR-0016), protocolo de entrada evaluado (ADR-0014).

## 1. Resumen de la elección

| Capa | Elección | Versión verificada (2026-10-05) |
|---|---|---|
| Lenguaje | **Java 25 (LTS)** | 25 |
| Framework | **Spring Boot 4.1** (Web MVC + Actuator + Validation), hilos virtuales activados | 4.1.1 (OSS hasta 2027-07-31) |
| Base de datos | **PostgreSQL 18** | 18.6 (soporte hasta 2030-11-14) |
| Acceso a datos | **Spring Data JDBC** (sin JPA/Hibernate) + **Flyway**, dos `DataSource` (tenant con RLS / sistema) | Gestionadas por el BOM |
| Cola de trabajos | **PostgreSQL** (`FOR UPDATE SKIP LOCKED` + `lock_token` + prioridad) | — (sin broker) |
| Plantillas | **Handlebars.java** endurecido (ADR-0011) + jsoup para el linter | **(rev. 2026-10-06)** Handlebars.java 4.5.5 (2026-09-14), sin Nashorn; jsoup 1.23.2 |
| Proveedor de correo | **Resend** vía su API HTTP (alternativas: Postmark, Amazon SES) | API actual |
| SMTP de desarrollo | **Mailpit** | Última |
| Documentación API | **springdoc-openapi** | Compatible con Boot 4.1 |
| Build | **Gradle (Kotlin DSL)**, toolchain Java 25 | Wrapper commiteado |
| Contenedores | **Docker + Docker Compose**, imagen base `eclipse-temurin:25-jre` | — |
| Tests | JUnit 5, Testcontainers (PostgreSQL con roles y RLS), WireMock (proveedor), Awaitility, ArchUnit | Últimas compatibles |
| Observabilidad | Micrometer + endpoint Prometheus; logs JSON a stdout; OTLP opcional | — |
| CI | GitHub Actions (build, tests, gitleaks, escaneo de dependencias, diff de OpenAPI) | — |

> Las versiones mayores se fijan aquí y en ADR-0009; las de parche, en `build.gradle.kts` y `docker-compose.yml`.

## 2. Lenguaje y framework (rev. 2026-10)

**Java 25 LTS + Spring Boot 4.1**: decisión ratificada en ADR-0009 después de compararla con Go, Node/TypeScript, Elixir y Rust. Resumen:

- **El lenguaje no es el cuello de botella.** Medido: el camino de aceptación tarda 1,38 ms p50 en el servidor, y el 96 % es PostgreSQL. Parse, auth, validación y serialización suman ~44 µs. Eso vale a nuestro volumen y a 100 veces más.
- **El perfil de E/S está cubierto** con hilos virtuales (`spring.threads.virtual.enabled=true`) en la API y en el worker.
- **Ecosistema completo** para lo que necesita el servicio: validación, Actuator, springdoc, Flyway, Testcontainers, ArchUnit, Handlebars.java y JSON Schema.
- **Costo de cartera.** Ya operas Java 25 + Spring Boot 4.1 en otro servicio; un quinto stack es el coste más alto a largo plazo para un solo mantenedor. Es el criterio decisivo, y se dice explícitamente: sin él, Go gana la matriz (4,63 frente a 3,94).
- **En contra:**
  - más memoria: RSS mínimo de la JVM medido en 125 MB, frente a 72 MB de un servidor Node equivalente; Spring Boot real, 200–300 MB, a confirmar en H1;
  - arranque más lento.

  Cabe en la instancia de referencia de 1 GB; el sobrecoste de hosting estimado es de 0 a 5 USD/mes.

Matriz ponderada y sensibilidad: ADR-0009. Spring Boot 3.5 terminó su soporte OSS el 2026-06-30 y la 4.0 lo termina el 2026-12-31, así que se usa la 4.1.

## 3. Base de datos

**PostgreSQL 18**, una base **exclusiva del servicio** (ADR-0001: ningún otro producto accede a ella).

- `jsonb` para variables, payloads de webhook y metadatos, sin inventar una tabla por cada forma de dato.
- `FOR UPDATE SKIP LOCKED` convierte una tabla en una cola de trabajo correcta y observable (ADR-0010).
- Índices parciales para la cola (`WHERE status = 'QUEUED'`), restricciones `UNIQUE` para la idempotencia y **FK compuestas + RLS** para el aislamiento: la corrección la garantiza la base de datos, no el código (ADR-0008).
- Disponible como servicio gestionado en cualquier proveedor, con respaldos y PITR. Referencia de costo: DigitalOcean 1 vCPU/1 GB por 15,15 USD/mes; 2 vCPU/4 GB por 60,90 USD/mes ([precios](https://www.digitalocean.com/pricing/managed-databases)). Requisito a confirmar con el hosting elegido: que permita crear un rol con `BYPASSRLS` o políticas por rol (ADR-0008).

**Acceso a datos: Spring Data JDBC + Flyway**, no JPA. Consultas explícitas, sin caché de primer nivel ni *lazy loading* sorpresa; para un dominio de **10 tablas** es más simple y predecible. Migraciones versionadas e inmutables una vez aplicadas, ejecutadas por un paso de despliegue con el rol propietario.

## 4. Cola de mensajes

**PostgreSQL como cola** (la fila del mensaje es el trabajo + `SKIP LOCKED`), sin RabbitMQ, Redis ni Kafka. Razones:

1. **Atomicidad real:** el mensaje y su trabajo en cola son la misma fila; no hay ventana "guardado pero no encolado" ni doble escritura.
2. **Un componente menos** que instalar, asegurar, respaldar y monitorear.
3. **Observabilidad trivial:** el estado de la cola se consulta con SQL; reintentos, backoff y mensajes atascados son columnas.
4. **Suficiente con mucho margen:** medido, la toma tarda 2,4–4,8 ms con 12 800 mensajes en cola. El límite práctico lo pone el proveedor (Resend: 10 rps por equipo), no la cola. **(rev. 2026-10)** Se corrige la versión anterior, que hablaba de "decenas por minuto" contradiciendo a `01` §7.

**Criterio de revisión (rev. 2026-10, ADR-0010):** p95 de toma > 50 ms durante 3 días, antigüedad de cola > 60 s con el proveedor sano durante 3 días, > 3 M de correos/mes o necesidad de fan-out. Candidato: SQS con outbox, manteniendo `email_message` como registro de verdad. (Se elimina el umbral "> 50 msg/s": equivalía a ~130 M de correos/mes y a 5 veces el límite del proveedor.)

## 5. Proveedor de correo transaccional (rev. 2026-10)

### 5.1 Comparativa (datos verificados el 2026-10-05; revisar antes de contratar)

| Criterio | **Resend** | **Postmark** | **Amazon SES** |
|---|---|---|---|
| Plan de entrada | Gratis: 3 000/mes, **100/día**, 3 dominios, 1 endpoint de webhook | Gratis: 100/mes. Basic 15 USD / Pro 16,50 USD / Platform 18 USD por 10 000 | Sin plan gratuito permanente; hasta 200 USD en créditos para cuentas nuevas |
| Precio a 50 000/mes | **20 USD** (Pro; 10 dominios; 5 endpoints de webhook) | 87 USD (Basic) / 68,50 USD (Pro) / 66 USD (Platform) | ≈ 5 USD (0,10 USD/1 000) + SNS/EventBridge |
| Precio a 100 000/mes | 35 USD (Pro) o 90 USD (Scale) | 177 / 133,50 / 126 USD | ≈ 10 USD + extras |
| Precio a 500 000/mes | **A confirmar** (tramo de Scale no publicado en la fuente consultada; la escala llega a 2,5 M por 1 150 USD) | Excedente de 1,20–1,80 USD/1 000 | ≈ 50 USD + extras |
| Límite de tasa por defecto | **10 peticiones/s por equipo** | A confirmar | Por cuenta, según la reputación (a confirmar) |
| Idempotencia por API | **Sí**: `Idempotency-Key`, 24 h, `409` si el cuerpo cambia | A confirmar | A confirmar |
| Webhooks de eventos | Sí, firmados con Svix: `sent`, `delivered`, `delivery_delayed`, `bounced`, `complained`, `failed`, `suppressed`, `opened`, `clicked`… | Sí, muy completos | Vía SNS/EventBridge (cableado extra) |
| Lista de supresión | **De todo el equipo** (todos los dominios), automática | Por *stream* | Por cuenta (opcional) |
| Separación transaccional / masivo | Dominios separados; IP compartidas salvo IP dedicada (> 3 000/día) | **Message Streams con IP separadas** por diseño | *Configuration sets* + pools de IP dedicadas |
| Fricción inicial | Muy baja | Baja (revisión de cuenta) | Alta: salir del *sandbox*, SNS, reputación propia |

Fuentes: [Resend pricing](https://resend.com/pricing), [Resend pricing KB](https://resend.com/docs/knowledge-base/what-is-resend-pricing), [Resend API (rate limit)](https://resend.com/docs/api-reference/introduction), [Resend idempotencia](https://resend.com/docs/dashboard/emails/idempotency-keys), [Resend eventos](https://resend.com/docs/dashboard/webhooks/event-types), [Resend supresiones](https://resend.com/docs/dashboard/emails/email-suppressions), [Resend IP dedicadas](https://resend.com/docs/knowledge-base/how-do-dedicated-ips-work), [Postmark pricing](https://postmarkapp.com/pricing), [Postmark Message Streams](https://postmarkapp.com/message-streams), [AWS SES pricing](https://aws.amazon.com/ses/pricing/).

**Correcciones respecto a la versión del 2026-09-20:**
- SES cuesta 0,10 USD/1 000, no 0,16.
- Postmark a 50 000/mes cuesta 66–87 USD, no "~15–18 USD base + excedente".
- Resend a 500 000/mes no es "~90 USD + excedente".

### 5.2 Recomendación: **Resend** para el MVP (ADR-0016)

1. **Coste:** 20 USD/mes cubren 50 000 correos, de 3 a 4 veces menos que Postmark a ese volumen.
2. **API y webhooks de primera clase**, con **idempotencia nativa**, que cierra la ventana de duplicados (ADR-0010).
3. **Fricción inicial mínima**: verificar el dominio con DKIM/SPF/DMARC y enviar.
4. **Plan:** gratuito para integrar el primer producto; **Pro** en cuanto haya más de un producto en producción. El límite de 100/día del plan gratuito lo agota cualquier pico y bloquea los correos `SECURITY` del resto del día.

### 5.3 Disparadores de cambio (medibles)

- **A Postmark** si:
  - las demoras y rebotes atribuibles al proveedor superan el 2 % durante 7 días;
  - un incidente de cuenta interrumpe el transaccional;
  - o se construye correo masivo propio y se quiere separación de IP por *stream* en un solo proveedor.
- **A Amazon SES** cuando el ahorro verificado supere 150 USD/mes durante 3 meses (a 200 000/mes: SES ≈ 20 USD frente a ≥ 90 USD en Resend).
- **Failover multi-proveedor** solo tras una caída real con correos `SECURITY` retrasados > 30 min.

Gracias a `EmailSender`/`WebhookVerifier` (FR-14), el cambio es **una implementación nueva + variables de entorno**. Ojo: si el proveedor nuevo no ofrece idempotencia, vuelve la ventana de duplicado por timeout (NFR-04).

### 5.4 Riesgo de cuenta compartida (rev. 2026-10)

Todos los productos comparten **un** equipo de Resend en producción. Separar dominios **no** separa:
- la lista de supresión (de todo el equipo);
- el límite de tasa (10 rps);
- las IP (compartidas en Pro);
- ni la revisión de abuso de la cuenta.

Un producto que genere quejas afecta a todos. Mitigaciones: detección de anomalías por tenant (FR-34), nada de correo masivo en esta cuenta (ADR-0013) y un equipo distinto para staging. Abrir un equipo por producto es una pregunta abierta para el owner.

## 6. Modo de desarrollo local: Mailpit

- Servidor SMTP + interfaz web que captura todo el correo sin entregarlo.
- Se activa con `MAIL_PROVIDER=smtp` apuntando a `mailpit:1025`; la interfaz queda en `http://localhost:8025`.
- Permite probar el HTML renderizado real, sin credenciales ni riesgo de enviar a direcciones reales.
- **SMTP no es el mecanismo de producción** (ADR-0003, ratificado por ADR-0016): sin webhooks, sin idempotencia, sin control de tasa, sin trazabilidad de eventos.

## 7. Motor de plantillas: Handlebars.java endurecido (rev. 2026-10, ADR-0011)

- **Logic-less:** las plantillas interpolan variables y hacen condicionales y bucles simples, pero no ejecutan código.
- **Endurecimiento obligatorio:**
  - `TemplateLoader` que no carga nada (sin parciales);
  - se eliminan los helpers por defecto (`partial`, `block`, `embedded`, `precompile`, `i18n`, `log`, `lookup`…) y solo se registran `if`, `unless`, `each`, `with`, `formatDate`, `formatNumber` y `formatMoney`;
  - solo `MapValueResolver` (sin reflexión sobre objetos Java);
  - se rechazan `{{{ }}}` **y** `{{& }}`.
- **Escapado por contexto:** HTML en el cuerpo HTML; ninguno en el asunto y el texto plano.
- **Linter HTML (jsoup)** al guardar: sin variables en `<script>`/`<style>`/`style=`/`on*`/atributos sin comillas; variables en `href`/`src` solo con `format: uri`.
- Se descartan Thymeleaf y FreeMarker para este uso: evalúan expresiones (SSTI).
- **Reserva:** jmustache, si Handlebars.java deja de mantenerse (criterio en ADR-0011).

## 8. Protocolo de entrada (rev. 2026-10, ADR-0014)

**REST/JSON sobre HTTPS** con conexiones persistentes y un endpoint de lote. Medido: el protocolo y la serialización son ~1,5 % del tiempo de servidor; protobuf no es más rápido que JSON para este payload (4,81 frente a 3,43 µs); una conexión TLS por petición sí cuesta (+2,5 ms en loopback, más 2 RTT de red), y agrupar inserciones baja el coste de BD unas 3 veces. Se descartan gRPC, Connect, cuerpos binarios, HTTP/3, ingestión por cola y SMTP de entrada (tabla completa en ADR-0014).

## 9. Almacenamiento de objetos y otras piezas (rev. 2026-10)

- **Almacenamiento de objetos:** ninguno. No hay adjuntos en el MVP (ADR-0015 rechazado); los documentos y las exportaciones se envían como enlace firmado alojado por el producto.
- **Circuit breaker:** código propio, sin Resilience4j.
- **Coordinación de tareas programadas:** `pg_try_advisory_xact_lock`, sin Quartz, ShedLock ni tabla de locks.

## 10. Resumen de dependencias de producción

**Permitidas:** PostgreSQL y el proveedor de correo. Nada más.

Cualquier otra dependencia de infraestructura (Redis, RabbitMQ, Kafka, Elasticsearch, S3, un segundo servicio) exige un ADR con una métrica real que la justifique (NFR-10). Es deliberado: el mayor riesgo de este proyecto no es quedarse corto de escalabilidad, sino volverse caro de mantener.
