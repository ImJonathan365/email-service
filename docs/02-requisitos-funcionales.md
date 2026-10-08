# 02 — Requisitos funcionales y criterios de aceptación

> Revisión 2026-10-05 (auditoría). Los criterios nuevos o modificados llevan la marca **(rev. 2026-10)**. Ningún ID se ha renumerado; lo eliminado se marca `DEPRECATED` y se conserva.

## 0. Convenciones

- IDs estables `FR-xx`. **No se renumeran**; un requisito o criterio eliminado se marca `DEPRECATED` y se conserva.
- Prioridad MoSCoW: **Must** (sin esto no hay MVP), **Should** (esperado, recortable), **Could** (si sobra tiempo), **Won't** (fuera del MVP, documentado para que nadie lo implemente por su cuenta).
- Criterios de aceptación en formato **Dado / Cuando / Entonces**, verificables con un test automatizado.
- Los enums y códigos de error usados aquí son los mismos de `06-modelo-de-datos.md` y `07-api-interna.md`. No se inventan valores nuevos sin actualizar los tres documentos.
- Los requisitos de correo masivo (FR-40 a FR-54) viven en `11-correo-masivo.md` y están todos en `Won't` (ADR-0013).

## 1. Resumen

| ID | Requisito | Prioridad |
|---|---|---|
| FR-01 | Autenticación de productos por API key | Must |
| FR-02 | Administración de tenants | Must |
| FR-03 | Emisión, rotación y revocación de API keys | Must |
| FR-04 | Gestión de plantillas y versiones borrador | Must |
| FR-05 | Publicación e inmutabilidad de versiones | Must |
| FR-06 | Previsualización (render sin enviar) | Should |
| FR-07 | Solicitud de envío transaccional | Must |
| FR-08 | Idempotencia de la solicitud de envío | Must |
| FR-09 | Validación de remitente y destinatario | Must |
| FR-10 | Bloqueo por lista de supresión | Must |
| FR-11 | Encolado persistente y respuesta asíncrona | Must |
| FR-12 | Worker de envío | Must |
| FR-13 | Reintentos con backoff y clasificación de errores | Must |
| FR-14 | Integración con proveedor vía `EmailSender` | Must |
| FR-15 | Modo desarrollo local con SMTP (Mailpit) | Must |
| FR-16 | Recepción de webhooks del proveedor | Must |
| FR-17 | Actualización de estado e historial de eventos | Must |
| FR-18 | API de supresiones | Should |
| FR-19 | Consulta del estado de un envío | Must |
| FR-20 | Listado y filtrado de envíos | Should |
| FR-21 | Límites de tasa y cuotas por tenant | Must |
| FR-22 | Auditoría de acciones sensibles | Should |
| FR-23 | Retención y purga de datos | **Must** (rev. 2026-10, antes Should) |
| FR-24 | Observabilidad: health, métricas y logs correlacionados | Must |
| FR-25 | Documentación OpenAPI/Swagger | Must |
| FR-26 | Cancelación de un mensaje aún encolado | Could |
| FR-27 | Envío con contenido inline sin plantilla | Won't (MVP) |
| FR-28 | ~~Adjuntos, envíos programados, webhooks salientes~~ | **DEPRECATED** (rev. 2026-10) → dividido en FR-30, FR-31 y FR-32 |
| FR-29 | Borrado de datos de un titular (derecho de supresión) | Must (nuevo) |
| FR-30 | Adjuntos acotados (PDF/XML) | **Won't (MVP)** (owner 2026-10-05: ningún producto envía adjuntos; ADR-0015 Rejected) |
| FR-31 | Envío diferido (`sendAt`) | **Should** (nuevo; owner 2026-10-05) |
| FR-32 | Webhooks salientes hacia los productos | Won't (MVP) (nuevo) |
| FR-33 | Ámbitos (scopes) y restricción de origen de API keys | Must (nuevo) |
| FR-34 | Detección de anomalías y pausa automática de envío | Must (nuevo) |
| FR-35 | Envío por lote (`POST /v1/emails/batch`) | Should (nuevo) |
| FR-36 | Categoría de plantilla y prioridad en la cola | Must (nuevo) |
| FR-37 | Plantillas por idioma (`es-CR` por defecto, `en` disponible) | Should (nuevo; ADR-0018) |

## 2. Máquina de estados de un mensaje (rev. 2026-10)

```
                     ┌──────────┐  cancelar (FR-26)   ┌───────────┐
                     │  QUEUED  │────────────────────▶│ CANCELED  │ (terminal)
                     └────┬─────┘                     └───────────┘
        worker toma el job│  ▲ error transitorio (attempts < MAX y dentro de la ventana)
                          ▼  │
                    ┌──────────┐   error permanente / intentos agotados / ventana agotada   ┌────────┐
                    │ SENDING  │────────────────────────────────────────────────────────────▶│ FAILED │ (terminal)
                    └────┬─────┘                                                             └────────┘
   proveedor acepta (id) │                                                                        ▲
                         ▼                                    email.failed / email.suppressed     │
                    ┌──────────┐──────────────────────────────────────────────────────────────────┘
                    │   SENT   │
                    └────┬─────┘
                         │ email.delivered
                         ▼
                    ┌───────────┐  email.bounced (asíncrono)  ┌───────────┐
                    │ DELIVERED │────────────────────────────▶│  BOUNCED  │ (terminal)
                    │ (estable) │  email.complained           ├───────────┤
                    └───────────┘────────────────────────────▶│ COMPLAINED│ (terminal)
                                                              └───────────┘
   Desde SENT también se puede pasar directamente a BOUNCED o COMPLAINED.
```

Reglas:

- **Orden de precedencia** (una transición solo avanza): `QUEUED (0) < SENDING (1) < SENT (2) < DELIVERED (3) < BOUNCED = COMPLAINED (4)`. `FAILED` y `CANCELED` son terminales y solo se alcanzan desde `QUEUED`/`SENDING`, o desde `SENT` por `email.failed`/`email.suppressed`.
- **Terminales:** `BOUNCED`, `COMPLAINED`, `FAILED`, `CANCELED`. **Estable:** `DELIVERED` (puede pasar todavía a `BOUNCED` o `COMPLAINED`, porque una queja siempre llega después de la entrega).
- `SENT` sin ningún evento durante `SENT_FINAL_AFTER_HOURS` (default 72 h) se considera final a efectos de métricas y de purga de variables; el estado no cambia.
- `opened` y `clicked` **no** son estados: se registran como eventos y como marcas de tiempo (`first_opened_at`, `first_clicked_at`).
- Un evento que no hace avanzar el estado (llega tarde o desordenado) **se guarda igualmente** y **sus efectos secundarios se aplican igual** (p. ej., crear la supresión ante una queja). Cambiar el estado y crear la supresión son efectos independientes.
- Un tenant suspendido o en pausa (FR-02, FR-34) **no** cambia el estado de sus mensajes: los `QUEUED` quedan retenidos.

### 2.1 Mapeo de eventos del proveedor (Resend) (rev. 2026-10)

Tipos verificados en la documentación de Resend el 2026-10-05.

