# 07 — Especificación de la API interna

> Revisión 2026-10-05. Los cambios respecto a la versión del 2026-09-20 y su impacto en los productos están en §12 (notas de migración). Como aún no hay ninguna versión desplegada en producción, se aplican sobre `/v1` (NFR-18).

Base URL: `https://email.internal.midominio.com` (local: `http://localhost:8080`).
Swagger UI: `/swagger-ui.html` · OpenAPI: `/v3/api-docs` · Spec versionada: `contracts/email-service.openapi.json`.

## 1. Convenciones

- JSON en `camelCase`; fechas ISO-8601 en UTC; identificadores UUID (los mensajes, UUIDv7).
- Versionado en la ruta: `/v1`. Los clientes **deben ignorar campos desconocidos** (NFR-18) y **deben tratar un `code` desconocido según su clase HTTP** (4xx = no reintentar salvo 429; 5xx = reintentar).
- Autenticación de productos: `Authorization: Bearer esk_{env}_{prefix}_{secret}`.
- Administración: `X-Admin-Key: <una de ADMIN_API_KEYS>` sobre `/admin/v1/**`; solo accesible desde la red privada.
- Cabeceras: `Idempotency-Key` (envíos; exigida por el contrato de integración), `X-Request-Id` (correlación; se devuelve siempre).
- Transporte: HTTPS (o red privada cifrada). **(rev. 2026-10)** Los clientes **deben** reutilizar conexiones (HTTP/1.1 keep-alive o HTTP/2): abrir una conexión TLS por petición añade ~2,5 ms en loopback más 2 RTT de red (ADR-0014).
- Errores: `application/problem+json` (RFC 9457).
- Paginación por cursor: `?limit=20&cursor=<opaco>` → `{"data":[…],"nextCursor":"…"}`.

## 2. Mapa de endpoints

| Método | Ruta | Descripción | Ámbito (FR-33) | FR |
|---|---|---|---|---|
| `POST` | `/v1/emails` | Enviar correo transaccional | `emails:send` | FR-07, FR-08, FR-31, FR-37 |
| `POST` | `/v1/emails/batch` | Enviar hasta 100 correos (rev. 2026-10) | `emails:send` | FR-35 |
| `GET` | `/v1/emails/{id}` | Estado y eventos de un envío | `emails:read` | FR-19 |
| `GET` | `/v1/emails` | Listar/filtrar envíos | `emails:read` | FR-20 |
| `POST` | `/v1/emails/{id}/cancel` | Cancelar si sigue en cola | `emails:send` | FR-26 |
| `GET` | `/v1/templates` | Listar plantillas | `emails:read` | FR-04 |
| `POST` | `/v1/templates` | Crear plantilla | `templates:write` | FR-04 |
| `GET` | `/v1/templates/{key}` | Detalle + versiones | `emails:read` | FR-04 |
| `POST` | `/v1/templates/{key}/versions` | Crear versión (borrador) | `templates:write` | FR-04 |
| `PUT` | `/v1/templates/{key}/versions/{version}` | Editar borrador | `templates:write` | FR-04 |
| `POST` | `/v1/templates/{key}/versions/{version}/publish` | Publicar (inmutable) | `templates:write` | FR-05 |
| `POST` | `/v1/templates/{key}/preview` | Renderizar sin enviar | `templates:write` | FR-06 |
| `GET` | `/v1/suppressions` | Listar supresiones (propias + globales enmascaradas) | `emails:read` | FR-18 |
| `POST` | `/v1/suppressions` | Añadir supresión manual | `suppressions:write` | FR-18 |
| `DELETE` | `/v1/suppressions/{email}` | Eliminar supresión manual propia | `suppressions:write` | FR-18 |
| `POST` | `/webhooks/{provider}` | Eventos del proveedor (firma, sin API key) | — | FR-16 |
| `POST` | `/admin/v1/tenants` | Crear tenant | admin | FR-02 |
| `GET` | `/admin/v1/tenants` | Listar tenants | admin | FR-02 |
| `PATCH` | `/admin/v1/tenants/{slug}` | Actualizar límites/estado/hosts | admin | FR-02 |
| `POST` | `/admin/v1/tenants/{slug}/resume-sending` | Levantar una pausa por anomalía (rev. 2026-10) | admin | FR-34 |
| `POST` | `/admin/v1/tenants/{slug}/api-keys` | Emitir API key | admin | FR-03, FR-33 |
| `GET` | `/admin/v1/tenants/{slug}/api-keys` | Listar keys (sin secretos) | admin | FR-03 |
| `DELETE` | `/admin/v1/api-keys/{id}` | Revocar key | admin | FR-03 |
| `GET` | `/admin/v1/suppressions` | Listar supresiones globales (rev. 2026-10) | admin | FR-18 |
| `DELETE` | `/admin/v1/suppressions/{email}` | Eliminar supresión global (rev. 2026-10) | admin | FR-18 |
| `POST` | `/admin/v1/data-subjects/erase` | Borrado por titular (rev. 2026-10) | admin | FR-29 |
| `GET` | `/actuator/health` (+ `/liveness`, `/readiness`) | Health | — | FR-24 |

