# 01 — Visión, objetivos y alcance

> Revisión 2026-10-05: productos reales, alcance del MVP ajustado (ámbitos de keys, anomalías, RLS, `sendAt`, idiomas); respuestas del owner aplicadas, objetivos corregidos.

## 1. Problema

Cada producto que lanzo necesita enviar correo transaccional: verificación de cuenta, recuperación de contraseña, comprobantes, confirmaciones de pago, alertas y notificaciones de procesos. Si cada producto implementa su propio envío:

- se duplica el mismo código de integración, reintentos y manejo de errores en cada repositorio;
- las credenciales del proveedor terminan copiadas en varios sitios (más superficie de fuga, rotación imposible en la práctica);
- no hay historial unificado: cuando un usuario dice "no me llegó el correo", hay que investigar producto por producto;
- rebotes y quejas no se comparten: un producto sigue enviando a una dirección que ya rebotó en otro (y el proveedor, que sí comparte su lista de supresión entre dominios, los salta sin que el producto se entere);
- cada producto reinventa las plantillas HTML y su previsualización.

## 2. Solución

Un **microservicio centralizado de correo transaccional**, con una API interna que consumen mis productos. El servicio se encarga de: autenticar al producto origen, validar la solicitud, renderizar la plantilla del producto, encolar, enviar a través de un proveedor transaccional, reintentar, registrar el estado y exponer el historial.

```
PipeMend  ─┐
Colmena   ─┤
Chinamo   ─┼──▶  email-service  ──▶  proveedor transaccional (Resend)  ──▶  bandeja del usuario
Payverica ─┘         │    ▲                    │
                     ▼    └────── webhooks ────┘
                PostgreSQL (historial, plantillas, supresiones; RLS por tenant)
```

### 2.1 Productos consumidores (rev. 2026-10)

Perfil de envío a partir de la descripción de cada producto y de las respuestas del owner del 2026-10-05 (todos en Spring Boot; frontends en TypeScript; solo usuarios en Costa Rica); el detalle de cada correo, a confirmar con sus documentos (ver `docs/auditoria/2026-10-05-informe-auditoria.md`, "Qué verificar en los productos consumidores").

| Tenant | Producto | Correos típicos | Categorías | Necesidades particulares |
|---|---|---|---|---|
| `pipemend` | Pipeline ETL con IA | Alertas de fallo de pipeline, resúmenes, aviso de exportación mensual lista | `TRANSACTIONAL`, `NOTICE`, `SECURITY` (acceso) | Exportaciones como **enlace firmado**, no como adjunto |
| `colmena` | SaaS de administración de negocios | Invitaciones, verificación, recuperación, recordatorios (incluidos los de productos o deudas de los clientes de cada negocio) | `SECURITY`, `TRANSACTIONAL`, `NOTICE` | `sendAt` (recordatorios); `fromName` por negocio; **trata datos de los clientes de sus clientes** (encargado del tratamiento, NFR-20); avisos masivos fuera (ADR-0013) |
| `chinamo` | POS | Cierres de caja, accesos, notificaciones de operación | `TRANSACTIONAL`, `SECURITY` | Sin adjuntos (owner: no envía comprobantes por correo) |
| `payverica` | Verificación de pagos SINPE | Confirmación o rechazo de pago verificado, códigos, alertas | `SECURITY`, `TRANSACTIONAL` | Datos sensibles en variables (`x-sensitive`); latencia baja (NFR-19) |

## 3. Objetivos

| ID | Objetivo | Métrica de éxito |
|---|---|---|
| OBJ-1 | Integrar un producto nuevo en menos de 30 minutos | Crear tenant + API key + plantilla y enviar el primer correo siguiendo solo `07-api-interna.md` |
| OBJ-2 | No bloquear al producto que envía | p95 de `POST /v1/emails` ≤ 150 ms, independiente del estado del proveedor |
| OBJ-3 | Cero correos duplicados por reintentos (rev. 2026-10) | 100 % de las solicitudes con `Idempotency-Key` repetida devuelven el mensaje original, y 0 duplicados en el test de "respuesta perdida" del proveedor (NFR-04) |
| OBJ-4 | Cero pérdida de correos aceptados (rev. 2026-10) | Todo mensaje aceptado (`202`) llega a un estado terminal (`BOUNCED`, `COMPLAINED`, `FAILED`, `CANCELED`), a `DELIVERED`, o a `SENT` final por tiempo (72 h sin eventos); nada queda en `QUEUED` elegible o en `SENDING` sin alerta |
| OBJ-5 | Trazabilidad completa | Para cualquier envío: quién lo pidió, con qué plantilla y versión, qué respondió el proveedor y qué eventos llegaron |
| OBJ-6 | Aislamiento entre productos (rev. 2026-10) | Un tenant nunca puede leer ni afectar datos, plantillas o cuotas de otro: verificado por tests **y** garantizado por RLS y FK compuestas en la BD |
| OBJ-7 | Secretos fuera de la base de datos y del código | Solo hashes de API keys en BD; credenciales del proveedor solo en el entorno o en un secrets manager |
| OBJ-8 | Desarrollo local sin cuenta de proveedor | `docker compose up` + Mailpit: se envía y se inspecciona correo sin salir de la máquina |
| OBJ-9 | Correo crítico rápido (nuevo) | Aceptación → `SENT` p95 ≤ 5 s para `SECURITY` y `TRANSACTIONAL` (NFR-19) |
| OBJ-10 | Contener el abuso de una key filtrada (nuevo) | Una key de producto no puede publicar plantillas ni enlazar a hosts ajenos; un pico anómalo pausa el tenant en ≤ 5 min (FR-33, FR-34) |

