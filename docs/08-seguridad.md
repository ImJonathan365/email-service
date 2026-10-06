# 08 — Consideraciones de seguridad

> Revisión 2026-10-05: ámbitos y CIDR por key, anomalías, plantillas endurecidas, RLS, supresión global, webhooks Svix, privacidad y Ley 8968, respuesta a incidentes con notificación de brechas.

Un servicio de correo es un objetivo atractivo: quien lo controla puede suplantar tus dominios, enviar phishing con tu reputación y leer datos personales. Estas reglas son **obligatorias** y no se relajan "temporalmente".

## 1. Gestión de secretos

| Secreto | Dónde vive | Dónde **nunca** |
|---|---|---|
| API key del proveedor (`RESEND_API_KEY`) | Variable de entorno inyectada por el orquestador o un secrets manager (AWS Secrets Manager, Doppler, Infisical, 1Password Connect) | Base de datos, repositorio, imagen Docker, logs |
| `MAIL_WEBHOOK_SIGNING_SECRET` | Ídem | Ídem |
| `ADMIN_API_KEYS` | Ídem | Ídem |
| `SUPPRESSION_HASH_KEY` (rev. 2026-10) | Ídem; con respaldo fuera de línea (si se pierde, los hashes de supresión no se pueden recalcular) | Ídem |
| Credenciales de PostgreSQL (tres roles) | Ídem; `email_owner` solo disponible para el job `migrate` | Ídem |
| API keys de los tenants | En el servicio: **solo hash**. En el producto cliente: su propia variable de entorno | En claro en la base de datos, en logs, en respuestas (salvo el momento de creación) |

Reglas:

- `.env` está en `.gitignore`; `.env.example` solo contiene marcadores.
- La aplicación **no arranca** si falta un secreto obligatorio en `production` o `staging` (fail-fast, sin defaults inseguros).
- Escaneo de secretos en CI (`gitleaks` o equivalente) y bloqueo del merge si detecta algo.
- Rotación: documentada y probada al menos una vez (§3).
- Ningún secreto se imprime en logs, trazas ni mensajes de error; los objetos de configuración que los contienen tienen `toString()` redactado.

## 2. Autenticación entre servicios (rev. 2026-10, ADR-0017)

**Formato de API key:** `esk_{env}_{prefix}_{secret}`

- `esk` = *email service key*; `env` = `live`/`test`; `prefix` = 8 caracteres Base62 públicos que permiten localizar la fila (se regeneran si colisionan); `secret` = 43 caracteres Base62 de un CSPRNG (≥ 256 bits).
- **Almacenamiento:** `key_prefix` en claro (para el lookup) y `SHA-256(secret)` en `key_hash`. Con 256 bits de entropía real, SHA-256 basta y evita el coste de Argon2 en cada petición; no es una contraseña humana. (Si algún día se emiten claves de baja entropía, cambiar a Argon2id → requiere ADR.)
- **Comparación en tiempo constante** (`MessageDigest.isEqual`), nunca `String.equals`.
- El prefijo permite identificar una clave filtrada en un log o repositorio sin exponer el secreto.
- Caché en memoria de las claves validadas con TTL ≤ 60 s, para no golpear la base de datos en cada petición; la revocación se hace efectiva en ≤ 60 s (AC-03.4).
- **Ámbitos** (`emails:send`, `emails:read`, `templates:write`, `suppressions:write`): la key desplegada en un producto solo lleva `emails:send` y `emails:read` (FR-33).
- **Origen:** `allowedCidrs` por key cuando el hosting da IP de salida estables.

**Credencial de administración:** `ADMIN_API_KEYS` (una o varias, para rotar sin corte), con su propia cabecera (`X-Admin-Key`); `/admin/v1/**` solo es accesible desde la red privada y restringido por IP en el balanceador. Nunca se usa una key de tenant para administrar.

**Transporte (rev. 2026-10):** TLS obligatorio en todo tránsito, o una red privada cifrada (WireGuard, la red privada cifrada del hosting). Las API keys viajan como *bearer*: no se aceptan en texto plano por una red sin cifrar. HSTS en el proxy público.

## 3. Rotación de credenciales

**API key de un tenant (sin downtime):**