| Evento del proveedor | `email_event.type` | Efecto en el estado | Otros efectos |
|---|---|---|---|
| `email.sent` | `SENT` | Ninguno (ya se marcó `SENT` al aceptar la API) | — |
| `email.delivered` | `DELIVERED` | `SENT → DELIVERED` | `delivered_at` |
| `email.delivery_delayed` | `DELIVERY_DELAYED` | Ninguno | Métrica de demoras por tenant |
| `email.bounced` | `BOUNCED` | `SENT/DELIVERED → BOUNCED` | Supresión `HARD_BOUNCE` **global** (ADR-0012) |
| `email.complained` | `COMPLAINED` | `SENT/DELIVERED → COMPLAINED` | Supresión `COMPLAINT` **global** (ADR-0012); alerta |
| `email.failed` | `FAILED` | `SENT → FAILED` (`PROVIDER_FAILED`) | — |
| `email.suppressed` | `SUPPRESSED` | `SENT → FAILED` (`PROVIDER_SUPPRESSED`) | Supresión espejo `PROVIDER` **global** |
| `email.opened` | `OPENED` | Ninguno | `first_opened_at`, `open_count` (solo si el seguimiento está activo) |
| `email.clicked` | `CLICKED` | Ninguno | `first_clicked_at`, `click_count` (ídem) |
| `suppression.added` / `suppression.removed` | `OTHER` | Ninguno | Sincroniza la supresión espejo `PROVIDER` |
| Cualquier otro (`email.scheduled`, `email.received`, `contact.*`, `domain.*`…) | `OTHER` | Ninguno | Se guarda y se ignora |

---

## 3. Requisitos detallados

### FR-01 — Autenticación de productos por API key (Must)

Cada solicitud a `/v1/**` se autentica con `Authorization: Bearer <api_key>`. La clave identifica al tenant.

- **AC-01.1** — *Dado* una API key activa, *cuando* se llama a cualquier endpoint `/v1/**`, *entonces* la petición se resuelve en el contexto de ese tenant y solo ve sus datos. **(rev. 2026-10)** El contexto se fija en la base de datos con `set_config('app.tenant_id', …, true)` al inicio de cada transacción, y las políticas RLS lo aplican (ADR-0008).
- **AC-01.2** — *Dado* una petición sin cabecera `Authorization` o con formato inválido, *entonces* la respuesta es `401` con `code = UNAUTHENTICATED` y no se revela si la clave existe o no.
- **AC-01.3** — *Dado* una key revocada, expirada o de un tenant `SUSPENDED`, *entonces* la respuesta es `401` (key) o `403 TENANT_SUSPENDED`, y el intento se registra en auditoría.
- **AC-01.4** — La comparación del secreto se hace sobre su **hash** (nunca en claro) y en tiempo constante.
- **AC-01.5** — *Cuando* una key se usa con éxito, *entonces* `last_used_at` se actualiza (con granularidad de minuto, para no escribir en cada request).
- **AC-01.6** — La API key nunca aparece en logs, trazas, mensajes de error ni en respuestas (salvo en el momento de su creación, FR-03).
- **AC-01.7** — **(rev. 2026-10)** *Dado* una key con ámbitos (FR-33), *cuando* llama a un endpoint que exige un ámbito que no tiene, *entonces* `403 INSUFFICIENT_SCOPE`.

### FR-02 — Administración de tenants (Must)

Endpoints `/admin/v1/**` protegidos por una credencial de administrador distinta de las API keys de tenant.

- **AC-02.1** — *Dado* una credencial de administración válida (`ADMIN_API_KEYS`), *cuando* se crea un tenant con `slug`, `name`, `fromEmail`, `fromName`, `allowedFromDomains`, `allowedLinkHosts`, `locale`, `timezone` y límites, *entonces* responde `201` con el tenant creado.
- **AC-02.2** — *Dado* un `slug` ya existente, *entonces* `409 TENANT_SLUG_TAKEN`.
- **AC-02.3** — **(rev. 2026-10)** Se puede suspender y reactivar un tenant. Mientras está suspendido, sus envíos nuevos se rechazan con `403 TENANT_SUSPENDED` y los ya encolados **permanecen en `QUEUED`** sin enviarse (el worker no toma mensajes de tenants suspendidos o en pausa). Al reactivarlo, la cola se drena sola. Ningún mensaje pasa a `FAILED` por la suspensión.
- **AC-02.4** — Una petición `/admin/v1/**` con una API key de tenant responde `403 ADMIN_REQUIRED`.
- **AC-02.5** — Los valores por defecto de límites, retención, `locale` (`es-CR`) y `timezone` (`America/Costa_Rica`) se aplican si no se especifican.
- **AC-02.6** — **(rev. 2026-10)** La alerta de antigüedad de cola (AC-24.4) excluye los mensajes de tenants suspendidos o en pausa; se reportan en una métrica aparte (`email_queue_held_messages`).

### FR-03 — Emisión, rotación y revocación de API keys (Must)

- **AC-03.1** — **(rev. 2026-10)** *Cuando* se emite una key para un tenant, *entonces* la respuesta `201` incluye el secreto **una sola vez**, con formato `esk_{env}_{prefix}_{secret}`: `prefix` de 8 caracteres Base62 (se regenera si colisiona) y `secret` de 43 caracteres Base62 (≥ 256 bits de un CSPRNG). En base de datos solo quedan el prefijo y el hash.
- **AC-03.2** — *Dado* un tenant, *cuando* se consultan sus keys, *entonces* se listan `prefix`, `name`, `status`, `scopes`, `allowedCidrs`, `createdAt`, `expiresAt` y `lastUsedAt`, nunca el secreto.
- **AC-03.3** — Un tenant puede tener **varias keys activas** simultáneamente (rotación con solapamiento).
- **AC-03.4** — **(rev. 2026-10-06, ADR-0019)** *Cuando* se revoca una key, *entonces* deja de autenticar en la siguiente petición (no hay caché de keys) y queda `REVOKED` con `revoked_at`.
- **AC-03.5** — Una key con `expiresAt` en el pasado no autentica.
- **AC-03.6** — Emitir, revocar y rotar quedan registrados en auditoría (FR-22) con el actor administrador.
- **AC-03.7** — **(rev. 2026-10)** Los ámbitos y CIDR de una key se fijan al emitirla y no se modifican; para cambiarlos se emite otra (FR-33).

### FR-04 — Gestión de plantillas y versiones borrador (Must)

Requiere el ámbito `templates:write` (FR-33).

