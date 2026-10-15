# email-service

Microservicio interno de correo **transaccional** para PipeMend, Colmena, Chinamo y Payverica. Es un único servicio multi-tenant con aislamiento por RLS en PostgreSQL, plantillas por producto, envío asíncrono y trazabilidad. No es una plataforma de marketing.

La fuente de verdad es [`docs/`](docs/README.md). Reglas para asistentes de IA: [`AGENTS.md`](AGENTS.md).

> Estado: H1–H8 terminados; H9 (producción) en pausa hasta las decisiones de infraestructura. Estado detallado y **guía para usar el servicio desde otros proyectos en local** (red Docker, un tenant por proyecto, plantillas en el repositorio del producto, eventos simulados): [`docs/12-estado-y-uso-local.md`](docs/12-estado-y-uso-local.md).

## Arranque local

Requisitos:
- Docker con Compose;
- para los tests y los scripts: Java 25 y `jq`, ambos declarados en [`mise.toml`](mise.toml) (`mise install`).

```bash
cp .env.example .env            # solo marcadores locales; .env está en .gitignore
docker compose up --build       # app (APP_ROLE=all) + PostgreSQL 18 con 3 roles + Mailpit
./scripts/seed-local.sh         # tenant "demo", una key de producto, una de operador y las plantillas de templates/demo
./scripts/smoke-test.sh         # POST /v1/emails -> cola -> worker -> SMTP -> Mailpit, verificado por su API
./scripts/simulate-event.sh <messageId> delivered   # evento de Resend simulado y firmado (bounced, complained, delayed)
```

Comportamiento del seed y de los datos:
- El seed es idempotente: si el tenant ya existe no cambia nada ni emite keys; `--new-keys` emite un par nuevo.
- `SLUG=colmena ./scripts/seed-local.sh` crea el tenant de otro proyecto.
- `docker compose down -v` borra la base: tenants, keys y plantillas incluidos.

Otros proyectos en Docker se conectan por la red `email-service-net` en `http://email-service:8080` ([`docs/12`](docs/12-estado-y-uso-local.md)).

| URL | Qué es |
|---|---|
| http://localhost:8080/swagger-ui.html | Swagger UI (grupos `public` y `admin`) |
| http://localhost:8080/actuator/health/readiness | Readiness (depende solo de la BD) |
| http://localhost:8025 | Mailpit (SMTP de desarrollo; no entrega nada fuera) |

Tests: `./gradlew build` (necesita Docker: Testcontainers con los mismos roles y RLS que producción).

## Envío real desde staging (Resend)

En local se envía siempre por SMTP a Mailpit. Resend se usa a partir de staging (ADR-0016). La cuenta la prepara el owner; el servicio solo necesita las variables.

1. **Equipo de Resend para staging,** separado del de producción. Así una key o un dominio de staging nunca afectan a la reputación de producción.
2. **Dominio remitente:** en *Domains → Add domain*, un subdominio propio (p. ej. `mail-staging.<dominio>`). Publica en el DNS los registros que muestra Resend:
   - **SPF:** el `TXT` y el `MX` del subdominio de rebotes (`send.…`);
   - **DKIM:** el `TXT` `resend._domainkey…`;
   - **DMARC:** Resend no lo crea. Añade `_dmarc.<subdominio>` `TXT "v=DMARC1; p=none; rua=mailto:<buzón>"` y endurécelo (`quarantine` / `reject`) cuando los informes estén limpios.

   Espera a que el dominio aparezca como **Verified**.
3. **API key:** en *API Keys → Create*, con permiso **Sending access** y limitada a ese dominio. Se muestra una sola vez: guárdala directamente en el gestor de secretos de staging.
4. **Variables del despliegue de staging** (gestor de secretos, nunca en el repositorio):

   | Variable | Valor |
   |---|---|
   | `APP_ENV` | `staging` |
   | `MAIL_PROVIDER` | `resend` |
   | `RESEND_API_KEY` | la key del paso 3 (secreto) |
   | `ALLOWED_RECIPIENT_DOMAINS` | dominios de destino permitidos en staging (obligatoria) |
   | `WORKER_PROVIDER_RPS` | límite de peticiones por segundo de la cuenta de Resend dividido entre el número de instancias de worker |
   | `CIRCUIT_BREAKER_FAILURES` / `CIRCUIT_BREAKER_OPEN_SECONDS` | `5` / `60` (por defecto) |

   `RESEND_API_URL` solo se cambia para pruebas contra un stub, y fuera de `local` debe ser `https`.