## 4. Casos de uso principales

| ID | Caso de uso | Actor | Resumen |
|---|---|---|---|
| UC-01 | Enviar correo transaccional con plantilla | Producto origen | `POST /v1/emails` con `templateKey` + variables → `202` + `id` |
| UC-02 | Consultar el estado de un envío | Producto origen / yo | `GET /v1/emails/{id}` con historial de eventos |
| UC-03 | Listar y filtrar envíos | Producto origen / yo | Por estado, plantilla, destinatario, rango de fechas |
| UC-04 | Crear y versionar una plantilla | Yo, con una key `templates:write` | Crear plantilla (con categoría), crear versión borrador, previsualizar, publicar |
| UC-05 | Previsualizar una plantilla con datos de ejemplo | Yo | Render sin enviar, para ver el HTML resultante |
| UC-06 | Recibir eventos del proveedor | Proveedor (webhook) | Firma verificada → actualiza estado y guarda evento |
| UC-07 | Gestionar supresiones | Sistema / yo | Rebote duro o queja bloquea la dirección globalmente; bajas manuales por tenant |
| UC-08 | Alta de un producto nuevo (tenant) y su API key | Yo (admin global) | Crear tenant, emitir key con ámbitos (se muestra una sola vez) |
| UC-09 | Rotar o revocar una API key | Yo (admin global) | Emitir nueva key, periodo de solapamiento, revocar la anterior |
| UC-10 | Reintento automático ante fallo del proveedor | Sistema | Backoff exponencial, clasificación de errores, tope de intentos y ventana < 23 h |
| UC-11 | Operar con el proveedor caído | Sistema | Los mensajes se acumulan en la cola y se envían al recuperarse; la API sigue aceptando |
| UC-12 | Purgar datos antiguos | Sistema | Retención configurable por tenant; variables purgadas antes |
| UC-13 | ~~Enviar un comprobante con adjuntos~~ | — | DEPRECATED (owner 2026-10-05: no hay caso de uso; FR-30 Won't) |
| UC-17 | Programar un recordatorio (nuevo) | Colmena | `POST /v1/emails` con `sendAt` (≤ 30 días) |
| UC-18 | Enviar en inglés (nuevo) | Cualquier producto | `POST /v1/emails` con `locale = "en"`; si no hay traducción, cae al español |
| UC-14 | Contener una key filtrada (nuevo) | Sistema / yo | Pausa automática por anomalía; revocar la key; reanudar |
| UC-15 | Atender una solicitud de borrado de un titular (nuevo) | Yo (admin) | `POST /admin/v1/data-subjects/erase` en ≤ 5 días hábiles |
| UC-16 | Enviar un aviso operativo a varios usuarios (nuevo) | Producto origen | `POST /v1/emails/batch` (≤ 100 por llamada, categoría `NOTICE`, dentro de la cuota) |

## 5. Alcance del MVP (in-scope)

1. API interna REST autenticada por API key con **ámbitos** y CIDR opcionales (una o varias por tenant).
2. Envío transaccional **solo mediante plantilla del tenant** (HTML + texto), con variables validadas por esquema.
3. Plantillas versionadas con categoría (`SECURITY`/`TRANSACTIONAL`/`NOTICE`): versiones publicadas inmutables, linter de seguridad y escapado por contexto.
4. Previsualización/render de plantilla sin enviar.
5. Cola persistente en PostgreSQL con prioridad + worker con reintentos, backoff, *fencing* e idempotencia hacia el proveedor.
6. Idempotencia por `Idempotency-Key`.
7. Integración con un proveedor transaccional por API (Resend) detrás de `EmailSender`/`WebhookVerifier`.
8. Modo local SMTP (Mailpit) seleccionable por variable de entorno.
9. Webhooks entrantes del proveedor con verificación de firma y mapeo completo de eventos (`02` §2.1).
10. Lista de supresión global (rebotes duros, quejas, espejo del proveedor) y por tenant (bajas manuales), con bloqueo automático.
11. Historial y consulta de envíos y eventos.
12. Límites de tasa por tenant (por minuto y por día), cuotas de tamaño y **detección de anomalías con pausa automática**.
13. API de administración (tenants, API keys, supresiones globales, borrado por titular) con credencial de administrador separada y solo en red privada.
14. Auditoría de acciones administrativas y de cambios de plantilla.
15. Observabilidad: health checks, métricas, logs estructurados con correlación y alertas mínimas.
16. Retención y purga configurables (Must).
17. Docker Compose para desarrollo (app + PostgreSQL + Mailpit) y despliegue con la misma imagen.
18. **(rev. 2026-10)** Row Level Security en PostgreSQL y FK compuestas por tenant (ADR-0008).
19. ~~Adjuntos acotados~~ **(rev. 2026-10-05, owner)**: fuera del MVP (FR-30 Won't).
21. **(rev. 2026-10)** Envío diferido `sendAt` (Should, FR-31).
22. **(rev. 2026-10)** Plantillas por idioma: `es-CR` por defecto y `en` disponible (Should, FR-37, ADR-0018).
20. **(rev. 2026-10)** Endpoint de lote (Should, ADR-0014).

## 6. Fuera de alcance del MVP (out-of-scope)

| Excluido | Motivo / destino |
|---|---|
| Dashboard web / UI de métricas | Fase 1. En el MVP: endpoints + Swagger + SQL |
| Envío con contenido HTML arbitrario en la petición (sin plantilla) | Obliga a buena práctica y reduce la superficie de abuso. Fase 2 con flag por tenant |
| Adjuntos | **(rev. 2026-10-05, owner)** Ningún producto los necesita: Won't (FR-30, ADR-0015 Rejected). Las exportaciones se envían como enlace firmado |
| ~~Envíos programados (`sendAt`)~~ | **(rev. 2026-10)** Entran en el MVP como Should (FR-31) |
| Campañas, boletines, listas y `List-Unsubscribe` de marketing | No entra por ahora (ADR-0013); diseño y disparador en `11-correo-masivo.md` |
| Multi-proveedor simultáneo o failover automático | Fase 3. El MVP deja la interfaz lista, pero configura un proveedor activo |
| Webhooks salientes hacia los productos origen | Won't (FR-32). En el MVP los productos consultan por API |
| SDK cliente publicado (Java/TS) | Fase 1; en el MVP basta con la especificación OpenAPI |
| Plantillas con lógica compleja, parciales compartidos, MJML, más de 2 idiomas | Fase 2 (es-CR y en entran en el MVP, ADR-0018) |
| ~~Row Level Security en PostgreSQL~~ | **(rev. 2026-10)** Entra en el MVP (ADR-0008) |
| SSO/OAuth2 para la administración | No aporta con un solo administrador |
| Recepción de correo entrante (inbound parsing) | Fuera del propósito |
| Kubernetes, service mesh, Kafka, Redis, almacenamiento de objetos | Innecesarios para el volumen objetivo (NFR-10) |
| gRPC, ingestión por cola, SMTP de entrada | Medido: no aportan (ADR-0014) |

## 7. Supuestos y restricciones

- **(rev. 2026-10)** Volumen esperado en el primer año: **< 100 000 correos/mes** entre todos los productos (≈ 0,04 msg/s de media); picos de hasta ~20 msg/s durante segundos (p. ej., un lote de avisos), no sostenidos.
- Lo mantiene **una persona**: la simplicidad operativa pesa más que la escalabilidad teórica.
- Los productos consumidores son de confianza (son míos) y corren en una red donde pueden alcanzar al servicio; aun así se autentican y se limitan como si no lo fueran, porque una key se puede filtrar.
- El servicio nunca conoce la lógica de negocio de un producto: recibe `templateKey` + variables ya resueltas.
- Un solo proveedor activo por entorno; **(rev. 2026-10)** cuentas de proveedor distintas para staging y producción.
- **(rev. 2026-10)** Idioma por defecto de los correos: español de Costa Rica (`es-CR`), zona `America/Costa_Rica`; el inglés (`en`) está disponible por mensaje si la plantilla tiene traducción (ADR-0018).
- **(rev. 2026-10, owner)** Solo usuarios y servicios en Costa Rica: no aplican GDPR ni CAN-SPAM mientras eso no cambie.
- **(rev. 2026-10)** Normativa aplicable de referencia: Ley 8968 (Costa Rica); detalle en NFR-20.

## 8. Principios de diseño (no negociables)

1. **Simple antes que genérico.** Nada de abstracciones para necesidades hipotéticas; las únicas indirecciones son `EmailSender`/`WebhookVerifier` (proveedor) y el motor de plantillas.
2. **Un artefacto desplegable.** API y worker son el mismo binario con distinto perfil.
3. **La base de datos es la cola.** Un componente menos que instalar, respaldar y vigilar (ADR-0010).
4. **Aceptar rápido, entregar con reintentos.** El cliente nunca espera al proveedor.
5. **Los secretos no viven en la base de datos ni en el código.**
6. **Multi-tenant desde la primera línea, con doble barrera**: toda consulta lleva `tenant_id`, y RLS lo impone en la BD (ADR-0008).
7. **Sin lógica de negocio ajena.** Si algo es específico de un producto, va en el producto.
8. **(rev. 2026-10)** **La reputación de envío es un recurso compartido**: nada que pueda generar quejas en masa comparte cuenta de proveedor con el correo crítico (ADR-0013).