- **AC-04.1** — **(rev. 2026-10)** *Cuando* un tenant crea una plantilla con `key` (slug kebab-case único dentro del tenant), `name`, `description` y `category` (`SECURITY` \| `TRANSACTIONAL` \| `NOTICE`), *entonces* responde `201`; si la `key` ya existe, `409 TEMPLATE_KEY_TAKEN`.
- **AC-04.2** — **(rev. 2026-10)** *Cuando* se crea una versión con `subjectTemplate`, `htmlTemplate`, `textTemplate` (opcional) y `variablesSchema`, *entonces* se crea en estado `DRAFT` con número correlativo empezando en 1. `variablesSchema` es opcional en borrador y **obligatorio para publicar** (AC-05.5).
- **AC-04.3** — Una versión `DRAFT` puede modificarse o eliminarse; una `PUBLISHED`, no (FR-05).
- **AC-04.4** — *Dado* una plantilla con sintaxis inválida, *entonces* la creación falla con `422 TEMPLATE_SYNTAX_ERROR`, indicando línea y detalle.
- **AC-04.5** — **(rev. 2026-10)** Las plantillas son **logic-less** (Handlebars) con escapado HTML por defecto en `htmlTemplate`. Se rechazan con `422 UNSAFE_TEMPLATE_CONSTRUCT`: la interpolación sin escapar en **cualquiera** de sus formas (`{{{ }}}` y `{{& }}`), los parciales (`{{> }}`, `{{#> }}`) y cualquier helper fuera de la lista blanca (`if`, `unless`, `each`, `with`, `formatDate`, `formatNumber`, `formatMoney`).
- **AC-04.6** — Un tenant solo ve y modifica **sus** plantillas; intentar acceder a la de otro devuelve `404` (no `403`, para no revelar existencia).
- **AC-04.7** — **(rev. 2026-10)** Linter de contexto HTML al guardar: se rechaza con `422 UNSAFE_TEMPLATE_CONSTRUCT` toda variable dentro de `<script>`, `<style>`, un atributo `style`, un atributo de evento (`on*`) o un atributo sin comillas. Se rechaza también cualquier etiqueta `<script>`, `<iframe>`, `<object>`, `<embed>` o `<form>` en la plantilla.
- **AC-04.8** — **(rev. 2026-10)** Toda variable usada en un atributo `href` o `src` debe estar declarada en `variablesSchema` con `"format": "uri"`; si no, `422 UNSAFE_TEMPLATE_CONSTRUCT` al publicar.
- **AC-04.9** — **(rev. 2026-10)** Escapado por contexto: `htmlTemplate` se renderiza con escapado HTML; `subjectTemplate` y `textTemplate` se renderizan **sin** escapado HTML y con eliminación de `\r` y `\n` en el asunto. Test: la variable `"Ana & Juan"` produce `Ana &amp; Juan` en el HTML y `Ana & Juan` en el asunto y en el texto.
- **AC-04.10** — **(rev. 2026-10)** Límites: fuente de `htmlTemplate` ≤ 256 KB (`422 VALIDATION_ERROR`); si el HTML renderizado de la previsualización supera 100 KB, la respuesta incluye un aviso `warnings: ["HTML_MAY_BE_CLIPPED"]`.
- **AC-04.11** — **(rev. 2026-10)** El seguimiento de aperturas y clics (`trackingEnabled`) está desactivado por defecto, solo se puede activar en plantillas de categoría `NOTICE`, y se rechaza con `422 VALIDATION_ERROR` en `SECURITY`.

### FR-05 — Publicación e inmutabilidad de versiones (Must)

- **AC-05.1** — *Cuando* se publica una versión `DRAFT`, *entonces* pasa a `PUBLISHED` con `published_at`, y cualquier intento posterior de modificarla o borrarla devuelve `409 VERSION_IMMUTABLE`.
- **AC-05.2** — Publicar una versión nueva no altera las anteriores: la anterior publicada pasa a `ARCHIVED`, pero **sigue siendo utilizable** si un envío la referencia explícitamente.
- **AC-05.3** — *Dado* un envío sin `templateVersion`, *entonces* se usa la última versión `PUBLISHED` y se **fija** (`template_version_id`) en el mensaje, de modo que un reintento posterior renderiza exactamente lo mismo.
- **AC-05.4** — *Dado* una plantilla sin ninguna versión publicada, *cuando* se intenta enviar con ella, *entonces* `422 TEMPLATE_NOT_PUBLISHED`.
- **AC-05.5** — **(rev. 2026-10)** Publicar exige `variablesSchema` válido según el subconjunto de `06` §3.3; sin él, `422 VALIDATION_ERROR`. Publicar queda en auditoría.

### FR-06 — Previsualización (render sin enviar) (Should)

- **AC-06.1** — *Cuando* se solicita la previsualización de una plantilla/versión con variables de ejemplo, *entonces* responde `200` con `subject`, `html`, `text` y `warnings` renderizados, sin crear ningún mensaje ni enviar nada.
- **AC-06.2** — *Dado* variables faltantes respecto a `variablesSchema`, *entonces* responde `422 TEMPLATE_VARIABLES_INVALID` con la lista de las que faltan.
- **AC-06.3** — El render de la previsualización usa exactamente el mismo componente que el envío real (mismo escapado, mismos helpers).

### FR-07 — Solicitud de envío transaccional (Must)

`POST /v1/emails` con `templateKey`, `to`, `variables` y campos opcionales (`templateVersion`, `locale` (FR-37), `fromName`, `replyTo`, `cc`, `bcc`, `tags`, `metadata`, `sendAt` (FR-31)). Requiere el ámbito `emails:send`.

- **AC-07.1** — *Dado* una solicitud válida, *entonces* la respuesta es `202` con `id`, `status = QUEUED` y `createdAt`, en **≤ 150 ms (p95)**, sin haber contactado al proveedor.
- **AC-07.2** — *Dado* campos obligatorios ausentes o mal formados, *entonces* `422 VALIDATION_ERROR` con el detalle por campo, y no se crea mensaje.
- **AC-07.3** — *Dado* un `templateKey` inexistente en el tenant, *entonces* `404 TEMPLATE_NOT_FOUND`.
- **AC-07.4** — *Dado* variables que no cumplen `variablesSchema` de la versión, *entonces* `422 TEMPLATE_VARIABLES_INVALID`.
- **AC-07.5** — **(rev. 2026-10)** La API valida las variables **y** renderiza la plantilla de forma síncrona, descartando el resultado (un error de render devuelve `422 TEMPLATE_VARIABLES_INVALID` con el detalle). El worker vuelve a renderizar con la versión fijada al enviar. El resultado no se guarda salvo `storeRenderedContent`.
- **AC-07.6** — El cuerpo de la petición no puede superar `MAX_REQUEST_BYTES` (default 256 KB) → `413 PAYLOAD_TOO_LARGE`. **(rev. 2026-10)** Un campo `attachments` se rechaza con `422 VALIDATION_ERROR` mientras FR-30 siga en Won't.
- **AC-07.7** — `cc` y `bcc` admiten hasta 5 direcciones cada uno; `to` es **una sola** dirección (un mensaje = un destinatario principal, para que el estado sea inequívoco).
- **AC-07.8** — **(rev. 2026-10)** Toda variable con `"format": "uri"` debe usar el esquema `https` y tener un host que coincida con `tenant.allowedLinkHosts` (coincidencia exacta o subdominio de una entrada). Si no, `422 UNSAFE_URL` indicando la variable.
- **AC-07.9** — **(rev. 2026-10)** *Dado* un tenant en pausa (FR-34), *entonces* `403 TENANT_SENDING_PAUSED` y no se crea mensaje.

### FR-08 — Idempotencia (Must)