1. Emitir una key nueva (`POST /admin/v1/tenants/{slug}/api-keys`) con los mismos ámbitos: el tenant queda con dos activas.
2. Desplegar el producto con la nueva en su entorno.
3. Verificar en `last_used_at` que la antigua ya no se usa.
4. Revocar la antigua (`DELETE /admin/v1/api-keys/{id}`).
5. Queda registrado en auditoría.

**Credencial de administración:** añadir la nueva a `ADMIN_API_KEYS` → desplegar → usar la nueva → retirar la vieja → desplegar.

**Credenciales del proveedor:** crear la nueva en el panel → actualizar la variable de entorno → redesplegar (*rolling*) → revocar la anterior. El servicio lee la clave al arrancar; un reinicio basta.

**Secreto de webhooks:** Resend tiene un secreto por endpoint. Para rotarlo sin perder eventos: crear un segundo endpoint con su secreto, aceptar ambos durante el cambio (`MAIL_WEBHOOK_SIGNING_SECRET` admite dos valores separados por coma) y eliminar el endpoint viejo.

**Ante sospecha de filtración:** revocar de inmediato (la disponibilidad del producto afectado es secundaria frente a un envío suplantado), pausar el tenant, rotar el secreto de webhooks si pudo exponerse, revisar `audit_log` y los envíos de las últimas horas por tenant.

## 4. Protección contra abuso y spam (rev. 2026-10)

