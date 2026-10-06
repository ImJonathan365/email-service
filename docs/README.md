# email-service — Documentación base

Microservicio interno y centralizado de envío de correo **transaccional** para mis productos: PipeMend (pipeline ETL con IA), Colmena (SaaS de administración de negocios), Chinamo (POS) y Payverica (verificación de pagos SINPE). Un solo servicio multi-tenant, con plantillas propias por producto, envío asíncrono, trazabilidad completa y aislamiento garantizado por la base de datos.

> Esta carpeta es la **fuente de verdad** del proyecto. Cualquier colaborador (humano o modelo de IA) debe leerla antes de escribir código. Si el código y estos documentos se contradicen, mandan los documentos hasta que se apruebe un cambio mediante un ADR.

> **Estado (2026-10-05):** documentación revisada tras la auditoría (`auditoria/`). El owner aceptó los ADR-0008 a 0014, 0016, 0017 y 0018, rechazó el 0015 (adjuntos) y respondió las preguntas abiertas. **H0 cerrado: se puede empezar H1.**

## Índice

| Archivo | Contenido |
|---|---|
| [01-vision-y-alcance.md](01-vision-y-alcance.md) | Problema, productos consumidores, objetivos, casos de uso, in-scope / out-of-scope del MVP |
| [02-requisitos-funcionales.md](02-requisitos-funcionales.md) | FR-xx con criterios de aceptación Dado/Cuando/Entonces; máquina de estados y mapeo de eventos |
| [03-requisitos-no-funcionales.md](03-requisitos-no-funcionales.md) | NFR-xx medibles (rendimiento, fiabilidad, aislamiento, privacidad, cumplimiento) |
| [04-stack-tecnologico.md](04-stack-tecnologico.md) | Stack, proveedor de correo y protocolo de entrada, con justificación, comparativas y medidas |
| [05-arquitectura.md](05-arquitectura.md) | Componentes, flujo de envío, reintentos, tareas, configuración, despliegue |
| [06-modelo-de-datos.md](06-modelo-de-datos.md) | Entidades, DDL de referencia con RLS, consultas de la cola, retención |
| [07-api-interna.md](07-api-interna.md) | Especificación de la API, catálogo de errores y contrato de integración para los productos |
| [08-seguridad.md](08-seguridad.md) | Secretos, autenticación, anti-abuso, aislamiento, privacidad, Ley 8968, incidentes |
| [09-roadmap.md](09-roadmap.md) | Fases: MVP y mejoras posteriores con disparadores medibles |
| [10-guia-para-agentes-ia.md](10-guia-para-agentes-ia.md) | Contexto y reglas para modelos de IA que asistan en el desarrollo |
| [11-correo-masivo.md](11-correo-masivo.md) | Correo masivo y marketing: por qué todavía no, disparador, separación obligatoria y requisitos |
| [adr/](adr/) | Architecture Decision Records (decisiones y su porqué) |
| [auditoria/](auditoria/) | Informe de auditoría del 2026-10-05, resumen de decisiones y preguntas abiertas |
| [CHANGELOG.md](CHANGELOG.md) | Registro de avance y decisiones |

## Resumen en 12 líneas

- **Un solo servicio** (API + worker en el mismo artefacto, escalables por separado) + **PostgreSQL** propio. Sin microservicios adicionales ni broker externo en el MVP.
- **Multi-tenant con doble barrera**: cada producto es un *tenant* con sus API keys (con ámbitos), plantillas, límites y dominio remitente; `tenant_id` en cada consulta **y** Row Level Security en la base de datos.
- **Envío asíncrono**: la API encola y responde `202` en milisegundos; un worker envía por prioridad (`SECURITY` > `TRANSACTIONAL` > `NOTICE`) con reintentos, backoff y *fencing*.
- **Proveedor transaccional por API** (**Resend**; alternativas evaluadas: Postmark y Amazon SES) detrás de `EmailSender`/`WebhookVerifier`.
- **Sin duplicados en la práctica**: `Idempotency-Key` del cliente + `Idempotency-Key = message.id` hacia el proveedor (24 h); semántica al menos una vez.
- **Desarrollo local con Mailpit** (SMTP) cambiando una variable de entorno, sin cambiar la arquitectura.
- **Idempotencia exigida por contrato** con `Idempotency-Key` derivada del evento de dominio del producto.
- **Webhooks entrantes** firmados (Svix) actualizan el estado: entregado, rebote, queja, demora, fallo, supresión.
- **Supresión global** para rebotes duros y quejas (como hace el proveedor) y **por tenant** para bajas manuales.
- **Plantillas seguras**: *logic-less*, escapado por contexto, linter HTML, enlaces solo a hosts del producto.
- **Secretos solo en variables de entorno / secrets manager**; en la base de datos solo hashes de API keys.
- **Sin lógica de negocio de ningún producto** dentro del servicio, **sin correo masivo** (ADR-0013) y **sin adjuntos** (enlaces firmados en su lugar).
- **Idiomas:** español (`es-CR`) por defecto e inglés disponible por mensaje (ADR-0018).