- **AC-08.1** — *Dado* una petición con `Idempotency-Key`, *cuando* se repite con el **mismo cuerpo** (hash SHA-256 del payload canónico), *entonces* responde `200` con el mensaje original (mismo `id`) y **no** se crea ni se envía un segundo correo.
- **AC-08.2** — *Dado* la misma `Idempotency-Key` con un cuerpo distinto, *entonces* `409 IDEMPOTENCY_KEY_REUSED`.
- **AC-08.3** — La unicidad es por tenant: dos tenants pueden usar la misma clave sin interferir.
- **AC-08.4** — *Dado* dos peticiones concurrentes con la misma clave, *entonces* exactamente una crea el mensaje y la otra recibe el original (restricción `UNIQUE` en base de datos con `INSERT … ON CONFLICT DO NOTHING`, no solo comprobación previa).
- **AC-08.5** — **(rev. 2026-10)** Las claves se conservan `IDEMPOTENCY_RETENTION_HOURS` (default 24 h); pasado ese plazo se ponen a `NULL`. Máximo 256 caracteres ASCII imprimibles; si no, `422 VALIDATION_ERROR`.
- **AC-08.6** — Si el cliente no envía `Idempotency-Key`, la petición se acepta igual y se cuenta en la métrica `email_requests_without_idempotency_total`. El contrato de integración (`07` §11) exige enviarla.
- **AC-08.7** — **(rev. 2026-10)** La comprobación de idempotencia se hace **antes** del rate limit: una repetición que devuelve el original (`200`) no consume rate limit ni cuota.

### FR-09 — Validación de remitente y destinatario (Must)

- **AC-09.1** — Las direcciones se validan sintácticamente (RFC 5322 básico, sin comentarios ni direcciones entre comillas) y se normalizan (trim, todo en minúsculas para comparar); las inválidas → `422 INVALID_EMAIL_ADDRESS`.
- **AC-09.2** — El remitente efectivo es el del tenant; si la petición envía un `from` propio, debe pertenecer a `allowedFromDomains` del tenant; si no, `403 FROM_DOMAIN_NOT_ALLOWED`.
- **AC-09.3** — Cualquier campo de dirección, asunto, nombre (incluido `fromName`) o `tags` que contenga `\r` o `\n` se rechaza con `422 HEADER_INJECTION_DETECTED`.
- **AC-09.4** — En entornos no productivos se puede exigir una allowlist de dominios de destino (`ALLOWED_RECIPIENT_DOMAINS`); fuera de ella, `403 RECIPIENT_NOT_ALLOWED_IN_ENV`. **(rev. 2026-10)** En `staging` la allowlist es obligatoria: la app no arranca con `APP_ENV=staging` y `ALLOWED_RECIPIENT_DOMAINS` vacía.
- **AC-09.5** — **(rev. 2026-10)** La petición puede sobrescribir solo el **nombre** visible del remitente (`fromName`, ≤ 80 caracteres, sin CR/LF ni `<`, `>`, `@`), para que un producto como Colmena muestre "Negocio X vía Colmena"; la dirección sigue sujeta a AC-09.2.

### FR-10 — Bloqueo por lista de supresión (Must)

- **AC-10.1** — **(rev. 2026-10)** *Dado* un destinatario suprimido (en `to`, `cc` o `bcc`) para ese tenant o globalmente, *cuando* se solicita un envío, *entonces*:
  - si el suprimido está en `to`, la API responde `202` y el mensaje se crea directamente en `FAILED` con `failure_code = SUPPRESSED` y `suppressionReason` (`HARD_BOUNCE`, `COMPLAINT`, `PROVIDER` o `MANUAL`), sin llamar al proveedor. **(rev. 2026-10)** Con ese motivo el producto puede usar una dirección alternativa del usuario (p. ej., para recuperar la cuenta tras una queja); el email-service no elige direcciones alternativas;
  - si solo hay suprimidos en `cc`/`bcc`, se eliminan de esas listas, el mensaje se encola y la respuesta incluye `droppedRecipients`.
  - Alternativa configurable `SUPPRESSION_REJECT_MODE=reject` → `422 RECIPIENT_SUPPRESSED`.
- **AC-10.2** — **(rev. 2026-10)** Alcance de la supresión (ADR-0012): `HARD_BOUNCE`, `COMPLAINT` y `PROVIDER` son **globales** (aplican a todos los tenants); `MANUAL` es **por tenant**. *Dado* una dirección suprimida globalmente por un rebote duro de un tenant A, *cuando* el tenant B envía a esa dirección, *entonces* también queda `FAILED`/`SUPPRESSED`.
- **AC-10.3** — Un rebote duro (`email.bounced`) o una queja (`email.complained`) recibidos por webhook crean automáticamente la supresión, **aunque el evento no cambie el estado del mensaje** (§2).
- **AC-10.4** — **(rev. 2026-10)** `email.delivery_delayed` (problema temporal) **no** suprime: solo registra el evento `DELIVERY_DELAYED`.
- **AC-10.5** — La comprobación es insensible a mayúsculas/minúsculas en el dominio y en la parte local (decisión simple y explícita).
- **AC-10.6** — **(rev. 2026-10)** El worker vuelve a comprobar la supresión justo antes de llamar al proveedor (AC-12.2), de modo que una supresión creada entre la aceptación y el envío también se respeta.

### FR-11 — Encolado persistente y respuesta asíncrona (Must)

- **AC-11.1** — El mensaje se persiste en la misma transacción que lo encola (la fila **es** el trabajo en cola): no existe el estado "aceptado pero no encolado".
- **AC-11.2** — *Dado* que el proveedor está caído, *entonces* `POST /v1/emails` sigue respondiendo `202` normalmente.
- **AC-11.3** — **(rev. 2026-10)** *Dado* un reinicio del servicio con mensajes en vuelo, *entonces* ningún mensaje se pierde ni queda bloqueado: los `SENDING` con lock vencido (`lock_expires_at < now()`) vuelven a `QUEUED` **conservando el `attempts` ya incrementado al tomarlos** (ADR-0010). Un mensaje que agota `MAX_ATTEMPTS` por reclamaciones sucesivas termina en `FAILED`/`MAX_ATTEMPTS_EXCEEDED`.
- **AC-11.4** — La toma de trabajo usa `SELECT ... FOR UPDATE SKIP LOCKED`, de forma que varios workers no procesan el mismo mensaje.
- **AC-11.5** — **(rev. 2026-10)** La toma de trabajo es una transacción corta que termina (`COMMIT`) **antes** de cualquier llamada de red. Ninguna llamada al proveedor ocurre con locks de fila abiertos.

### FR-12 — Worker de envío (Must)