---

## 3. `POST /v1/emails` — enviar

```http
POST /v1/emails HTTP/1.1
Authorization: Bearer esk_live_7fA2kQ9z_…
Idempotency-Key: password-reset:3f9c2a71-5d1e-4b8a-9e44-2f7a1e5d8b90
Content-Type: application/json
```

```json
{
  "templateKey": "password-reset",
  "templateVersion": null,
  "to": { "email": "ana@example.com", "name": "Ana Pérez" },
  "cc": [],
  "bcc": [],
  "replyTo": "soporte@colmena.cr",
  "variables": {
    "firstName": "Ana",
    "resetUrl": "https://app.colmena.cr/reset?t=abc123",
    "expiresInMinutes": 30
  },
  "tags": ["password-reset"],
  "metadata": { "userId": "u_8817", "requestSource": "web" }
}
```

| Campo | Tipo | Oblig. | Reglas |
|---|---|---|---|
| `templateKey` | string | Sí | Plantilla del tenant; debe tener versión publicada |
| `templateVersion` | int\|null | No | `null` = última publicada (se fija en el mensaje al aceptar) |
| `to.email` / `to.name` | string | Sí / No | Una sola dirección principal |
| `cc` / `bcc` | array | No | Máx. 5 direcciones cada uno; los suprimidos se descartan (`droppedRecipients`) |
| `replyTo` | string | No | Default: el del tenant |
| `variables` | object | Sí (`{}` si no hay) | Validadas contra `variablesSchema`; las `format: uri` deben ser `https` y pertenecer a `allowedLinkHosts` |
| `tags` | string[] | No | Máx. 10, `[a-z0-9_-]{1,64}` |
| `metadata` | object | No | Opaco para el servicio, máx. 4 KB |
| `locale` | string | No | **(rev. 2026-10, FR-37)** `es-CR` (default del tenant) \| `en`; si no hay traducción publicada, cae a `tenant.locale` |
| `fromName` | string | No | **(rev. 2026-10, AC-09.5)** Nombre visible del remitente, ≤ 80 caracteres (p. ej., "Pulpería La Esquina vía Colmena") |
| `sendAt` | string | No | **(rev. 2026-10, FR-31, Should)** ISO-8601 con zona; entre ahora y +30 días; la versión se fija al aceptar |
| `attachments` | — | — | **No disponible** (FR-30 Won't): se rechaza con `422 VALIDATION_ERROR` |

**202 Accepted**

```json
{
  "id": "0199c2f1-6b21-7a3e-9c44-2f7a1e5d8b90",
  "status": "QUEUED",
  "templateKey": "password-reset",
  "templateVersion": 3,
  "locale": "es-CR",
  "to": "ana@example.com",
  "droppedRecipients": [],
  "createdAt": "2026-10-05T18:04:11Z"
}
```

- Repetición con la misma `Idempotency-Key` y el mismo cuerpo → **200 OK** con el mismo objeto (no consume rate limit).
- Destinatario `to` suprimido → **202** con `status = FAILED`, `failureCode = SUPPRESSED` y `suppressionReason` (`HARD_BOUNCE` \| `COMPLAINT` \| `PROVIDER` \| `MANUAL`); el producto puede reintentar con **otra** dirección del usuario si la tiene (o `422 RECIPIENT_SUPPRESSED` si `SUPPRESSION_REJECT_MODE=reject`). **El cliente no debe reintentar este caso.**

**Errores:** `400 MALFORMED_REQUEST`, `401 UNAUTHENTICATED`, `403 TENANT_SUSPENDED` / `TENANT_SENDING_PAUSED` / `FROM_DOMAIN_NOT_ALLOWED` / `RECIPIENT_NOT_ALLOWED_IN_ENV` / `INSUFFICIENT_SCOPE` / `IP_NOT_ALLOWED`, `404 TEMPLATE_NOT_FOUND`, `409 IDEMPOTENCY_KEY_REUSED`, `413 PAYLOAD_TOO_LARGE`, `422 VALIDATION_ERROR` / `TEMPLATE_VARIABLES_INVALID` / `TEMPLATE_NOT_PUBLISHED` / `INVALID_EMAIL_ADDRESS` / `HEADER_INJECTION_DETECTED` / `UNSAFE_URL` / `SEND_AT_OUT_OF_RANGE` / `RECIPIENT_SUPPRESSED`, `429 RATE_LIMITED`, `503 SERVICE_UNAVAILABLE`.

## 4. `POST /v1/emails/batch` — enviar en lote (rev. 2026-10, FR-35)

```json
{
  "items": [
    { "idempotencyKey": "notice:maint-2026-10-12:u_1", "templateKey": "maintenance-notice",
      "to": { "email": "a@example.com" }, "variables": { "date": "2026-10-12T02:00:00-06:00" } },
    { "idempotencyKey": "notice:maint-2026-10-12:u_2", "templateKey": "maintenance-notice",
      "to": { "email": "no-es-un-correo" }, "variables": { "date": "2026-10-12T02:00:00-06:00" } }
  ]
}
```

**200 OK**

```json
{
  "results": [
    { "index": 0, "httpStatus": 202, "id": "0199c2f1-…", "status": "QUEUED" },
    { "index": 1, "httpStatus": 422, "code": "INVALID_EMAIL_ADDRESS" }
  ]
}
```

- Máximo 100 elementos y 1 MB; cada elemento admite `locale` y `sendAt`. `idempotencyKey` es obligatorio por elemento (la cabecera `Idempotency-Key` se ignora en este endpoint).
- Los elementos aceptados se insertan en una transacción (medido: ~0,25 ms/mensaje frente a ~0,75 ms con una transacción por mensaje).
- No es una herramienta de campañas: los envíos `NOTICE` siguen sujetos a cuota, a la detección de anomalías y a la prioridad más baja (ADR-0013).

## 5. `GET /v1/emails/{id}` — estado

```json
{
  "id": "0199c2f1-6b21-7a3e-9c44-2f7a1e5d8b90",
  "status": "DELIVERED",
  "category": "SECURITY",
  "templateKey": "password-reset",
  "templateVersion": 3,
  "to": "ana@example.com",
  "from": "no-reply@colmena.cr",
  "tags": ["password-reset"],
  "metadata": { "userId": "u_8817" },
  "attempts": 1,
  "provider": "resend",
  "providerMessageId": "re_3Hk9…",
  "failureCode": null,
  "failureDetail": null,
  "createdAt": "2026-10-05T18:04:11Z",
  "sentAt": "2026-10-05T18:04:13Z",
  "deliveredAt": "2026-10-05T18:04:19Z",
  "firstOpenedAt": null,
  "openCount": 0,
  "clickCount": 0,
  "events": [
    { "type": "SENT",      "occurredAt": "2026-10-05T18:04:13Z" },
    { "type": "DELIVERED", "occurredAt": "2026-10-05T18:04:19Z" }
  ]
}
```

Incluye `renderedSubject` / `renderedHtml` solo si el tenant tiene `storeRenderedContent = true` y no se han purgado (variables `x-sensitive` como `[REDACTED]`). Un `id` de otro tenant → `404 MESSAGE_NOT_FOUND`.

## 6. `GET /v1/emails` — listado

`?status=FAILED&templateKey=password-reset&to=ana@example.com&tag=password-reset&createdAfter=2026-09-01T00:00:00Z&createdBefore=2026-10-01T00:00:00Z&limit=20&cursor=…`

```json
{
  "data": [ { "id": "…", "status": "SENT", "to": "ana@example.com", "templateKey": "password-reset", "createdAt": "…" } ],
  "nextCursor": "eyJjcmVhdGVkQXQiOiIyMDI2…"
}
```

## 7. Plantillas

**Crear plantilla** — `POST /v1/templates`

```json
{ "key": "password-reset", "name": "Recuperación de contraseña", "description": "…", "category": "SECURITY" }
```

**Crear versión** — `POST /v1/templates/password-reset/versions` (una versión por idioma; `locale` default `es-CR`)

```json
{
  "locale": "es-CR",
  "subjectTemplate": "{{firstName}}, restablece tu contraseña de Colmena",
  "htmlTemplate": "<html><body style=\"font-family:sans-serif\"><img src=\"https://cdn.colmena.cr/logo.png\" alt=\"Colmena\" height=\"32\"><h1>Hola {{firstName}}</h1><p>El enlace vence en {{expiresInMinutes}} minutos.</p><p><a href=\"{{resetUrl}}\">Restablecer contraseña</a></p></body></html>",
  "textTemplate": "Hola {{firstName}}. Restablece tu contraseña: {{resetUrl}} (vence en {{expiresInMinutes}} minutos)",
  "variablesSchema": {
    "required": ["firstName", "resetUrl", "expiresInMinutes"],
    "properties": {
      "firstName": { "type": "string", "maxLength": 80 },
      "resetUrl": { "type": "string", "format": "uri", "x-sensitive": true },
      "expiresInMinutes": { "type": "integer" }
    }
  }
}
```

**201 Created** → `{ "version": 4, "status": "DRAFT", "createdAt": "…" }`

**Publicar** — `POST /v1/templates/password-reset/versions/4/publish` → `200` con `status = PUBLISHED`. A partir de ahí la versión es inmutable (`409 VERSION_IMMUTABLE` ante cualquier edición) y la versión 3 pasa a `ARCHIVED`. Publicar exige `variablesSchema`.

**Previsualizar** — `POST /v1/templates/password-reset/preview`

```json
{ "templateVersion": 4, "variables": { "firstName": "Ana", "resetUrl": "https://app.colmena.cr/reset?t=x", "expiresInMinutes": 30 } }
```

```json
{ "subject": "Ana, restablece tu contraseña de Colmena", "html": "<html>…</html>", "text": "Hola Ana…", "warnings": [] }
```

**Reglas de plantilla (rev. 2026-10, ADR-0011):**
- Handlebars *logic-less*. `{{variable}}` escapa HTML **solo** en `htmlTemplate`; en `subjectTemplate` y `textTemplate` no hay escapado HTML (el asunto además pierde CR/LF).
- Se rechazan con `422 UNSAFE_TEMPLATE_CONSTRUCT`: `{{{ }}}`, `{{& }}`, parciales (`{{> }}`), cualquier helper fuera de la lista blanca, variables dentro de `<script>`, `<style>`, `style=`, `on*=` o atributos sin comillas, y etiquetas `<script>`, `<iframe>`, `<object>`, `<embed>` y `<form>`.
- Toda variable en `href`/`src` debe declararse con `"format": "uri"`.
- Helpers permitidos: `if`, `unless`, `each`, `with`, `formatDate value [pattern]`, `formatNumber value [decimals]` y `formatMoney value currency` (`CRC`, `USD`). Usan el locale efectivo del mensaje (`es-CR` o `en`) y `tenant.timezone`; no existe un helper de "fecha actual" (el render debe ser determinista).
- Seguimiento de aperturas y clics: solo en categoría `NOTICE` y solo si `trackingEnabled = true`.

## 8. Supresiones

```http
GET    /v1/suppressions?reason=HARD_BOUNCE&limit=50
POST   /v1/suppressions                { "email": "bad@example.com", "note": "solicitado por el usuario" }
DELETE /v1/suppressions/bad@example.com
```

- `GET` devuelve `{ email | emailMasked, scope: TENANT|GLOBAL, reason, createdAt }`; las globales van enmascaradas.
- `POST` crea siempre `reason = MANUAL`, `scope = TENANT`.
- `DELETE` solo borra las `MANUAL` propias; sobre una global → `403 ADMIN_REQUIRED`.

## 9. Webhooks entrantes (rev. 2026-10)

```http
POST /webhooks/resend
Content-Type: application/json
svix-id: msg_2f9…
svix-timestamp: 1789412651
svix-signature: v1,k8s9…
```

- La firma se verifica sobre el **cuerpo crudo**, antes de parsear el JSON, con el secreto del endpoint (`MAIL_WEBHOOK_SIGNING_SECRET`), con una ventana de ±5 min sobre `svix-timestamp`. `svix-id` se guarda como `provider_event_id` para deduplicar.
- Respuestas: `204` (procesado, duplicado o huérfano), `401` (firma inválida o fuera de ventana), `400` (cuerpo ilegible tras verificar la firma), `503` (BD no disponible; el proveedor reintentará). **Nunca 5xx** por un evento que no se puede correlacionar.
- El formato exacto depende del proveedor: el puerto `WebhookVerifier` (módulo `provider`) tiene una implementación por proveedor; el módulo `events` la consume.
- Mapeo de eventos: `02` §2.1.

## 10. Administración

**Crear tenant** — `POST /admin/v1/tenants`

```json
{
  "slug": "colmena",
  "name": "Colmena",
  "fromEmail": "no-reply@colmena.cr",
  "fromName": "Colmena",
  "allowedFromDomains": ["colmena.cr"],
  "allowedLinkHosts": ["colmena.cr", "app.colmena.cr", "cdn.colmena.cr"],
  "locale": "es-CR",
  "timezone": "America/Costa_Rica",
  "rateLimitPerMinute": 120,
  "dailyQuota": 3000,
  "retentionDays": 90,
  "storeRenderedContent": false
}
```

**Emitir API key** — `POST /admin/v1/tenants/colmena/api-keys`

```json
{ "name": "producción 2026", "expiresAt": "2027-10-05T00:00:00Z",
  "scopes": ["emails:send", "emails:read"], "allowedCidrs": ["10.20.0.0/16"] }
```

**201 Created** (el secreto se muestra **una sola vez**):

```json
{
  "id": "1f2e…",
  "name": "producción 2026",
  "apiKey": "esk_live_7fA2kQ9z_3xK9mQ2vL8pR4tW6yB1nC5dF7gH0jA2sE4uI6oP8qZr",
  "keyPrefix": "esk_live_7fA2kQ9z",
  "scopes": ["emails:send", "emails:read"],
  "allowedCidrs": ["10.20.0.0/16"],
  "expiresAt": "2027-10-05T00:00:00Z",
  "warning": "Guárdala ahora: no se podrá volver a mostrar."
}
```

**Revocar** — `DELETE /admin/v1/api-keys/{id}` → `204`.

**Levantar pausa** — `POST /admin/v1/tenants/colmena/resume-sending` `{ "note": "pico legítimo: aviso de mantenimiento" }` → `200`.

**Borrado por titular** — `POST /admin/v1/data-subjects/erase` `{ "email": "ana@example.com", "reference": "ARCO-2026-014" }` → `200 { "deleted": { "email_message": 12, "email_event": 31, "email_attachment": 0 }, "suppressionsAnonymized": 1 }`.

## 11. Formato de error

```json
{
  "type": "https://email-service.internal/problems/rate-limited",
  "title": "Rate limit exceeded",
  "status": 429,
  "detail": "Tenant colmena exceeded 120 requests per minute.",
  "instance": "/v1/emails",
  "code": "RATE_LIMITED",
  "requestId": "0199c2f1-6b21-7a3e-9c44-2f7a1e5d8b90"
}
```

### Catálogo de `code` (enum cerrado)

| HTTP | `code` | Significado |
|---|---|---|
| 400 | `MALFORMED_REQUEST` | JSON inválido |
| 401 | `UNAUTHENTICATED` | Falta la key, es inválida, está revocada o expiró |
| 403 | `ADMIN_REQUIRED` | Endpoint o recurso de administración sin credencial de admin |
| 403 | `INSUFFICIENT_SCOPE` | **(rev. 2026-10)** La key no tiene el ámbito requerido |
| 403 | `IP_NOT_ALLOWED` | **(rev. 2026-10)** IP de origen fuera de `allowedCidrs` de la key |
| 403 | `TENANT_SUSPENDED` | Tenant suspendido |
| 403 | `TENANT_SENDING_PAUSED` | **(rev. 2026-10)** Envío pausado por anomalía (FR-34) |
| 403 | `FROM_DOMAIN_NOT_ALLOWED` | Remitente fuera de `allowedFromDomains` |
| 403 | `RECIPIENT_NOT_ALLOWED_IN_ENV` | Destino fuera de la allowlist de entorno |
| 404 | `TEMPLATE_NOT_FOUND` / `MESSAGE_NOT_FOUND` / `RESOURCE_NOT_FOUND` | No existe (o es de otro tenant) |
| 409 | `IDEMPOTENCY_KEY_REUSED` | Misma clave, cuerpo distinto |
| 409 | `TEMPLATE_KEY_TAKEN` / `TENANT_SLUG_TAKEN` | Identificador en uso |
| 409 | `VERSION_IMMUTABLE` | Edición de versión publicada |
| 409 | `MESSAGE_NOT_CANCELABLE` | El mensaje ya no está en cola |
| 413 | `PAYLOAD_TOO_LARGE` | Supera `MAX_REQUEST_BYTES` (o el límite de lote) |
| 422 | `VALIDATION_ERROR` | Campos inválidos (incluye `errors[]` por campo) |
| 422 | `INVALID_EMAIL_ADDRESS` | Dirección mal formada |
| 422 | `HEADER_INJECTION_DETECTED` | CR/LF en un campo de cabecera |
| 422 | `TEMPLATE_VARIABLES_INVALID` | Variables faltantes, de tipo incorrecto o que rompen el render |
| 422 | `TEMPLATE_NOT_PUBLISHED` | Sin versión publicada |
| 422 | `TEMPLATE_SYNTAX_ERROR` / `UNSAFE_TEMPLATE_CONSTRUCT` | Plantilla inválida o insegura |
| 422 | `UNSAFE_URL` | **(rev. 2026-10)** Variable URL no `https` o fuera de `allowedLinkHosts` |
| 422 | `ATTACHMENT_INVALID` | **Reservado** (FR-30 Won't); no se emite en el MVP |
| 422 | `SEND_AT_OUT_OF_RANGE` | **(rev. 2026-10)** `sendAt` en el pasado o a más de 30 días |
| 422 | `RECIPIENT_SUPPRESSED` | Solo en modo `reject` |
| 429 | `RATE_LIMITED` | Límite por minuto o cuota diaria (incluye `Retry-After`) |
| 500 | `INTERNAL_ERROR` | Fallo no esperado (sin detalles internos) |
| 503 | `SERVICE_UNAVAILABLE` | Base de datos no disponible |

## 12. Contrato de integración para los productos (rev. 2026-10)

```bash
curl -sS -X POST https://email.internal.midominio.com/v1/emails \
  -H "Authorization: Bearer $EMAIL_SERVICE_API_KEY" \
  -H "Idempotency-Key: password-reset:$RESET_REQUEST_ID" \
  -H "Content-Type: application/json" \
  -d '{
    "templateKey": "password-reset",
    "to": { "email": "ana@example.com", "name": "Ana" },
    "variables": { "firstName": "Ana", "resetUrl": "https://app.colmena.cr/reset?t=abc", "expiresInMinutes": 30 }
  }'
```

Reglas para el cliente:

1. **Idempotency-Key = el identificador del evento de dominio** que origina el correo (`password-reset:{resetRequestId}`, `receipt:{invoiceId}`, `payment-verified:{verificationId}`). Nunca usuario+fecha (colapsa solicitudes legítimas del mismo día) ni un valor aleatorio por intento (no deduplica).
2. Tratar `202` y `200` como éxito; no esperar la entrega. Un `202` con `status = FAILED` y `failureCode = SUPPRESSED` **no** se reintenta.
3. Reintentar solo ante `5xx` y `429` (respetando `Retry-After`), con backoff y la **misma** `Idempotency-Key`. Ante un `code` desconocido, actuar según su clase HTTP.
4. Timeouts del cliente: conexión ≤ 2 s, lectura ≤ 5 s. Reutilizar conexiones (keep-alive o HTTP/2).
5. Si el correo es crítico para el negocio, registrarlo primero en un *outbox* propio del producto y llamar al email-service desde ese outbox, para no perderlo si el email-service no responde.
6. No almacenar el HTML: vive en el email-service. Guardar el `id` devuelto para consultar el estado más tarde.
7. Usar una API key por entorno; la de producción solo con `emails:send` y `emails:read`.
8. No hay adjuntos: los documentos y las exportaciones se envían como enlace firmado con caducidad (variable `format: uri`, host en `allowedLinkHosts`).
9. Enviar `locale` solo si el usuario prefiere inglés; si no hay traducción, el correo sale en español y la respuesta lo indica.
10. Ante `failureCode = SUPPRESSED` en un correo `SECURITY`, ofrecer al usuario un canal alternativo (otra dirección registrada); no reintentar a la misma dirección.

### 12.1 Notas de migración respecto a la especificación del 2026-09-20

| Cambio | Impacto en el cliente | Compatibilidad |
|---|---|---|
| Claves de idempotencia recomendadas: de `signup-{userId}-{fecha}` a id de evento | Cambiar cómo se deriva la clave | Sin cambio de formato |
| Códigos nuevos (`INSUFFICIENT_SCOPE`, `IP_NOT_ALLOWED`, `TENANT_SENDING_PAUSED`, `UNSAFE_URL`, `ATTACHMENT_INVALID`, `SEND_AT_OUT_OF_RANGE`) | Tratar según su clase HTTP | Compatible si se cumple §1 |
| `droppedRecipients`, `locale` y `suppressionReason` en la respuesta | Ignorar o usar | Compatible (campos nuevos) |
| Campos opcionales `locale`, `fromName`, `sendAt` en la petición | Usarlos si se necesitan | Compatible |
| Variables URL validadas contra `allowedLinkHosts` | Dar de alta los hosts del producto en el tenant | **Incompatible** si el producto envía enlaces a hosts no registrados → configurar antes de la integración |
| Plantillas con `category` y `variablesSchema` obligatorio para publicar | Afecta a quien publica plantillas (administración), no al envío | Las plantillas se crean desde cero en la Fase 0 |
| Ámbitos de API key | Las keys de producto se emiten con `emails:send`, `emails:read` | Sin impacto si se usa la key correcta |
| Eventos `BOUNCED_HARD`/`BOUNCED_SOFT` → `BOUNCED`/`DELIVERY_DELAYED` | Ajustar si el producto lee `events[].type` | Renombrado antes de la primera versión desplegada |
| `GET /v1/emails`: filtros de fecha `createdAfter`/`createdBefore` | Ya era así en esta especificación; FR-20 se alinea | — |
