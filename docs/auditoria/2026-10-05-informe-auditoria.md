# Informe de auditoría — documentación de email-service

- **Fecha:** 2026-10-05
- **Alcance:** `/docs` (README, 01–10, CHANGELOG), ADR-0000 a ADR-0007 y `AGENTS.md`, versión del 2026-09-20.
- **Método:** lectura cruzada de todos los documentos; verificación web de versiones, precios, límites y normativa (fuentes al final, consultadas el 2026-10-05); micro-benchmarks reales del camino de aceptación en PostgreSQL 16 local (detalle en ADR-0014).
- **Severidad:** **Bloqueante** (no se puede implementar sin decidirlo: produce pérdida o duplicado de correo, fuga entre tenants o abuso) · **Grave** (defecto real que aparecerá en producción o contradicción que obliga a adivinar) · **Menor** (incoherencia o imprecisión con impacto acotado) · **Mejora** (no es un error, pero conviene).
- "A confirmar" = no se pudo verificar en una fuente primaria; no se afirma.

Resumen: 4 bloqueantes, 17 graves, 22 menores, 3 mejoras. Lo que está **bien** y se mantiene, en una línea cada uno: un solo artefacto con `APP_ROLE` (correcto); cola en PostgreSQL con `SKIP LOCKED` (correcta a este volumen, ver medidas); proveedor por API detrás de `EmailSender` (correcto); secretos fuera de la BD y API keys con hash SHA-256 de 256 bits (correcto, Argon2 sería coste sin beneficio); versiones de plantilla inmutables y fijadas por mensaje (correcto); errores RFC 9457 (correcto); Java 25 LTS + Spring Boot 4.1 + PostgreSQL 18 como versiones (vigentes y verificadas).

---

## BLOQUEANTES

### 1. La máquina de estados hace inalcanzable `COMPLAINED` y pierde rebotes tardíos
- **Dónde:** `02` §2 y AC-17.2 / AC-17.4; `06` §3.4.
- **Qué está mal:** "Estados finales: `DELIVERED`, `BOUNCED`, `COMPLAINED`…" y "Una transición nunca retrocede". Una queja **siempre** llega después de la entrega (el usuario tiene que recibir el correo para marcarlo como spam), y un rebote asíncrono también puede llegar después de `delivered`. Con `DELIVERED` final, AC-17.2 ("un evento… que implicaría retroceder **no** cambia el estado") obliga a ignorar la queja, mientras AC-17.4 exige "actualiza el estado **y** crea la supresión en la misma transacción". Las dos lecturas son incompatibles.
- **Por qué importa:** según cómo lo interprete quien implemente, la supresión por queja no se crea y se sigue escribiendo a quien te denunció como spam. Es exactamente la métrica que Gmail vigila (umbral 0,3 %).
- **Corrección:** orden parcial explícito: `QUEUED → SENDING → SENT → DELIVERED → {BOUNCED | COMPLAINED}`; `DELIVERED` es "estable", no terminal; `BOUNCED`/`COMPLAINED` sí. La creación de la supresión se desacopla del cambio de estado (se crea siempre que llega el evento). Aplicado en `02` §2 y en el nuevo AC-17.5.