- **AC-12.1** — **(rev. 2026-10)** El worker toma como máximo tantos mensajes como huecos libres tenga su concurrencia (`WORKER_CONCURRENCY`, default 4): mensajes `QUEUED` con `next_attempt_at <= now()` de tenants `ACTIVE` y no pausados, ordenados por `priority` y luego por `next_attempt_at`.
- **AC-12.2** — Por cada mensaje: lo marca `SENDING` con `lock_token` y `lock_expires_at`, renderiza la versión fijada, vuelve a comprobar la supresión y el estado del tenant, y llama a `EmailSender` con `idempotencyKey = message.id`.
- **AC-12.3** — **(rev. 2026-10)** *Dado* un envío aceptado por el proveedor, *entonces* el mensaje pasa a `SENT` con `provider_message_id` y `sent_at` mediante `UPDATE … WHERE id = :id AND lock_token = :token AND status = 'SENDING'`. Si el `UPDATE` afecta 0 filas (el lock se perdió), el resultado se registra en `attempt_log`, se emite la métrica `email_lost_lock_total` y **no** se vuelve a enviar.
- **AC-12.4** — El worker corre en el mismo artefacto que la API, activado por perfil/variable (`APP_ROLE=api|worker|all`), y puede escalarse a N instancias sin duplicar envíos.
- **AC-12.5** — Un error de render (plantilla rota con esos datos) marca `FAILED` con `failure_code = RENDER_ERROR` y **no** se reintenta.
- **AC-12.6** — **(rev. 2026-10)** Cada instancia de worker respeta un límite de tasa hacia el proveedor de `WORKER_PROVIDER_RPS` (default 8). La suma de `WORKER_PROVIDER_RPS` de todas las instancias que comparten cuenta de proveedor no puede superar el límite contratado (Resend: 10 rps por equipo por defecto). Ver NFR-21.
- **AC-12.7** — **(rev. 2026-10)** El cuerpo enviado al proveedor es determinista para un mismo mensaje (misma versión, mismas variables, mismo orden de cabeceras), de forma que la idempotencia del proveedor reconoce el reintento. Las plantillas no tienen acceso a "la hora actual".

### FR-13 — Reintentos con backoff y clasificación de errores (Must)

- **AC-13.1** — Errores **transitorios** (timeout, 429, 5xx del proveedor, error de red) devuelven el mensaje a `QUEUED` con `next_attempt_at` según backoff exponencial con jitter ±20 %: 1 min, 5 min, 15 min, 1 h, 6 h (configurable en `RETRY_BACKOFF_SECONDS`).
- **AC-13.2** — Errores **permanentes** (dirección inválida, 4xx no-429 del proveedor, contenido rechazado) marcan `FAILED` inmediatamente con `failure_code` y `failure_detail`, sin reintentos.
- **AC-13.3** — **(rev. 2026-10)** `MAX_ATTEMPTS` (default 6) = número de esperas de `RETRY_BACKOFF_SECONDS` + 1; la aplicación no arranca si no coincide. Agotados los intentos, el mensaje queda `FAILED` con `failure_code = MAX_ATTEMPTS_EXCEEDED`.
- **AC-13.4** — Cada intento queda registrado (contador y último error); el mensaje conserva el historial de errores en `attempt_log` (jsonb, últimos 10 intentos).
- **AC-13.5** — *Dado* un `429` del proveedor con `Retry-After`, *entonces* se respeta ese valor en lugar del backoff calculado.
- **AC-13.6** — **(rev. 2026-10)** El reintento **nunca** produce un segundo correo dentro de la ventana de idempotencia del proveedor: todo intento lleva `Idempotency-Key = message.id`; si ya hay `provider_message_id`, no se vuelve a llamar.
- **AC-13.7** — **(rev. 2026-10)** La suma de `RETRY_BACKOFF_SECONDS` × 1,2 (jitter) + `MAX_ATTEMPTS` × `LOCK_TIMEOUT_SECONDS` debe ser < 23 h (la ventana de idempotencia de Resend es de 24 h); la app no arranca si no se cumple. Un mensaje cuyo primer intento fue hace más de 23 h no se vuelve a intentar: pasa a `FAILED` con `RETRY_WINDOW_EXCEEDED`.
- **AC-13.8** — **(rev. 2026-10)** Un `409` del proveedor por clave de idempotencia reutilizada con otro cuerpo se clasifica como permanente (`PROVIDER_IDEMPOTENCY_CONFLICT`) y dispara una alerta, porque indica un render no determinista.
- **AC-13.9** — **(rev. 2026-10)** *Circuit breaker:* tras `CIRCUIT_BREAKER_FAILURES` (default 5) errores transitorios consecutivos, el worker deja de llamar al proveedor durante `CIRCUIT_BREAKER_OPEN_SECONDS` (default 60) y luego prueba un único envío. Los mensajes no se marcan como fallidos durante la apertura; simplemente no se toman.

### FR-14 — Integración con proveedor vía `EmailSender` (Must)

- **AC-14.1** — Existe una interfaz `EmailSender` con una operación `send(OutboundEmail) → SendResult` y las implementaciones `ResendEmailSender` (o el proveedor elegido), `SmtpEmailSender` y `NoopEmailSender` (tests).
- **AC-14.2** — La implementación activa se elige por `MAIL_PROVIDER` (`resend|smtp|noop`) sin cambios de código ni de arquitectura.
- **AC-14.3** — El resto del sistema **no** conoce el proveedor: ni tipos, ni excepciones, ni cabeceras propias del SDK cruzan esa frontera (se traducen a `SendResult`/`SendException` propios).
- **AC-14.4** — **(rev. 2026-10)** Timeouts de conexión y lectura configurables (`PROVIDER_CONNECT_TIMEOUT_MS` = 3 000, `PROVIDER_READ_TIMEOUT_MS` = 10 000). La correlación se hace con `provider_message_id` y con la etiqueta del proveedor `message_id=<uuid>`; no se usa `X-Entity-Ref-ID` para correlacionar.
- **AC-14.5** — Añadir un proveedor nuevo consiste en crear una implementación y registrarla; no requiere tocar el worker.
- **AC-14.6** — **(rev. 2026-10)** `OutboundEmail` incluye `idempotencyKey`; las implementaciones que lo soporten lo envían (Resend: cabecera `Idempotency-Key`).
- **AC-14.7** — **(rev. 2026-10)** La verificación de webhooks del proveedor se expone como el puerto `WebhookVerifier` en el módulo `provider`; el módulo `events` lo consume sin conocer al proveedor.

### FR-15 — Modo desarrollo local con SMTP (Must)

- **AC-15.1** — *Dado* `MAIL_PROVIDER=smtp` apuntando a Mailpit, *cuando* se envía un correo, *entonces* aparece en la interfaz de Mailpit con el HTML renderizado.
- **AC-15.2** — `docker compose up` levanta app + PostgreSQL + Mailpit y permite un envío de prueba de extremo a extremo **sin credenciales de ningún proveedor**.
- **AC-15.3** — El modo SMTP está prohibido en producción: si `APP_ENV=production` y `MAIL_PROVIDER=smtp`, la aplicación **no arranca** (fail-fast) salvo que se active explícitamente `ALLOW_SMTP_IN_PRODUCTION=true`.
- **AC-15.4** — SMTP con usuario/contraseña **no** es el mecanismo de producción (ADR-0003, ratificado por ADR-0016); en modo SMTP no hay webhooks y el estado se queda en `SENT`.

### FR-16 — Recepción de webhooks del proveedor (Must)

`POST /webhooks/{provider}` público, sin API key de tenant.