5. **Tenant:** su `fromEmail` debe usar el dominio verificado, que también va en `allowedFromDomains`:
   ```bash
   curl -X PATCH "$BASE/admin/v1/tenants/demo" -H "X-Admin-Key: $ADMIN_KEY" -H 'Content-Type: application/json' \
     -d '{"fromEmail": "no-reply@mail-staging.<dominio>", "allowedFromDomains": ["mail-staging.<dominio>"]}'
   ```

**Qué pasa si algo está mal configurado** (docs/05 §4):
- **Key revocada o sin permiso** (`401`, `403` de la key, `404`/`405`):
  - el circuit breaker se abre al primer error y nadie consume intentos;
  - suben el log de error y `email_provider_config_error_total{error}`;
  - tras corregir la key, el siguiente sondeo (60 s) reanuda el envío;
  - los mensajes caducan a las 23 h de su primer intento (`RETRY_WINDOW_EXCEEDED`).
- **Dominio no verificado** (`403 validation_error`):
  - solo ese tenant queda en pausa (`pause_reason = PROVIDER_DOMAIN`, auditado);
  - sus mensajes siguen `QUEUED` y la API responde `403 TENANT_SENDING_PAUSED`;
  - tras verificar el dominio, se reanuda con:
  ```bash
  curl -X POST "$BASE/admin/v1/tenants/demo/resume-sending" -H "X-Admin-Key: $ADMIN_KEY" \
    -H 'Content-Type: application/json' -d '{"note": "dominio verificado en Resend"}'
  ```

**Prueba manual (opcional).** Envía un correo real a un buzón tuyo, dentro de `ALLOWED_RECIPIENT_DOMAINS`:
```bash
curl -X POST "$BASE/v1/emails" -H "Authorization: Bearer $PRODUCT_KEY" -H 'Content-Type: application/json' \
  -H "Idempotency-Key: $(uuidgen)" \
  -d '{"templateKey": "<plantilla publicada>", "to": {"email": "<tu buzón>"}, "variables": {...}}'
```
Comprueba lo siguiente:
- la respuesta es `202` con el `id` del mensaje;
- en *Emails* de Resend aparece el envío con la etiqueta `message_id=<id>`;
- en la base, el mensaje pasa a `SENT` con `provider_message_id`.

Los tests nunca llaman a Resend: usan WireMock en local.

## Webhooks de Resend en staging

Los eventos (entregado, rebote, queja, supresión) llegan a `POST /webhooks/resend`, firmados con Svix (FR-16). Resend necesita una URL pública con HTTPS. En staging, el balanceador debe exponer **solo** `/webhooks/*` (docs/05 §7).

**Registrar el endpoint** (en el equipo de staging de Resend):
1. En *Webhooks → Add endpoint*, pon la URL `https://<host de staging>/webhooks/resend`.
2. Marca estos eventos: `email.sent`, `email.delivered`, `email.delivery_delayed`, `email.bounced`, `email.complained`, `email.failed`, `email.suppressed`, `email.opened`, `email.clicked`, `suppression.added` y `suppression.removed`.
3. Copia el *Signing secret* (`whsec_…`) directamente al gestor de secretos como `MAIL_WEBHOOK_SIGNING_SECRET` y reinicia las instancias `api`. Es obligatorio con `MAIL_PROVIDER=resend`: sin él, la API no arranca.
4. **Rotación** (docs/08 §1):
   - crea un segundo endpoint;
   - pon los dos secretos separados por coma (`whsec_viejo,whsec_nuevo`);
   - borra el endpoint viejo;
   - deja solo el nuevo.