## Jerarquía de autoridad

1. ADR con estado `Accepted` (`adr/`).
2. Documentos numerados de esta carpeta.
3. Código y comentarios del repositorio.
4. Instrucciones puntuales en un chat (si contradicen 1–2, **consultar al owner** antes de actuar).

~~Regla transitoria (2026-10-05)~~: ya no aplica; todos los ADR de la auditoría tienen un estado decidido.

## Glosario

| Término | Definición |
|---|---|
| **Tenant / servicio origen** | Producto que usa el email-service (p. ej. `colmena`). Unidad de aislamiento: API keys, plantillas, límites, supresiones manuales y envíos son siempre de un tenant. |
| **Mensaje (`email_message`)** | Un envío individual solicitado por un tenant. Tiene estado, prioridad, intentos y trazabilidad. También es el trabajo en cola. |
| **Plantilla / versión** | Plantilla identificada por `key` dentro del tenant, con categoría; sus versiones publicadas son inmutables. |
| **Categoría** | `SECURITY` (verificación, recuperación, códigos), `TRANSACTIONAL` (recibos, comprobantes, pagos) o `NOTICE` (avisos operativos). Determina la prioridad y si se permite el seguimiento. |
| **Ámbito (scope)** | Permiso de una API key: `emails:send`, `emails:read`, `templates:write`, `suppressions:write`. |
| **Proveedor** | Servicio externo que entrega el correo (Resend, Postmark, SES) o el SMTP local de desarrollo. |
| **Evento** | Hecho reportado por el proveedor vía webhook (sent, delivered, delivery_delayed, bounced, complained, failed, suppressed, opened, clicked). |
| **Supresión** | Dirección bloqueada: **global** tras un rebote duro, una queja o una supresión del proveedor; **por tenant** si es manual. |
| **Pausa** | Estado de un tenant cuyo envío se detuvo por una anomalía (volumen, rebotes, quejas); sus mensajes quedan retenidos y solo un administrador la levanta. |
| **Worker** | Proceso que toma mensajes encolados de PostgreSQL y los envía. Mismo artefacto que la API, distinto perfil. |
| **Idempotency-Key** | Clave enviada por el cliente para que un reintento de la misma solicitud no genere un segundo correo; el servicio usa a su vez el `id` del mensaje como clave de idempotencia hacia el proveedor. |
| **lock_token** | *Fencing token* de cada toma de un mensaje; impide que un worker que perdió el lock cierre o reenvíe el mensaje. |
| **RLS** | Row Level Security de PostgreSQL: la base de datos filtra por `app.tenant_id` aunque el código olvide hacerlo. |

## Datos que recibe el proveedor de correo (encargado del tratamiento)

Para cada envío, Resend (EE. UU.) recibe: la dirección y el nombre del destinatario (`to`, `cc`, `bcc`), el remitente y el `reply-to`, el asunto y el cuerpo renderizados (que pueden contener datos personales de las variables), las etiquetas (`tags`) y el `message_id` interno como etiqueta. El proveedor conserva estos datos según su plan (30 días según su página de precios consultada el 2026-10-05; a confirmar por plan). Cada producto debe cubrir esta transferencia en su aviso de privacidad (Ley 8968, art. 14).