- **AC-16.1** — **(rev. 2026-10)** *Dado* un webhook con firma válida (verificada sobre el **cuerpo crudo**, sin re-serializar, con el secreto del endpoint en `MAIL_WEBHOOK_SIGNING_SECRET`; en Resend, cabeceras `svix-id`, `svix-timestamp`, `svix-signature`), *entonces* responde `204` y procesa el evento.
- **AC-16.2** — *Dado* una firma inválida o ausente, *entonces* `401`, el cuerpo **no** se procesa y se registra el intento (agregado por IP y ventana).
- **AC-16.3** — *Dado* un evento con timestamp fuera de la ventana de tolerancia (default ±5 min), *entonces* se rechaza con `401` (protección contra replay).
- **AC-16.4** — **(rev. 2026-10)** *Dado* un evento ya recibido (mismo `svix-id`, guardado como `provider_event_id`), *entonces* se ignora de forma idempotente y responde `204`.
- **AC-16.5** — *Dado* un evento cuyo `provider_message_id` no corresponde a ningún mensaje conocido, *entonces* se guarda como huérfano y responde `204` (nunca 5xx: provocaría reintentos infinitos del proveedor). Los huérfanos se purgan a los 30 días.
- **AC-16.6** — El procesamiento responde en ≤ 2 s; el trabajo pesado (si lo hubiera) se hace en segundo plano.
- **AC-16.7** — **(rev. 2026-10)** Ante un error de base de datos (no de correlación), el endpoint sí responde `503`, para que el proveedor reintente; esto no contradice AC-16.5.

### FR-17 — Actualización de estado e historial de eventos (Must)

- **AC-17.1** — **(rev. 2026-10)** Cada evento válido se guarda en `email_event` con tipo, fecha del proveedor, fecha de recepción y un **payload minimizado**: identificadores, tipo, timestamps y datos de rebote/queja; se eliminan `to`, `from`, `subject`, cabeceras y la *query string* de las URL clicadas.
- **AC-17.2** — **(rev. 2026-10)** Los eventos actualizan el estado del mensaje según el orden de precedencia de §2. Un evento que no hace avanzar el estado se guarda igualmente y aplica sus otros efectos (§2.1).
- **AC-17.3** — `opened`/`clicked` no cambian el estado; actualizan `first_opened_at`/`first_clicked_at` y sus contadores.
- **AC-17.4** — **(rev. 2026-10)** Un rebote duro o una queja crean la supresión (FR-10) en la misma transacción en que se guarda el evento, **independientemente** de si el estado cambia.
- **AC-17.5** — **(rev. 2026-10)** *Dado* un mensaje en `DELIVERED`, *cuando* llega `email.complained`, *entonces* el estado pasa a `COMPLAINED` y se crea la supresión global `COMPLAINT`.
- **AC-17.6** — **(rev. 2026-10)** Los eventos `suppression.added`/`suppression.removed` y `email.suppressed` mantienen sincronizada la supresión espejo `PROVIDER` (ADR-0012).

### FR-18 — API de supresiones (Should)

- **AC-18.1** — `GET /v1/suppressions` lista las supresiones que afectan al tenant (las suyas `MANUAL` y las globales), con filtro por motivo y paginación. Las globales se muestran con la dirección enmascarada (`a***@dominio.com`) y `scope = GLOBAL`. Requiere `emails:read`.
- **AC-18.2** — `POST /v1/suppressions` añade una manualmente (`reason = MANUAL`, `scope = TENANT`). Requiere `suppressions:write`.
- **AC-18.3** — **(rev. 2026-10)** `DELETE /v1/suppressions/{email}` elimina solo supresiones `MANUAL` del propio tenant y queda en auditoría. Intentar borrar una supresión global → `403 ADMIN_REQUIRED`; las globales se gestionan en `/admin/v1/suppressions`.
- **AC-18.4** — Un tenant no puede ver ni borrar supresiones `MANUAL` de otro.

### FR-19 — Consulta del estado de un envío (Must)

- **AC-19.1** — `GET /v1/emails/{id}` devuelve estado, plantilla y versión usada, destinatario, intentos, `providerMessageId`, marcas de tiempo, último error, `locale` efectivo y la lista de eventos. Requiere `emails:read`.
- **AC-19.2** — Un `id` de otro tenant devuelve `404`.
- **AC-19.3** — El contenido renderizado se incluye solo si el tenant tiene `storeRenderedContent = true` y aún no se ha purgado; las variables `x-sensitive` aparecen como `[REDACTED]`.

### FR-20 — Listado y filtrado de envíos (Should)

- **AC-20.1** — **(rev. 2026-10)** `GET /v1/emails` admite los filtros `status`, `templateKey`, `to` (destinatario), `tag`, `createdAfter` y `createdBefore` (fechas), con paginación por cursor; por defecto, los 20 más recientes primero.
- **AC-20.2** — **(rev. 2026-10)** Responde en ≤ 500 ms (p95) con el dataset de referencia: 4 tenants y 1 000 000 de filas por tenant, el 70 % del volumen total en uno de ellos.
- **AC-20.3** — Solo devuelve mensajes del tenant autenticado (verificado por test de aislamiento y por RLS).

### FR-21 — Límites de tasa y cuotas por tenant (Must)

- **AC-21.1** — **(rev. 2026-10)** Cada tenant tiene `rateLimitPerMinute` (default 60) y `dailyQuota` (default **1 000**; se fija explícitamente por tenant a ~3× su volumen diario esperado). Superarlos devuelve `429 RATE_LIMITED` con `Retry-After`.
- **AC-21.2** — El contador es compartido entre instancias de la API (se mantiene en PostgreSQL, no en la memoria del proceso).
- **AC-21.3** — Los límites son configurables por tenant sin redesplegar.
- **AC-21.4** — Los rechazos por límite se registran como métrica y en auditoría agregada (no una fila por rechazo).
- **AC-21.5** — **(rev. 2026-10)** El contador se incrementa en la **misma transacción** que inserta el mensaje y después de todas las validaciones: un envío rechazado (por validación, supresión en modo `reject` o límite) o una repetición idempotente no consume cuota. Un mensaje creado como `FAILED/SUPPRESSED` sí la consume.
- **AC-21.6** — **(rev. 2026-10)** En un lote (FR-35), cada elemento aceptado cuenta como un envío.

### FR-22 — Auditoría de acciones sensibles (Should)

- **AC-22.1** — **(rev. 2026-10)** Se auditan: creación, suspensión, pausa y reanudación de tenants; emisión y revocación de keys; publicación de plantillas; borrado de supresiones; borrados por titular (FR-29); fallos de autenticación repetidos; accesos a `/admin/v1/**`.
- **AC-22.2** — Cada entrada guarda actor (admin o `api_key_id`), acción, recurso, IP de origen, `requestId`, timestamp y metadatos; **nunca** secretos ni contenido de correos. Si el recurso es una dirección de correo, se guarda su `email_hash`.
- **AC-22.3** — Las entradas de auditoría no se actualizan ni se borran desde la aplicación (solo la purga por retención, FR-23).

### FR-23 — Retención y purga de datos (Must, rev. 2026-10)

- **AC-23.1** — Cada tenant tiene `retentionDays` (default 90); una tarea diaria borra los mensajes y eventos más antiguos.
- **AC-23.2** — La purga es idempotente, se ejecuta por lotes y deja traza del número de filas borradas.
- **AC-23.3** — Las supresiones **no** se purgan por antigüedad (son protección de reputación).
- **AC-23.4** — La auditoría tiene su propia retención (default 365 días).
- **AC-23.5** — **(rev. 2026-10)** `variables` se sustituye por `{}` (y se registra `variables_purged_at`) `VARIABLES_RETENTION_DAYS` (default 7) días después de que el mensaje llegue a un estado terminal o final por tiempo. Las variables marcadas `x-sensitive` en el esquema se eliminan **al pasar a `SENT`** o a un estado terminal.
- **AC-23.6** — **(rev. 2026-10; solo aplica si FR-30 se activa)** El contenido de los adjuntos (`email_attachment.content`) se elimina en cuanto el mensaje llega a `SENT` o a un estado terminal; los metadatos (nombre, tipo, tamaño, hash) se conservan hasta la purga del mensaje.
- **AC-23.7** — **(rev. 2026-10)** `rendered_*` se vacía a los 30 días. Los eventos huérfanos (`tenant_id` nulo) se purgan a los 30 días.