**Prueba temporal con un túnel** (antes de que staging tenga host propio). Nunca expongas la aplicación entera: delante del túnel va un proxy que solo deja pasar `/webhooks/*`; `/v1` y `/admin` responden `404`.

```bash
# 1. La app con los valores de staging, desde el host, contra el PostgreSQL de compose.
#    .env.staging está en .gitignore; contiene APP_ENV=staging, MAIL_PROVIDER=resend, RESEND_API_KEY,
#    MAIL_WEBHOOK_SIGNING_SECRET, ALLOWED_RECIPIENT_DOMAINS (incluye resend.dev) y las variables de .env.
docker compose up -d postgres
set -a; source .env.staging; set +a; ./gradlew bootRun

# 2. Proxy que solo reenvía /webhooks/* (otra terminal).
printf ':8081 {\n\thandle /webhooks/* {\n\t\treverse_proxy localhost:8080\n\t}\n\trespond 404\n}\n' > /tmp/Caddyfile
docker run --rm --network host -v /tmp/Caddyfile:/etc/caddy/Caddyfile:ro caddy:2

# 3. Túnel temporal hacia el proxy (otra terminal); imprime una URL https://<aleatorio>.trycloudflare.com
cloudflared tunnel --url http://localhost:8081
```

Después:
1. Registra `https://<aleatorio>.trycloudflare.com/webhooks/resend` como endpoint (pasos de arriba) y usa su secreto en `.env.staging`.
2. Envía a las direcciones de prueba de Resend, con la prueba manual de la sección anterior:
   - `delivered@resend.dev`: el mensaje pasa a `DELIVERED`;
   - `bounced@resend.dev`: pasa a `BOUNCED` y se crea una supresión global `HARD_BOUNCE`;
   - `complained@resend.dev`: pasa a `COMPLAINED` y se crea una supresión global `COMPLAINT`.
3. En *Webhooks → \<endpoint\>* de Resend, cada entrega debe aparecer con `204`. Un `401` indica que el secreto no coincide.
4. **Al terminar:**
   - corta el túnel y el proxy (`Ctrl+C`);
   - borra el endpoint temporal en Resend;
   - retira las supresiones de prueba con `DELETE /admin/v1/suppressions/{email}`, y también en *Suppressions* de Resend.

## Operación: alertas, salud y datos

**Alertas.** Las alertas de NFR-11 son líneas de log JSON de nivel `ERROR` con el campo `alert`, y `alertSubject` indica a qué se refiere.
- Una misma alerta para el mismo sujeto se emite como mucho una vez cada 15 minutos por instancia.
- Para una alerta basada en logs en el hosting, filtra `level = ERROR` y el campo `alert` presente, y agrupa por `alert` y `alertSubject`.
- `ops/prometheus/alerts.yml` tiene reglas equivalentes de referencia, para cuando las métricas se recojan con Prometheus.

| `alert` | `alertSubject` | Qué significa | Qué hacer |
|---|---|---|---|
| `QUEUE_AGE` | `queue` | El mensaje elegible más viejo espera más de `QUEUE_AGE_ALERT_SECONDS` (300 s) | Mirar si hay workers vivos, el circuit breaker y `/actuator/health` |
| `FAILED_RATE` | `all-tenants` | Más del 5 % de los mensajes de la última hora están `FAILED` (con al menos 20) | Agrupar por `failure_code` (docs/06 §4) |
| `TENANT_PAUSED` | slug (o id) del tenant | Pausa automática: volumen, rebotes, quejas (FR-34) o dominio no verificado | Revisar con el producto; reanudar con `POST /admin/v1/tenants/{slug}/resume-sending` |
| `CIRCUIT_BREAKER_OPEN` | `provider` | El proveedor lleva más de 10 minutos sin aceptar envíos | Estado de Resend; la cola espera sin perder intentos |
| `PROVIDER_CONFIGURATION` | código de error de Resend | La API key o los permisos están mal (`401`, `403` de la key) | Rotar o corregir `RESEND_API_KEY` |
| `LOST_LOCK` | `worker` | Un worker perdió el lock de un mensaje antes de cerrarlo | Revisar pausas largas del worker o `LOCK_TIMEOUT_SECONDS` |
| `IDEMPOTENCY_CONFLICT` | id del mensaje | El proveedor vio el mismo id con otro cuerpo: render no determinista (AC-12.7) | Revisar la plantilla y la versión fijada del mensaje |
| `COMPLAINT_RATE` | slug del tenant | Tasa de quejas a 7 días por encima de 0,1 % | Revisar contenido y destinatarios del producto antes de que llegue a pausar |
| `AUTH_FAILURE_SPIKE` | IP | Una IP superó `AUTH_FAILURES_PER_MINUTE_PER_IP` fallos de autenticación | Si es un producto propio, su key está mal configurada; si no, posible fuerza bruta (docs/08 §4) |