| Vector | Control |
|---|---|
| Key de producto filtrada usada para enviar masivamente | Cuota diaria ajustada a ~3× el volumen esperado (FR-21); **pausa automática por anomalía** de volumen, rebotes o quejas (FR-34); CIDR por key (FR-33); alerta |
| Key filtrada usada para publicar contenido propio (phishing) | La key de producto **no** tiene `templates:write` (FR-33); publicar queda en auditoría |
| Phishing mediante variables URL en plantillas legítimas | Variables `format: uri` solo `https` y con host en `allowedLinkHosts` del tenant (AC-07.8) |
| Uso como *open relay* | No existe endpoint de envío sin API key; toda petición usa una plantilla propia del tenant |
| Contenido arbitrario | En el MVP **solo se envía con plantilla publicada** del tenant; no hay HTML libre en la petición (FR-27 fuera de alcance) |
| Suplantación de remitente | El `from` sale del tenant; si se especifica, debe estar en `allowedFromDomains`; SPF, DKIM y DMARC alineados por dominio en el proveedor |
| Inyección de cabeceras (CRLF) | Rechazo de `\r`/`\n` en direcciones, nombres (incluido `fromName`), asunto y tags (AC-09.3, AC-09.5); el asunto renderizado también se limpia |
| Inyección de plantilla (SSTI) | Motor *logic-less* endurecido: sin parciales, sin helpers por defecto, solo `MapValueResolver`, `{{{ }}}` y `{{& }}` rechazados (ADR-0011) |
| XSS / contenido activo en el correo | Escapado HTML por defecto; linter que prohíbe variables en `<script>`, `<style>`, `style=`, `on*` y atributos sin comillas, y prohíbe las etiquetas activas (AC-04.7) |
| SSRF | El servicio nunca descarga contenido remoto |
| Adjuntos maliciosos | No hay adjuntos en el MVP (FR-30 Won't) |
| Enumeración de recursos | IDs UUID; recursos de otro tenant devuelven `404`, no `403` |
| Abuso de la cola (payloads enormes) | `MAX_REQUEST_BYTES`, límites de `metadata` (4 KB), `tags` (10), `cc`/`bcc` (5) y lotes |
| Rebotes y quejas que dañan la reputación | Supresión **global** automática (ADR-0012); tasas por tenant; pausa al superar el 4 % de rebotes o el 0,1 % de quejas |
| Reproducción de webhooks | Firma Svix sobre el cuerpo crudo + ventana ±5 min + dedupe por `svix-id` |
| Fuerza bruta de API keys | Claves de 256 bits (inviable); rate limit por IP en `401` repetidos (en memoria por instancia) y auditoría agregada |
| Fuga entre tenants por un error de código | RLS *fail-closed* + FK compuestas (ADR-0008) |

**Envío a direcciones reales desde entornos no productivos:** `ALLOWED_RECIPIENT_DOMAINS` es **obligatoria** en `staging`; en `local` el proveedor por defecto es Mailpit, que no entrega nada al exterior. Staging usa una cuenta de proveedor distinta de producción.

## 5. Aislamiento multi-tenant (rev. 2026-10, ADR-0008)

- `tenant_id` en toda consulta de negocio; el contexto de tenant se deriva **solo** de la API key autenticada, nunca de un campo del cuerpo o de un parámetro.
- Un `tenantId` enviado por el cliente se ignora explícitamente: no existe "actuar en nombre de".
- **RLS activa desde el MVP**: `FORCE ROW LEVEL SECURITY` y política *fail-closed* sobre `app.tenant_id`, fijado por transacción con `set_config(…, true)`.
- **FK compuestas `(tenant_id, …)`**: la BD rechaza referencias cruzadas entre tenants.
- **Rol `email_system` (`BYPASSRLS`)** confinado por test de arquitectura a worker, webhooks, auth, administración, anomalías y mantenimiento.
- Tests obligatorios por endpoint (A no ve ni afecta a B), sin contexto (0 filas) y de FK cruzada.

## 6. Protección de datos personales (rev. 2026-10)

- **Minimización:** se guardan la dirección, las variables y los metadatos; el HTML renderizado solo si el tenant lo activa, y con las variables `x-sensitive` redactadas.
- **Variables:** pueden contener datos personales (nombres, importes, teléfonos SINPE, enlaces con token). **Nunca** se escriben en logs. Las `x-sensitive` se purgan al enviar; el resto, 7 días después del estado final.
- **Eventos del proveedor:** payload minimizado (sin `to`, `from`, `subject`, cabeceras ni *query string* de URL).
- **Logs:** direcciones enmascaradas; retención de 30 días.
- **Enlaces con token de un solo uso:** responsabilidad del producto (caducidad corta); el email-service no los interpreta y los marca `x-sensitive`. Por eso el seguimiento de clics está prohibido en `SECURITY` (reescribiría esos enlaces a través del proveedor).
- **Encargado del tratamiento:** el proveedor de correo procesa direcciones y contenido en EE. UU.; el README documenta qué datos recibe.
- **Borrado a petición del titular (FR-29):** endpoint de administración `POST /admin/v1/data-subjects/erase`.
  1. Recibir la solicitud a través del producto (que es el responsable frente al usuario).
  2. Ejecutar el endpoint con la dirección y una referencia de la solicitud.
  3. Solicitar al proveedor el borrado de sus registros de esa dirección (retención declarada por Resend: 30 días según su página de precios; a confirmar por plan).
  4. Responder al titular dentro de **5 días hábiles** (Ley 8968; a confirmar con asesoría).
  5. La supresión se conserva solo como hash, para no volver a escribirle.

### 6.1 Ley 8968 (Costa Rica) — obligaciones que tocan a este servicio

No soy abogado: es una lectura técnica de fuentes secundarias verificadas el 2026-10-05, a validar con asesoría (NFR-20).

| Obligación | Cómo se cubre |
|---|---|
| Consentimiento informado y por escrito para tratar datos (art. 5) | Responsabilidad de cada producto; el email-service solo trata datos por encargo |
| Transferencia internacional solo con autorización expresa (art. 14; no hay régimen de adecuación) | Cada producto debe informar y obtener el consentimiento para que un proveedor de correo en EE. UU. procese la dirección y el contenido. **Bloqueante para producción** de cada producto |
| Derechos ARCO en 5 días hábiles | FR-29 y procedimiento de §6 |
| Notificación de brechas a afectados y PRODHAB en 5 días hábiles (Reglamento, Decreto 37554-JP, arts. 38–39) | §9, paso 5 |
| Registro de bases de datos ante PRODHAB (solo las que se distribuyen, difunden o comercializan) | El email-service se considera de uso interno: exento, **a confirmar** |
| Datos de terceros tratados por encargo (Colmena: clientes de sus negocios, productos, deudas) | El negocio es el responsable; Colmena, encargada; email-service y Resend, subencargados. Los términos de Colmena con sus negocios deben cubrir los correos y la transferencia. Montos y conceptos de deuda se marcan `x-sensitive` (NFR-20) |
| Medidas de seguridad | Este documento |

## 7. Auditoría

Se registran en `audit_log` (sin secretos ni contenido de correos; las direcciones, como `email_hash`):
- creación, suspensión, pausa y reanudación de tenants;
- emisión y revocación de keys;
- publicación de plantillas;
- borrado de supresiones;
- borrados por titular;
- cambios de límites;
- fallos de autenticación agregados por IP y ventana;
- accesos a endpoints de administración.

Cada entrada: actor, acción, recurso, IP, `requestId`, timestamp. Solo inserciones (sin `UPDATE`/`DELETE` para los roles de la app); retención de 365 días.

## 8. Seguridad de la cadena de suministro y del despliegue

- Imagen base oficial y fijada por versión; contenedor con usuario no-root y sistema de archivos de solo lectura salvo `/tmp`.
- Dependencias con versiones fijadas y escaneo de vulnerabilidades en CI (`dependency-check`, `osv-scanner` o Dependabot). Handlebars.java se vigila en particular (ADR-0011).
- Sin puertos de depuración expuestos; Actuator limitado a `health`, `info`, `metrics` y `prometheus` (este último solo en la red privada).
- **(rev. 2026-10)** Tres roles de PostgreSQL: `email_owner` (migraciones, solo en el job `migrate`), `email_app` (RLS, sin DDL) y `email_system` (`BYPASSRLS`, sin DDL). La app en ejecución nunca es dueña de las tablas.
- CORS deshabilitado (no hay navegadores llamando a esta API); si algún día los hay, allowlist explícita.
- **(rev. 2026-10)** Respaldos cifrados en reposo, con retención ≤ 35 días.

## 9. Respuesta a incidentes (mínimo viable) (rev. 2026-10)

1. **Detección:** alertas de antigüedad de cola, tenant pausado (FR-34), tasa de rebote > 4 % o de queja > 0,1 % por tenant, pico de `401`, `email_lost_lock_total` > 0, circuit breaker abierto > 10 min.
2. **Contención:** pausar o suspender el tenant afectado (`PATCH /admin/v1/tenants/{slug}` → `SUSPENDED`) y/o revocar su key; los envíos encolados **quedan retenidos** en `QUEUED` (no se pierden ni fallan).
3. **Investigación:** `audit_log` + `email_message` por tenant y ventana temporal; eventos del proveedor.
4. **Recuperación:** rotar credenciales, reanudar el tenant (`resume-sending`), revisar la reputación del dominio en el panel del proveedor y en Google Postmaster Tools.
5. **Notificación (si hubo datos personales comprometidos):** a los afectados y a PRODHAB dentro de **5 días hábiles** desde el incidente, indicando la naturaleza, los datos comprometidos, las acciones correctivas y los canales de información (Reglamento de la Ley 8968; a confirmar con asesoría). La notificación a los usuarios la hace cada producto afectado; el email-service aporta el alcance exacto (consultas por tenant y ventana).
6. **Registro:** anotar el incidente, la causa y las decisiones en `docs/CHANGELOG.md`.

## 10. Lista de verificación previa a producción

- [ ] Ningún secreto en el repositorio (escaneo en CI en verde).
- [ ] `ADMIN_API_KEYS` y `SUPPRESSION_HASH_KEY` generadas con ≥ 32 bytes aleatorios y guardadas en el secrets manager (la segunda, con respaldo fuera de línea).
- [ ] SPF, DKIM y DMARC (al menos `p=none`, alineado) configurados y verificados para cada dominio remitente.
- [ ] `MAIL_PROVIDER=resend` y `ALLOW_SMTP_IN_PRODUCTION=false`; equipo de Resend distinto para staging.
- [ ] TLS o red privada cifrada en todo tránsito; solo `/webhooks/*` es público.
- [ ] Firma de webhooks verificada con un evento real del proveedor (cuerpo crudo).
- [ ] Roles `email_owner`/`email_app`/`email_system` creados; RLS activa y test sin contexto en verde contra la BD real.
- [ ] Límites, `allowedLinkHosts` y umbrales de anomalía revisados por tenant; alertas configuradas.
- [ ] Keys de producto emitidas solo con `emails:send` + `emails:read`.
- [ ] Respaldo de PostgreSQL activo, cifrado y **restauración probada una vez**.
- [ ] Tests de aislamiento multi-tenant en verde.
- [ ] Actuator expuesto solo lo necesario.
- [ ] Aviso de privacidad de cada producto cubre la transferencia al proveedor en EE. UU. (Ley 8968, art. 14). **Pendiente, no bloquea el desarrollo; bloquea la salida a producción de cada producto.**
- [ ] Colmena: términos con sus negocios cubren el envío de correos a los clientes de cada negocio.