### FR-24 — Observabilidad (Must)

- **AC-24.1** — **(rev. 2026-10)** `/actuator/health/liveness` no depende de nada externo; `/actuator/health/readiness` depende solo de la base de datos. `/actuator/health` agrega además el indicador `provider`, calculado a partir de los resultados de los últimos 5 minutos (sin hacer *ping* al proveedor).
- **AC-24.2** — Métricas expuestas: mensajes por estado, latencia de aceptación, tiempo aceptación→`SENT`, profundidad de cola (`QUEUED` elegibles), antigüedad del mensaje elegible más viejo, mensajes retenidos por tenant pausado o suspendido, intentos, eventos por tipo, rechazos por rate limit, latencia del proveedor, estado del circuit breaker, tasa de rebote y de queja por tenant, y peticiones sin clave de idempotencia.
- **AC-24.3** — **(rev. 2026-10)** Todos los logs son JSON con `tenantSlug`, `messageId` y `requestId` cuando aplican; **nunca** incluyen el contenido del correo, variables ni secretos, y las direcciones aparecen enmascaradas (`a***@dominio.com`).
- **AC-24.4** — **(rev. 2026-10)** *Dado* que el mensaje elegible más antiguo supera `QUEUE_AGE_ALERT_SECONDS` (default 300), *entonces* el indicador `queue` de `/actuator/health` reporta el estado personalizado `DEGRADED`, mapeado a HTTP 200 para no matar instancias sanas, y se emite una alerta.

### FR-25 — Documentación OpenAPI (Must)

- **AC-25.1** — Swagger UI disponible en `/swagger-ui.html` y la spec en `/v3/api-docs`, con ejemplos de petición y respuesta por endpoint.
- **AC-25.2** — **(rev. 2026-10-06)** La spec se exporta por grupo a `contracts/email-service.openapi.json` (API de productos: `/v1/**` y `/webhooks/**`) y a `contracts/email-service-admin.openapi.json` (`/admin/v1/**`, AC-25.3); un test de CI falla si alguno difiere de la generada.
- **AC-25.3** — Los endpoints `/admin/v1/**` aparecen en un grupo separado.

### FR-26 — Cancelación de un mensaje encolado (Could)

- **AC-26.1** — `POST /v1/emails/{id}/cancel` sobre un mensaje `QUEUED` lo pasa a `CANCELED`; sobre cualquier otro estado, `409 MESSAGE_NOT_CANCELABLE`. Requiere `emails:send`.

### FR-27 — Contenido inline sin plantilla (Won't)

Documentado aquí para que ningún colaborador lo implemente sin ADR (ver `09-roadmap.md`).

### FR-28 — DEPRECATED (rev. 2026-10)

Agrupaba adjuntos, envíos programados y webhooks salientes como `Won't`. Se divide en FR-30, FR-31 y FR-32. Tras las respuestas del owner (2026-10-05): FR-30 (adjuntos) Won't, FR-31 (`sendAt`) Should y FR-32 (webhooks salientes) Won't.

### FR-29 — Borrado de datos de un titular (Must, nuevo)

`POST /admin/v1/data-subjects/erase` con `{ "email": "…", "reference": "solicitud ARCO #…" }`.

- **AC-29.1** — *Cuando* se ejecuta, *entonces* en todos los tenants se borran los mensajes cuyo `to_email` coincide, y sus eventos; la dirección se elimina de `cc`/`bcc` de otros mensajes; las supresiones conservan solo `email_hash` (la dirección en claro pasa a `NULL`), para seguir sin escribirle.
- **AC-29.2** — La respuesta indica cuántas filas se borraron por tabla; queda en auditoría con `email_hash` y `reference`, nunca con la dirección en claro.
- **AC-29.3** — La operación es idempotente y termina en < 60 s con el dataset de referencia.
- **AC-29.4** — El procedimiento documentado (`08` §6) incluye pedir el borrado al proveedor de correo y responder al titular dentro del plazo legal (5 días hábiles según la Ley 8968; a confirmar con asesoría).

### FR-30 — Adjuntos acotados (Won't (MVP); ADR-0015 Rejected)

> **(rev. 2026-10-05, owner):** ningún producto envía comprobantes ni otros adjuntos. Los criterios se conservan como diseño de referencia. Disparador para reactivarlo: un producto con un caso de uso real que no se resuelva con un enlace firmado.