**Salud.**
- `/actuator/health/liveness` y `/actuator/health/readiness` son las sondas del orquestador.
- `/actuator/health` agrega además `queue` y `provider`. Este último solo existe en el rol worker.
- `DEGRADED` responde `200`: avisa sin reiniciar instancias sanas. `provider` en `DOWN` (breaker abierto o error de configuración) sí responde `503` en `/actuator/health`, pero no afecta a las sondas.

**Retención y borrado.**
- Las purgas corren solas en el worker (docs/06 §8): variables, idempotencia, retención por tenant, contenido renderizado, huérfanos, contadores y auditoría.
- Un titular se borra con `POST /admin/v1/data-subjects/erase`, siguiendo el procedimiento completo de docs/08 §6, que incluye pedir el borrado a Resend y responder en plazo.

## Plantillas como código

Las plantillas viven en este repositorio y se publican por la API ([`docs/05`](docs/05-arquitectura.md) §9):

```
templates/{tenant}/{templateKey}/
├── template.json     # key, name, category, subject por locale, variablesSchema, previewVariables
├── es-CR.html        # un .html por locale (obligatorio)
├── es-CR.txt         # texto plano (opcional)
├── en.html
└── en.txt
```

```bash
EMAIL_SERVICE_TEMPLATES_KEY=esk_test_... ./scripts/publish-template.sh demo password-reset

# Desde el repositorio de un producto, con la carpeta de su plantilla:
EMAIL_SERVICE_TEMPLATES_KEY=esk_test_... EMAIL_SERVICE_URL=http://localhost:8080 \
  ../email-service/scripts/publish-template.sh emails/password-reset
```

El script:
- crea la plantilla si no existe;
- omite los locales sin cambios;
- crea los borradores y los previsualiza con `previewVariables`;
- publica solo si todas las previsualizaciones pasan; si algo falla, borra los borradores que creó.

- Con varios idiomas cambiados, los publica todos en una sola operación (ADR-0020), lo que permite cambiar las variables obligatorias de una plantilla multilingüe.

La key debe ser de operador (`templates:write` y `emails:read`), nunca la que se despliega en un producto. En CI, `TemplatesAsCodeTest` valida cada plantilla con las mismas reglas que el servicio.

El CI no usa mise ni `jq`: Java lo instala `setup-java` y ningún job ejecuta los scripts. `jq` solo hace falta en local para los scripts; lo instala `mise install`, y en los runners de GitHub ya viene incluido.

Reglas de las plantillas (ADR-0011):
- Handlebars *logic-less*;
- solo `if`, `unless`, `each`, `with`, `formatDate`, `formatNumber` y `formatMoney`;
- sin `{{{ }}}`, `{{& }}` ni parciales;
- HTML escapado en el cuerpo;
- variables URL con `"format": "uri"`, `https` y un host de `allowedLinkHosts` del tenant.

## Ramas

- `main`: siempre desplegable.
- `dev`: integración.
- `feat/*`: una rama por hito.

Detalle en [`AGENTS.md`](AGENTS.md#branching).