### 2. "Exactamente una vez" no está garantizado: falta *fencing*, el barrido no cuenta intentos y no se usa la idempotencia de Resend, que existe
- **Dónde:** `03` NFR-04; `02` AC-11.3, AC-13.6; `05` §3 pasos 10–14; `06` §6; ADR-0002.
- **Qué está mal:**
  - NFR-04: "Mitigación: idempotencia del proveedor **cuando la ofrezca**". Resend **ya la ofrece**: cabecera `Idempotency-Key`, hasta 256 caracteres, retenida 24 h, con `409 invalid_idempotent_request` si cambia el cuerpo ([Resend — Idempotency keys](https://resend.com/docs/dashboard/emails/idempotency-keys)). El diseño no la usa.
  - AC-13.6 ("si hay `provider_message_id`, no se reenvía") no cubre el caso real de duplicado: el proveedor acepta, la respuesta se pierde por timeout y **nunca** llega a guardarse el `provider_message_id`.
  - El barrido (`06` §6) devuelve a `QUEUED` los `SENDING` vencidos **sin** sumar `attempts`: un mensaje que tumba al worker (p. ej., OOM al renderizar) se reintenta para siempre.
  - No hay *fencing*: el `UPDATE … SET status='SENT'` final no comprueba que el worker siga siendo dueño del lock. Si el barrido reasigna un mensaje cuyo primer worker sigue vivo (pausa de GC, proveedor lento), dos workers lo envían.
  - El lock se fija para el lote entero (25 mensajes) en el momento de tomarlo, pero el lote se procesa con concurrencia 4: el último mensaje del lote puede empezar cerca del vencimiento.
  - El texto no dice si la llamada al proveedor ocurre dentro de la transacción que tomó el lote. `06` §6 sugiere que no (CTE autocommit), pero no lo prohíbe.
- **Por qué importa:** es el objetivo nº 2 del proyecto ("no perder ni duplicar correos") y la regla AGENTS.md "Never re-send a message that already has provider_message_id" da una falsa sensación de seguridad.
- **Corrección (ADR-0010, supersede ADR-0002):** toma de trabajo en transacción corta; **nunca** HTTP con locks de fila abiertos; `lock_token` por toma y `UPDATE … WHERE lock_token = :mío` para cerrar; `attempts` se incrementa **al tomar** (no al fallar), así el barrido cuenta; `Idempotency-Key` hacia Resend = `email_message.id` (estable entre intentos) y cuerpo determinista; ventana total de reintentos < 23 h (validada al arrancar) para quedar dentro de las 24 h del proveedor; NFR-04 se reescribe como "al-menos-una-vez con deduplicación en el proveedor dentro de 24 h" y declara el riesgo residual real (envío que dure > 24 h, que el diseño prohíbe).

### 3. Una API key filtrada es un cañón de phishing con tu dominio
- **Dónde:** `08` §4 ("Contenido arbitrario / phishing → solo se envía con plantilla publicada"); `07` §2 (los endpoints `/v1/templates/**` y `/v1/suppressions/**` usan la misma key que `/v1/emails`); `07` §3 (variable `activationUrl` libre).
- **Qué está mal:** la misma key que usa el producto para enviar puede **crear y publicar plantillas**. "Solo plantilla publicada" no protege nada si quien roba la key publica la suya. Aun sin eso, una plantilla legítima con `href="{{activationUrl}}"` acepta cualquier URL: el atacante envía "Activa tu cuenta" desde `no-reply@tu-producto` con un enlace a su sitio. La misma key también puede **borrar supresiones** (incluidos rebotes duros, AC-18.3).
- **Por qué importa:** el escenario "se filtra una key" es el más probable (variables de entorno en logs de CI, `.env` en un ticket) y hoy su impacto máximo es suplantación del dominio a cualquier destinatario hasta la cuota diaria (5 000 por defecto).
- **Corrección:** ámbitos por key (FR-33: `emails:send`, `emails:read`, `templates:write`, `suppressions:write`; la key de producción del producto solo lleva los dos primeros); lista de IP/CIDR opcional por key; variables de tipo URL validadas contra `allowedLinkHosts` del tenant y solo `https` (AC-07.8); borrar supresiones `HARD_BOUNCE`/`COMPLAINT` solo por administración; detección de anomalías con pausa automática (FR-34).

### 4. Aplazar Row Level Security no se sostiene, y la BD no garantiza el aislamiento ni siquiera con RLS
- **Dónde:** `01` §6 ("Row Level Security… Fase 2"); `03` NFR-08; `08` §5; ADR-0007; `06` §5.
- **Qué está mal:** el único control es "toda consulta lleva `tenant_id`" más tests por endpoint. El servicio guarda destinatarios, variables (con tokens de activación) y eventos de **cuatro** productos distintos, incluido uno de pagos (Payverica). Además la propia BD permite inconsistencias entre tenants: `email_message.template_version_id` referencia `template_version(id)` sin incluir `tenant_id`, `template_version.tenant_id` está "desnormalizado" sin restricción que lo ate a `template.tenant_id`, y `suppression`/`email_event` tampoco lo atan. El argumento de ADR-0007 ("añade complejidad de sesión/roles desde el día 1") es real pero pequeño: un `set_config('app.tenant_id', …, true)` por transacción y políticas por tabla.
- **Por qué importa:** tus otros proyectos lo exigen desde el MVP; este, que mezcla datos personales de varios productos, tiene **más** motivo, no menos. Un solo repositorio mal escrito (o un agente de IA que "simplifica" una consulta) expone datos entre productos sin que ningún test lo detecte si el test no cubre ese camino.
- **Corrección (ADR-0008, supersede ADR-0007):** RLS con `FORCE ROW LEVEL SECURITY` desde la migración V1; rol propietario (migraciones) distinto del rol de la aplicación; política *fail-closed* (sin `app.tenant_id` no se ve ninguna fila); un segundo `DataSource` con rol de sistema solo para worker, barridos, webhooks y lookup de API key, confinado por test de arquitectura; claves foráneas compuestas `(tenant_id, id)` para que la BD impida que un mensaje apunte a la plantilla de otro tenant.

---

## GRAVES

### 5. El bloqueo de `{{{ }}}` no cierra la inyección ni el XSS
- **Dónde:** AC-04.5, ADR-0005, `04` §7, `08` §4.
- **Qué está mal:**
  - Handlebars también permite salida sin escapar con `{{& variable}}` ([README de handlebars.java](https://github.com/jknack/handlebars.java)). Solo se prohíbe `{{{ }}}`.
  - Handlebars.java registra por defecto helpers como `partial`, `block`, `embedded`, `precompile`, `i18n` y `log` (mismo README). `partial`/`embedded` cargan contenido mediante el `TemplateLoader`; la librería ha publicado correcciones de *path traversal* en sus cargadores (versiones 4.5.x, [releases](https://github.com/jknack/handlebars.java/releases)). La lista "helpers permitidos" de `07` §6 no dice que los demás se **eliminan**.
  - Los *value resolvers* por reflexión (`JavaBeanValueResolver`, `MethodValueResolver`) permiten navegar métodos públicos de objetos Java si el contexto no es un mapa puro.
  - El escapado HTML no protege contextos de URL (`href="javascript:…"` o un dominio ajeno), CSS (`style="color:{{c}}"`) ni atributos sin comillas.
  - El escapado HTML se aplica también al **asunto** y al **texto plano**, donde produce `&amp;` y `&#39;` visibles ("Ana &amp; Juan").
- **Corrección:** ADR-0011 (supersede ADR-0005) y AC-04.5/04.7–04.10: rechazar `{{&`, `{{{`, `{{>`, parciales y cualquier helper fuera de la lista blanca; `TemplateLoader` que no carga nada; solo `MapValueResolver`; *linter* al publicar que prohíbe variables dentro de `<script>`, `<style>`, `style=`, atributos `on*` y atributos sin comillas, y exige `format: uri` para variables en `href`/`src`; escapado por contexto (HTML en `html`, ninguno en `subject`/`text` salvo eliminar CR/LF).

### 6. Supresión "por tenant": premisa falsa con Resend y huecos de semántica
- **Dónde:** AC-10.2, AC-18.3, ADR-0007, `06` §3.6.
- **Qué está mal:** "La supresión es **por tenant**, no global." En Resend la lista de supresión es **de todo el equipo**: "Any address added to the suppression list will be skipped across all your domains and subdomains", y se alimenta automáticamente con rebotes duros y quejas ([Resend — Email suppressions](https://resend.com/docs/dashboard/emails/email-suppressions)). Una queja contra Chinamo bloquea en el proveedor los correos de Colmena a esa dirección, y tu tabla no lo sabrá. El evento `email.suppressed` ([tipos de evento](https://resend.com/docs/dashboard/webhooks/event-types)) no está mapeado: esos mensajes se quedan en `SENT` para siempre. Además: `cc`/`bcc` no se comprueban contra la supresión; un tenant puede borrar un rebote duro (que es un hecho sobre la dirección, no sobre el tenant); no se define qué pasa con correos críticos (recuperación de contraseña) a alguien que se quejó.
- **Corrección:** ADR-0012: `HARD_BOUNCE` y `COMPLAINT` con alcance **global** (refleja lo que el proveedor ya hace), `MANUAL` por tenant; espejo de `suppression.added`/`email.suppressed`; comprobación sobre `to`+`cc`+`bcc`; borrado de supresiones globales solo por administración. Si quieres que una queja contra un producto **no** afecte a otro, la única forma real es una cuenta (equipo) de Resend por producto: queda como pregunta abierta.

### 7. Rebote blando: el diagrama contradice al requisito, y el proveedor no emite "soft bounce"
- **Dónde:** `02` §2 ("webhook bounce (hard/soft) → BOUNCED") frente a AC-10.4 ("Un rebote blando **no** suprime, solo registra el evento"); `06` §3.5 (`BOUNCED_SOFT`).
- **Qué está mal:** el diagrama manda el rebote blando a un estado terminal; el requisito lo trata como informativo. Resend no distingue rebote duro/blando por campo: `email.bounced` es rechazo permanente y `email.delivery_delayed` es problema temporal ([tipos de evento](https://resend.com/docs/dashboard/webhooks/event-types)). Tampoco se mapean `email.sent`, `email.failed`, `email.scheduled` ni `email.suppressed`.
- **Corrección:** tabla de mapeo proveedor→evento interno→transición en `02` §2.1; `BOUNCED_SOFT` pasa a `DELIVERY_DELAYED` y `BOUNCED_HARD` a `BOUNCED` (renombrados antes de la primera migración: aún no hay datos); `email.failed` → `FAILED` con `PROVIDER_FAILED`.

### 8. La verificación de webhooks está documentada con cabeceras que Resend no envía
- **Dónde:** `07` §8 (`Webhook-Id`, `Webhook-Timestamp`, `Webhook-Signature`); AC-16.1 (`WEBHOOK_SIGNING_SECRET`) frente a `05`/`07`/`08` (`MAIL_WEBHOOK_SIGNING_SECRET`).
- **Qué está mal:** Resend firma con Svix y envía `svix-id`, `svix-timestamp`, `svix-signature`, con un secreto **por endpoint**, y la verificación exige el **cuerpo crudo** sin re-serializar ([Resend — Verify webhooks](https://resend.com/docs/dashboard/webhooks/verify-webhooks-requests)). El nombre de la variable de entorno no coincide entre documentos.
- **Corrección:** `07` §8 con las cabeceras reales, lectura del cuerpo crudo antes de parsear, variable única `MAIL_WEBHOOK_SIGNING_SECRET`, deduplicación por `svix-id`.

### 9. Tenant suspendido: tres comportamientos distintos
- **Dónde:** AC-02.3 ("los ya encolados **no** se envían mientras siga suspendido"); `05` §4 ("Bloqueante: tenant suspendido → `FAILED` con `failure_code`"); `08` §9 ("los envíos encolados quedan retenidos").
- **Por qué importa:** con `05` §4, suspender un tenant por precaución durante un incidente **destruye** su cola; con AC-02.3 la retiene. Es la palanca de contención principal del plan de incidentes.
- **Corrección:** retener: el worker no toma mensajes de tenants `SUSPENDED` o en pausa (filtro en la consulta de toma); la alerta de antigüedad de cola los excluye; `TENANT_SUSPENDED` deja de ser `failure_code` (DEPRECATED) y queda solo como error HTTP 403.

### 10. Sacar los adjuntos del MVP rompe el comprobante electrónico de Chinamo (y quizá de Colmena)
- **Dónde:** `01` §6, FR-28, `09` Fase 1 ("Adjuntos… Requisito previo: un caso de uso real").
- **Qué está mal:** el caso real ya existe: un POS en Costa Rica emite comprobantes electrónicos y debe hacérselos llegar al receptor; la práctica habitual es enviar el XML firmado, la respuesta de Hacienda y el PDF por correo. La normativa habla de que los comprobantes se "transmiten al receptor" ([Alegra — guía v4.4](https://blog.alegra.com/costa-rica/comprobantes-provisionales-y-electronicos-costa-rica/)); el canal y el formato exactos **a confirmar** contra la resolución vigente de la DGT. Si Chinamo es uno de los primeros productos, el servicio no le sirve. La exportación mensual de PipeMend, en cambio, **no** necesita adjunto: un enlace firmado es mejor (tamaño, caducidad, privacidad).
- **Corrección:** FR-28 se marca DEPRECATED y se divide: FR-30 Adjuntos acotados (Should, MVP): base64 en la petición, máx. 3 archivos, `application/pdf` y `application/xml`, ≤ 2 MB decodificados, comprobación de *magic bytes*, guardados en `email_attachment` y **borrados al llegar a estado terminal** (sin almacenamiento de objetos). FR-31 `sendAt` (Could, cuesta casi nada porque `next_attempt_at` ya existe). FR-32 webhooks salientes (Won't; ningún producto lo necesita para operar).

### 11. Throughput y límites del proveedor: NFR-02 ignora el límite de Resend y no hay mecanismo con N workers
- **Dónde:** NFR-02 ("≥ 20 mensajes/segundo"), AC-12.6 ("respeta… el rate limit del proveedor"), NFR-06 (N workers).
- **Qué está mal:** el límite por defecto de Resend es **10 peticiones/segundo por equipo** ([Resend — API reference](https://resend.com/docs/api-reference/introduction)). Si staging y producción comparten equipo, comparten el límite. Con N workers, cada uno con su propio limitador en memoria, se supera. NFR-02 se mide "contra un proveedor simulado con 100 ms", lo que no dice nada del comportamiento real.
- **Corrección:** NFR-21 (límites del proveedor): limitador por instancia = `PROVIDER_MAX_RPS / WORKER_INSTANCES` (por defecto 1 worker y 8 rps); un equipo de Resend distinto para staging; el `429` del proveedor es transitorio y respeta `Retry-After` (ya estaba).

### 12. Idempotencia: la clave recomendada a los clientes es incorrecta y hay tres incoherencias
- **Dónde:** `07` §11 ("`signup-{userId}-{fecha}`"), `07` §3 (ejemplo `signup-8f14e45f-2025`); README ("Idempotencia **obligatoria**") frente a AC-08.6 ("se acepta igual"); `05` §3 (rate limit en el paso 3, idempotencia en el paso 8); AC-21.5.
- **Qué está mal:** una clave por usuario y **fecha** colapsa dos solicitudes legítimas del mismo día (dos "olvidé mi contraseña" → el segundo recibe 200 con el primero y nunca se envía) y no protege un reintento que cruza la medianoche. Los reintentos idempotentes consumen rate limit y pueden recibir `429` en lugar del `200` original. Si el contador se incrementa antes de validar, un envío rechazado **sí** consume cuota, contra AC-21.5.
- **Corrección:** la clave se deriva del **evento de dominio** del producto (`password-reset:{resetRequestId}`); orden de la aceptación: auth → idempotencia (lectura) → validación → rate limit → inserción, todo en una transacción; AC-08.6 se mantiene (aceptar sin clave) pero el README deja de decir "obligatoria" y se exige a los productos por contrato de integración.

### 13. Privacidad y retención: datos sensibles demasiado tiempo y Ley 8968 cubierta a medias
- **Dónde:** NFR-15, FR-23, `06` §8, `08` §6.
- **Qué está mal:**
  - `variables` (con enlaces de activación y recuperación) vive 90 días aunque solo hace falta hasta el último reintento: un volcado de la BD entrega tokens.
  - `email_event.payload` se guarda "íntegro" (destinatario, asunto, URL clicada con su *query string*).
  - Eventos huérfanos (`tenant_id NULL`) no tienen retención definida.
  - El borrado a petición del titular es un "script" que solo borra mensajes (no eventos, ni `cc`/`bcc`, ni auditoría con el destinatario) y no tiene plazo.
  - Logs: sin retención y sin regla de enmascarar direcciones.
  - Ley 8968: la transferencia de direcciones y contenido a un proveedor en EE. UU. (Resend) requiere consentimiento expreso, porque no existe régimen de adecuación (art. 14); el Reglamento (Decreto 37554-JP, arts. 38–39) exige notificar una brecha a los afectados y a PRODHAB en **5 días hábiles**; los derechos ARCO se atienden en 5 días hábiles ([resumen Ley 8968](https://www.recordinglaw.com/world-laws/world-data-privacy-laws/costa-rica-data-privacy-laws/)). Nada de esto aparece. La reforma (expediente 23.097) sigue sin aprobarse según la información disponible ([Delfino](https://delfino.cr/asamblea/proyecto/23097)); estado a 2026 **a confirmar**.
  - Incoherencia de prioridades: NFR-15 (privacidad) es **Must** y FR-23 (la purga que la implementa) es **Should**.
- **Corrección:** FR-23 pasa a Must; `variables` se purgan al llegar a estado terminal + 7 días, y las marcadas `x-sensitive` al enviar; payload de evento minimizado; huérfanos 30 días; FR-29 (borrado por titular, endpoint de administración, ≤ 5 días hábiles, supresión conservada como hash); NFR-20 (cumplimiento) con las obligaciones de la Ley 8968 y la nota de que no soy abogado: a validar con asesoría legal.

### 14. El objetivo de costo es imposible con el entorno de referencia
- **Dónde:** `03` encabezado ("PostgreSQL gestionado pequeño (2 vCPU / 4 GB)") y NFR-16 ("≤ 25 USD/mes").
- **Qué está mal:** un PostgreSQL gestionado de 2 vCPU/4 GB cuesta 60,90 USD/mes en DigitalOcean; el de 1 vCPU/1 GB, 15,15 USD ([precios DO](https://www.digitalocean.com/pricing/managed-databases)). El objetivo no se cumple ni con la BD sola.
- **Corrección:** entorno de referencia = BD gestionada 1 vCPU/1 GB + instancia de app de 1 GB; NFR-16: ≤ 25 USD/mes con el plan gratuito del proveedor y ≤ 50 USD/mes con Resend Pro.

### 15. Precios y límites de proveedores desactualizados o erróneos
- **Dónde:** `04` §5 y ADR-0003.
- **Qué está mal / verificado hoy:**
  - Amazon SES: el documento dice "0,16 USD/1 000"; el precio vigente es **0,10 USD/1 000**, más 0,12 USD/GB de adjuntos; clientes nuevos reciben hasta 200 USD en créditos ([AWS SES pricing](https://aws.amazon.com/ses/pricing/)).
  - Postmark a 50 000/mes: el documento dice "~15–18 USD base + excedente"; la cifra real es 87 USD (Basic), 68,50 USD (Pro) o 66 USD (Platform) ([Postmark pricing](https://postmarkapp.com/pricing)).
  - Resend a 500 000/mes: "~90 USD + excedente" es incorrecto (Scale de 90 USD incluye 100 000); el precio del tramo de 500 000 es **a confirmar**. Resend Pro también tiene un tramo de 35 USD por 100 000 ([Resend — pricing KB](https://resend.com/docs/knowledge-base/what-is-resend-pricing)).
  - Confirmados: Resend gratis 3 000/mes con 100/día y 3 dominios; Pro 20 USD/50 000 con 10 dominios y 5 endpoints de webhook; excedente 0,90 USD/1 000 ([Resend pricing](https://resend.com/pricing)).
  - Faltan datos que cambian el diseño: límite de 10 rps (#11), idempotencia nativa (#2), supresión global por equipo (#6), IP dedicada solo con > 3 000 correos/día ([Resend — dedicated IPs](https://resend.com/docs/knowledge-base/how-do-dedicated-ips-work)).
- **Corrección:** tabla de `04` §5 rehecha con fecha y fuente; disparadores de cambio recalculados (#20).

### 16. Ventana de reintentos sin límite frente a las 24 h del proveedor, y backoff con un error de uno
- **Dónde:** AC-13.1 / AC-13.3, `05` §6 (`RETRY_BACKOFF_SECONDS=60,300,900,3600,21600`, `MAX_ATTEMPTS=5`).
- **Qué está mal:** con 5 intentos hay 4 esperas; la de 6 h nunca se usa. Nada impide configurar un backoff que supere las 24 h de idempotencia de Resend, y entonces el reintento sí podría duplicar.
- **Corrección:** `MAX_ATTEMPTS` = nº de esperas + 1 (validado al arrancar); suma de esperas + `LOCK_TIMEOUT` < 23 h o la app no arranca; superar la ventana → `FAILED` con `RETRY_WINDOW_EXCEEDED`.

### 17. No hay requisito de "tiempo hasta envío" ni prioridad: un lote puede retrasar una recuperación de contraseña
- **Dónde:** NFR-01 solo mide la aceptación; la cola ordena solo por `next_attempt_at`.
- **Por qué importa:** para el usuario lo que cuenta es cuándo llega el código, no cuándo respondió la API. Un aviso a 2 000 clientes de Colmena encolado antes pone 2 000 mensajes delante del correo de verificación de Payverica.
- **Corrección:** NFR-19: aceptación→`SENT` p95 ≤ 5 s con el proveedor sano y la cola vacía de reintentos; columna `priority` derivada de la categoría de plantilla (`SECURITY` > `TRANSACTIONAL` > `NOTICE`) y orden `priority, next_attempt_at` en la toma (FR-36).

### 18. Una sola cuenta de proveedor para todos los productos: riesgo de cuenta no documentado
- **Dónde:** `04` §5 ("Dominios y claves separados por producto, lo que se alinea con el diseño multi-tenant").
- **Qué está mal:** separar dominios no separa la **cuenta**: supresión compartida (#6), límite de tasa compartido (#11), IP compartidas en el plan Pro ([Resend — dedicated IPs](https://resend.com/docs/knowledge-base/how-do-dedicated-ips-work)) y, sobre todo, si un producto genera quejas, la revisión del proveedor afecta a la cuenta entera. Es el riesgo central de la tarea 3 (correo masivo).
- **Corrección:** documentado en `04` §5.4 y en ADR-0013; regla: ningún envío masivo comparte cuenta con el transaccional; pregunta abierta sobre una cuenta por producto.

### 19. Seguimiento de aperturas y clics activado por defecto en correo transaccional
- **Dónde:** `01` §5.9, FR-16/FR-17 (`opened`, `clicked` como eventos esperados).
- **Qué está mal:** el seguimiento de clics reescribe los enlaces a través del dominio del proveedor, incluidos los enlaces de recuperación de contraseña y de verificación de pago (el token pasa por un tercero y aparece en sus logs); la apertura es poco fiable con la protección de privacidad de los clientes de correo y es tratamiento de datos personales sin necesidad. Nada lo justifica en transaccional.
- **Corrección:** seguimiento desactivado por defecto; activable por plantilla de categoría `NOTICE`; prohibido en `SECURITY` (AC-04.11).

### 20. Los disparadores de cambio de proveedor y de cola no miden lo que importa
- **Dónde:** `04` §4–5, ADR-0002, ADR-0003, `09` Fase 3.
- **Qué está mal:** "> 50 mensajes/segundo sostenidos" equivale a ~130 millones de correos/mes: 1 300 veces el volumen objetivo y 5 veces el límite de Resend, así que nunca se dispararía. "A Postmark si la prioridad pasa a ser deliverability" no es medible. "A SES cuando supere ~200 000/mes" se calculó con un precio de SES erróneo.
- **Corrección:** cola: revisar si la toma p95 > 50 ms, si la antigüedad de cola > 60 s con el proveedor sano durante 3 días o si el volumen supera 3 M/mes. Proveedor: Postmark si `delivery_delayed` + rebotes > 2 % durante 7 días atribuibles al proveedor, o tras un incidente de cuenta; SES cuando el ahorro mensual verificado supere 150 USD durante 3 meses (a 200 000/mes SES ≈ 20 USD frente a Resend Scale ≥ 90 USD; tramo exacto a confirmar).

### 21. NFR-15 Must con FR-23 Should, y el borrado por titular sin plazo ni endpoint
Incluido en #13 por estar ligado; se corrige con FR-23 → Must y FR-29 nuevo.

---

## MENORES

22. **`01` §2, `05` §1, `06` §3.1, `07`, README, AGENTS.md — productos ficticios.** "finance-app / automation / ai-tool" no son tus productos. Sustituidos por PipeMend, Colmena, Chinamo y Payverica, con su perfil de envío (`01` §2.1).
23. **`04` §3 — referencias rotas.** "ADR-0005 del proyecto: ningún otro producto accede a ella" (ADR-0005 trata de plantillas) y "dominio de 8 tablas" (el modelo tiene 10). Corregido.
24. **`01` §7 frente a `04` §4.** "Picos de decenas por segundo" frente a "el objetivo son decenas por minuto". Se unifica en: media 0,04 msg/s, picos de hasta 20 msg/s durante segundos.
25. **AC-20.1 frente a `07` §5.** `to` aparece dos veces (destinatario y fecha); `07` usa `createdAfter`/`createdBefore`. Se adopta `07`.
26. **`07` §3 — lista de errores incompleta.** Faltan `400 MALFORMED_REQUEST`, `403 RECIPIENT_NOT_ALLOWED_IN_ENV`, `422 RECIPIENT_SUPPRESSED` y los nuevos (`INSUFFICIENT_SCOPE`, `IP_NOT_ALLOWED`, `TENANT_SENDING_PAUSED`, `UNSAFE_URL`, `ATTACHMENT_INVALID`).
27. **`05` §6 — configuración incompleta.** Faltan `SUPPRESSION_REJECT_MODE`, `IDEMPOTENCY_RETENTION_HOURS`, `PROVIDER_CONNECT_TIMEOUT`/`READ_TIMEOUT` y los nuevos.
28. **`07` §8 frente a `05` §2.** "El adaptador de firma vive en el módulo `events` junto al `EmailSender` correspondiente", pero `EmailSender` vive en `provider`. Se define el puerto `WebhookVerifier` en `provider`, consumido por `events`.
29. **`06` §4 — códigos de fallo inalcanzables.** `TEMPLATE_NOT_PUBLISHED` no puede ocurrir en el worker (la versión se fija al aceptar) y `TENANT_SUSPENDED` deja de ser fallo (#9). Marcados DEPRECATED.
30. **`01` OBJ-4.** Lista de estados finales sin `COMPLAINED` ni `CANCELED`, y con `SENT`, que no siempre es final. Corregido con la regla de "SENT sin eventos durante 72 h = final por tiempo".
31. **AC-24.1/24.4 — salud.** `DEGRADED` no es un estado estándar de Spring Boot Actuator (`UP`, `DOWN`, `OUT_OF_SERVICE`, `UNKNOWN`); además "health refleja el proveedor" sugiere hacer *ping* al proveedor (gasta rate limit). Se define un indicador propio basado en resultados recientes, con estado personalizado mapeado a HTTP 200, y liveness/readiness sin dependencias externas.
32. **NFR-05 — *circuit breaker* sin requisito.** Se menciona sin umbrales ni librería. Se especifica: abrir tras 5 fallos transitorios consecutivos, semiabierto a los 60 s, implementado a mano (~50 líneas) sin añadir dependencias.
33. **`05` §5 frente a `06` §3.9 — locks de tareas.** `05` dice `FOR UPDATE SKIP LOCKED` sobre `scheduled_lock` y `06` define `locked_until`. Se sustituye por `pg_try_advisory_xact_lock`, sin tabla.
34. **NFR-09 + `08` §8 — Flyway al arrancar en N instancias con el mismo rol.** Con RLS, el dueño de las tablas se salta las políticas salvo `FORCE`. Las migraciones pasan a un paso de despliegue con rol propietario; la app corre con un rol sin privilegios DDL.
35. **`08` §2 frente a `07` §9 — formato de clave.** "≥ 32 bytes en Base62" (43 caracteres) frente al ejemplo en hexadecimal; prefijo de 4 caracteres con colisiones posibles. Se fija: prefijo de 8 caracteres Base62 con reintento ante colisión y secreto de 43 caracteres Base62.
36. **`07` §6 — formateo de fecha y número sin locale.** No hay idioma, zona horaria ni moneda (colón, `America/Costa_Rica`). Se añaden `locale` y `timezone` al tenant, y los helpers `formatDate`, `formatNumber` y `formatMoney` con parámetros explícitos.
37. **AC-04.2, AC-07.4 — `variablesSchema` opcional y subconjunto indefinido.** Sin esquema, AC-07.4 no es verificable. Pasa a obligatorio al publicar, con el subconjunto definido (`required`, `type`, `format: uri|email|date-time`, `maxLength`, `maxItems`, `x-sensitive`).
38. **AC-20.2, NFR-01 — "1 000 000 de filas" sin distribución.** Se define el dataset: 4 tenants, 1 M filas cada uno, 70 % del volumen en uno.
39. **`05`/AC-14.4 — `X-Entity-Ref-ID` como correlación.** Esa cabecera sirve para evitar que Gmail agrupe hilos, no para correlacionar. La correlación es `provider_message_id` más la etiqueta `message_id` del proveedor (los tags de Resend admiten solo ASCII, `_` y `-`, máx. 256 caracteres, según [Send email](https://resend.com/docs/api-reference/emails/send-email)).
40. **`06` §3.8 — contador de rate limit.** Fila "caliente" por tenant y ventana; el rate limit por IP para `401` no dice dónde vive. Se acepta (a < 200 rps sobra) y se documenta que el límite por IP es en memoria por instancia, aceptable porque es defensa secundaria.
41. **Tamaño de plantilla y recorte de Gmail.** Sin límites, un correo de más de ~100 KB de HTML se recorta en Gmail (límite **a confirmar**). Se fija un límite de 100 KB para el HTML renderizado (aviso) y 256 KB para la fuente (rechazo).
42. **`08` §2 — TLS interno opcional.** "En red interna, TLS igualmente si el proveedor de hosting lo permite sin fricción": las API keys viajan como *bearer*. Pasa a obligatorio TLS o red privada cifrada (WireGuard/6PN).
43. **NFR-14 — respaldo sin RPO/RTO ni cifrado.** Se fija RPO ≤ 24 h (≤ 5 min con PITR), RTO ≤ 4 h, respaldos cifrados en reposo.

---

## MEJORAS

44. **Renderizar también al aceptar (AC-07.5).** Renderizar cuesta microsegundos con un motor *logic-less*; hacerlo en la API (y descartar el resultado) convierte `RENDER_ERROR` asíncrono en un `422` síncrono. Aplicado.
45. **Endpoint de lote y conexiones persistentes (tarea 4).** Medido: con keep-alive, parsear y serializar JSON es ~1,5 % del tiempo de servidor (todo lo que no es BD, ~3 %); agrupar 100 mensajes en una transacción baja de ~0,75 a ~0,25 ms/mensaje. FR-35 y ADR-0014.
46. **Detección de anomalías (tarea 3).** Pausa automática por volumen, rebotes y quejas por tenant (FR-34).

---

## Qué verificar en los productos consumidores

No tengo sus documentos. Revisa en cada uno (PipeMend, Colmena, Chinamo, Payverica):

1. **Claves de plantilla:** que los `templateKey` que usan existan con el mismo nombre (kebab-case, p. ej. `password-reset`) y que cada uno tenga una categoría (`SECURITY`/`TRANSACTIONAL`/`NOTICE`).
2. **Idempotencia:** que la `Idempotency-Key` se derive de un identificador de evento de su dominio, no de usuario+fecha ni aleatoria por intento.
3. **Contrato de respuesta:** que traten `202` y `200` como éxito, y que un `202` con `status=FAILED, failureCode=SUPPRESSED` **no** se reintente.
4. **Errores:** que interpreten `code` (no `title`/`detail`), reintenten solo `429`/`5xx` respetando `Retry-After`, y no fallen ante códigos nuevos (`INSUFFICIENT_SCOPE`, `TENANT_SENDING_PAUSED`, `UNSAFE_URL`).
5. **Destinatarios:** que no envíen varios destinatarios en `to` (el contrato acepta uno) ni usen `cc` para notificar a terceros.
6. **Idioma y formato:** el idioma de los correos (¿todo `es-CR`?), la moneda (CRC/USD) y la zona horaria con que formatean importes y fechas, sobre todo en Chinamo y Payverica.
7. **Datos sensibles en variables:** qué meten en `variables` (Payverica: ¿teléfonos SINPE, montos, nombres del pagador?) y cuáles deben marcarse `x-sensitive`.
8. **Adjuntos y envío diferido:** si Chinamo/Colmena envían comprobantes por correo (XML+PDF y tamaños reales) y si algún producto necesita `sendAt` (recordatorios de Colmena).
9. **Avisos de privacidad:** que cada producto informe y obtenga consentimiento para la transferencia de datos a un proveedor de correo en EE. UU. (Ley 8968, art. 14).
10. **Credenciales:** dónde guardan la API key, que usen una key por entorno y que la de producción no tenga `templates:write`.
11. **Enlaces:** los dominios que aparecen en enlaces de sus correos, para configurar `allowedLinkHosts`.
12. **Timeouts del cliente:** conexión ≤ 2 s y lectura ≤ 5 s, con keep-alive, y patrón *outbox* en el producto si el correo es crítico (para no perderlo si el email-service no responde).

---

## Conclusiones de las tareas 2, 3 y 4 (detalle en los ADR)

- **Stack (ADR-0009):** se **mantiene** Java 25 + Spring Boot 4.1 + PostgreSQL 18, pero con otra justificación: perfil de E/S resuelto con hilos virtuales, coste marginal de hosting de 0 a 5 USD/mes frente a Go, y sobre todo el coste de mantener un quinto stack en la cartera. Go es la alternativa seria (puntúa 3,90 frente a 4,15 en la matriz ponderada); Elixir/Oban es la mejor tecnología para colas, pero la peor apuesta para un solo mantenedor sin experiencia en BEAM.
- **Correo masivo (ADR-0013):** **no entra todavía.** La reputación no se separa por microservicio sino por cuenta, dominio e IP del proveedor; el riesgo real es que una campaña con quejas contamine la supresión y la reputación de la cuenta de Resend que envía las recuperaciones de contraseña. Disparador concreto y diseño de separación obligatoria en `11-correo-masivo.md`.
- **Protocolo de entrada (ADR-0014):** REST se queda. Medido en el camino de aceptación: la BD aporta el 96 % del tiempo de servidor (1,33 de 1,38 ms p50); parsear, autenticar, validar y serializar suman ~44 µs. Protobuf frente a JSON no ahorra nada medible (4,81 frente a 3,43 µs). Lo que sí importa es reutilizar conexiones (una conexión TLS nueva por petición añade ~2,5 ms en loopback más 2 RTT de red) y agrupar envíos (endpoint de lote). Umbral para revisar: > 200 peticiones/s sostenidas o > 30 M correos/mes.

---

## Fuentes (consultadas el 2026-10-05)

- [Resend — Pricing](https://resend.com/pricing) · [Resend — What is Resend pricing](https://resend.com/docs/knowledge-base/what-is-resend-pricing) · [Resend — API introduction (rate limit)](https://resend.com/docs/api-reference/introduction) · [Resend — Idempotency keys](https://resend.com/docs/dashboard/emails/idempotency-keys) · [Resend — Webhook event types](https://resend.com/docs/dashboard/webhooks/event-types) · [Resend — Verify webhooks](https://resend.com/docs/dashboard/webhooks/verify-webhooks-requests) · [Resend — Email suppressions](https://resend.com/docs/dashboard/emails/email-suppressions) · [Resend — Dedicated IPs](https://resend.com/docs/knowledge-base/how-do-dedicated-ips-work) · [Resend — Send email](https://resend.com/docs/api-reference/emails/send-email)
- [Postmark — Pricing](https://postmarkapp.com/pricing) · [Postmark — Message Streams](https://postmarkapp.com/message-streams)
- [Amazon SES — Pricing](https://aws.amazon.com/ses/pricing/)
- [DigitalOcean — Managed Databases pricing](https://www.digitalocean.com/pricing/managed-databases)
- [endoflife.date — Spring Boot](https://endoflife.date/spring-boot) · [PostgreSQL](https://endoflife.date/postgresql) · [Go](https://endoflife.date/go) · [Node.js](https://endoflife.date/nodejs) · [Elixir](https://endoflife.date/elixir) · [Erlang/OTP](https://endoflife.date/erlang) · [Rust](https://endoflife.date/rust)
- [handlebars.java — README](https://github.com/jknack/handlebars.java) · [Releases](https://github.com/jknack/handlebars.java/releases)
- [Gmail — Email sender guidelines FAQ](https://support.google.com/mail/answer/14229414?hl=en) · [Yahoo — Sender best practices](https://senders.yahooinc.com/best-practices/) · [Microsoft sender requirements (resumen PowerDMARC)](https://powerdmarc.com/microsoft-sender-requirements/)
- [FTC — CAN-SPAM Act: A Compliance Guide for Business](https://www.ftc.gov/business-guidance/resources/can-spam-act-compliance-guide-business)
- [Ley 8968 y PRODHAB — resumen 2026](https://www.recordinglaw.com/world-laws/world-data-privacy-laws/costa-rica-data-privacy-laws/) · [Expediente 23.097 (Delfino)](https://delfino.cr/asamblea/proyecto/23097) · [Ley 10946 (Bufete de Costa Rica)](https://bufetedecostarica.com/gobernanza-de-los-servicios-digitales-y-el-comercio-electronico-en-costa-rica-10946/)
- [Alegra — Comprobantes electrónicos v4.4](https://blog.alegra.com/costa-rica/comprobantes-provisionales-y-electronicos-costa-rica/)