- **AC-30.1** — `attachments` es un arreglo opcional de hasta 3 objetos `{ filename, contentType, contentBase64 }`. `contentType` ∈ {`application/pdf`, `application/xml`, `text/xml`}. La suma de tamaños decodificados es ≤ `MAX_ATTACHMENT_BYTES_TOTAL` (default 2 MB). Fuera de esos límites → `422 ATTACHMENT_INVALID` o `413 PAYLOAD_TOO_LARGE`.
- **AC-30.2** — Se comprueban los *magic bytes* (`%PDF-` para PDF; `<?xml` o `<` tras el BOM para XML); si no coinciden con `contentType` → `422 ATTACHMENT_INVALID`. `filename` tiene ≤ 100 caracteres, sin `/`, `\`, CR ni LF.
- **AC-30.3** — Los adjuntos se guardan en `email_attachment` (misma transacción que el mensaje) y su contenido se elimina al llegar el mensaje a `SENT` o a un estado terminal (AC-23.6).
- **AC-30.4** — Requiere el ámbito `emails:send`. El endpoint de lote (FR-35) no admite adjuntos.
- **AC-30.5** — Los productos solo adjuntan documentos **generados por ellos mismos** (comprobantes, recibos); nunca reenvían archivos subidos por usuarios (no hay antivirus en el MVP).

### FR-31 — Envío diferido `sendAt` (Should, nuevo)

- **AC-31.1** — `sendAt` (ISO-8601 con zona) fija `next_attempt_at`; debe estar entre `now()` y `now() + SEND_AT_MAX_DAYS` (default 30); si no, `422 SEND_AT_OUT_OF_RANGE`.
- **AC-31.2** — La versión de plantilla se fija al **aceptar**, no al enviar (documentado en `07`).
- **AC-31.3** — Un mensaje diferido se puede cancelar con FR-26 hasta que se tome.
- **AC-31.4** — La ventana de reintentos de AC-13.7 empieza a contar en el primer intento, no en la aceptación.

### FR-32 — Webhooks salientes hacia los productos (Won't, nuevo)

Los productos consultan el estado por `GET /v1/emails/{id}`. Disparador para reconsiderarlo: un producto necesita reaccionar a `BOUNCED`/`COMPLAINED` en < 5 min y el polling cuesta más de 1 000 peticiones/día.

### FR-33 — Ámbitos y restricción de origen de API keys (Must, nuevo; ADR-0017)

- **AC-33.1** — Cada key tiene `scopes` ⊆ {`emails:send`, `emails:read`, `templates:write`, `suppressions:write`}. Por defecto: `emails:send`, `emails:read`.
- **AC-33.2** — Mapa de endpoints a ámbitos en `07` §2; una llamada sin el ámbito requerido → `403 INSUFFICIENT_SCOPE`.
- **AC-33.3** — Una key puede tener `allowedCidrs` (lista de CIDR). Si la lista no está vacía y la IP de origen (la IP del socket, o `X-Forwarded-For` solo desde proxies de confianza configurados) no está en ella → `403 IP_NOT_ALLOWED`, y queda en auditoría.
- **AC-33.4** — La key que se despliega en un producto **no** lleva `templates:write` ni `suppressions:write`; esas keys las usa el administrador desde su máquina o desde CI.

### FR-34 — Detección de anomalías y pausa automática (Must, nuevo)

Una tarea del worker evalúa cada 5 minutos, por tenant:

- **AC-34.1** — **Volumen:** *dado* que los mensajes aceptados en la última hora superan `max(ANOMALY_VOLUME_MULTIPLIER × p95 horario de los últimos 14 días, ANOMALY_MIN_HOURLY)` (defaults 3 y 50), *entonces* el tenant pasa a pausa (`sending_paused_at`, `pause_reason = VOLUME_ANOMALY`), se emite una alerta y se audita.
- **AC-34.2** — **Rebotes:** *dado* ≥ 200 mensajes con resultado en las últimas 24 h y una tasa de `BOUNCED` > `BOUNCE_PAUSE_RATE` (default 4 %), *entonces* pausa con `BOUNCE_RATE`.
- **AC-34.3** — **Quejas:** *dado* ≥ 1 000 mensajes entregados en los últimos 7 días y una tasa de `COMPLAINED` > `COMPLAINT_PAUSE_RATE` (default 0,1 %), o 3 quejas en 24 h con cualquier volumen, *entonces* pausa con `COMPLAINT_RATE`.
- **AC-34.4** — En pausa, la API responde `403 TENANT_SENDING_PAUSED` a envíos nuevos y la cola queda retenida (AC-02.3). Solo un administrador la levanta: `POST /admin/v1/tenants/{slug}/resume-sending`, que queda en auditoría.
- **AC-34.5** — Los umbrales son configurables por tenant; la evaluación nunca pausa por debajo de los mínimos de volumen indicados (evita falsos positivos con pocos envíos).

### FR-35 — Envío por lote (Should, nuevo; ADR-0014)

`POST /v1/emails/batch` con `{ "items": [ … ] }`.

- **AC-35.1** — Hasta 100 elementos; cada uno tiene el mismo esquema que `POST /v1/emails` (sin `attachments`) más un campo obligatorio `idempotencyKey`. Más de 100 → `422 VALIDATION_ERROR`. Cuerpo ≤ 1 MB.
- **AC-35.2** — Respuesta `200` con `results[]` en el mismo orden: `{ index, httpStatus, id?, status?, code? }`. Cada elemento se valida por separado: un elemento inválido no invalida a los demás.
- **AC-35.3** — Los elementos aceptados se insertan en **una** transacción; la idempotencia y el rate limit se aplican por elemento (AC-21.6). Si el rate limit se agota a mitad de lote, los elementos restantes reciben `429 RATE_LIMITED`.
- **AC-35.4** — Los mensajes de un lote heredan la prioridad de su categoría (FR-36); un lote nunca adelanta a mensajes `SECURITY`.

### FR-36 — Categoría de plantilla y prioridad en la cola (Must, nuevo)

- **AC-36.1** — Toda plantilla tiene `category`: `SECURITY` (verificación, recuperación de acceso, códigos), `TRANSACTIONAL` (recibos, comprobantes, confirmaciones de pago) o `NOTICE` (avisos operativos, resúmenes).
- **AC-36.2** — El mensaje hereda `priority` de la categoría al aceptarse: `SECURITY` = 0, `TRANSACTIONAL` = 1, `NOTICE` = 2. La toma de trabajo ordena por `priority, next_attempt_at`.
- **AC-36.3** — *Dado* 2 000 mensajes `NOTICE` elegibles, *cuando* se acepta un mensaje `SECURITY`, *entonces* el worker lo toma en la siguiente vuelta (test de integración).
- **AC-36.4** — Cambiar la categoría de una plantilla exige crear una plantilla nueva (no se edita in situ).

### FR-37 — Plantillas por idioma (Should, nuevo; ADR-0018)

- **AC-37.1** — Cada versión de plantilla tiene `locale` ∈ `SUPPORTED_LOCALES` (default `es-CR,en`); otro valor → `422 VALIDATION_ERROR`. Puede haber como máximo una versión `PUBLISHED` por `(plantilla, locale)` (índice único parcial); publicar archiva solo la anterior del mismo locale.
- **AC-37.2** — *Dado* `POST /v1/emails` con `locale = "en"` y una versión publicada en `en`, *entonces* se fija esa versión y la respuesta lleva `locale = "en"`.
- **AC-37.3** — *Dado* `locale = "en"` sin versión publicada en `en`, *entonces* se usa `tenant.locale` (default `es-CR`), la respuesta indica el `locale` efectivo y se incrementa `email_locale_fallback_total`. Sin versión publicada tampoco en `tenant.locale` → `422 TEMPLATE_NOT_PUBLISHED`.
- **AC-37.4** — Sin `locale` en la petición se usa `tenant.locale`.
- **AC-37.5** — `formatDate`, `formatNumber` y `formatMoney` usan el locale efectivo del mensaje y `tenant.timezone`. Test: `formatMoney 1500 "CRC"` produce `₡1 500,00` en `es-CR` y `CRC1,500.00` en `en` (el formato exacto lo fija la librería de la JDK; el test lo congela). **(rev. 2026-10-06)** Congelado con la JDK 25: en `es-CR` el separador de miles es un espacio de no separación (U+00A0), y en `en` no hay espacio entre `CRC` y la cifra.
- **AC-37.6** — **(rev. 2026-10-07, ADR-0020)** Todas las versiones que quedan publicadas tras una publicación deben declarar las mismas variables `required` (todas las traducciones aceptan las mismas variables). Se comprueba sobre el conjunto resultante; si no se cumple → `422 VALIDATION_ERROR`.
- **AC-37.7** — En el lote (FR-35) cada elemento puede llevar su propio `locale`.
- **AC-37.8** — **(rev. 2026-10-07, ADR-0020)** *Cuando* se llama a `POST /v1/templates/{key}/publish` con `{"versions": {"es-CR": 5, "en": 3}}`, *entonces* esas versiones se publican en una sola transacción: cada una se valida como en una publicación individual, AC-37.6 se comprueba sobre el conjunto resultante, se archiva la versión anterior de cada idioma y se audita una entrada por versión. Si cualquiera falla, no se publica ninguna. Test: con `es-CR` y `en` publicados con `required: [a]`, publicar en conjunto dos versiones con `required: [a, b]` funciona; publicarlas por separado da `422`.
