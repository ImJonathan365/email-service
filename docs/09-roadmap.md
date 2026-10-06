# 09 — Roadmap de fases

> Revisión 2026-10-05: H0 cerrado (owner, 2026-10-05), hitos con los FR nuevos, RLS en el MVP, lote, `sendAt` e idiomas en el MVP (sin adjuntos), disparadores medibles y correo masivo con disparador propio.

Este documento importa tanto como los demás porque **dice qué NO se hace todavía**. Cada fase se cierra antes de empezar la siguiente.

## Fase 0 — MVP funcional (objetivo: ~7 semanas a tiempo parcial)

Meta: un producto real enviando correo en producción a través del servicio.

| Hito | Contenido | FR / NFR |
|---|---|---|
| **H0. Decisiones** ✅ (2026-10-05) | ADR-0008 a 0014, 0016 y 0017 aceptados; 0015 rechazado; 0018 aceptado; preguntas abiertas respondidas | — |
| **H1. Esqueleto** | Repo, Docker Compose (app + PostgreSQL con 3 roles + Mailpit), Flyway con job `migrate`, RLS base, health, OpenAPI, CI, ArchUnit; medir RSS (ADR-0009) | FR-24, FR-25, NFR-08, NFR-09, NFR-12 |
| **H2. Tenancy** | Tenants, API keys (emisión, revocación, hash, ámbitos, CIDR), autenticación, contexto de tenant + `set_config`, administración, auditoría básica; medir el coste de RLS | FR-01, FR-02, FR-03, FR-22, FR-33 |
| **H3. Plantillas** | CRUD, categorías, versiones por idioma (es-CR, en), publicación inmutable, Handlebars endurecido + linter, escapado por contexto, preview | FR-04, FR-05, FR-06, FR-36, FR-37 |
| **H4. Envío de extremo a extremo** | `POST /v1/emails`, validaciones, URL permitidas, idempotencia, cola con prioridad y *fencing*, worker, `SmtpEmailSender` → Mailpit | FR-07 a FR-13, FR-15 |
| **H5. Proveedor real** | `ResendEmailSender` con `Idempotency-Key`, limitador de tasa, circuit breaker; dominio verificado (SPF/DKIM/DMARC); envío real desde staging (cuenta separada); test de "respuesta perdida" | FR-14, NFR-04, NFR-21 |
| **H6. Eventos** | Webhooks Svix (cuerpo crudo), mapeo completo de eventos, estados por precedencia, supresión global/tenant, espejo del proveedor, API de supresiones | FR-10, FR-16, FR-17, FR-18 |
| **H7. Operación y protección** | Consultas y listados, rate limit y cuotas, anomalías y pausa, métricas y alertas, purga (variables, retención), borrado por titular, logs correlacionados | FR-19, FR-20, FR-21, FR-23, FR-29, FR-34 |
| **H8. Lote y envío diferido** (rev. 2026-10) | Endpoint de lote, `sendAt` | FR-35, FR-31 |
| **H9. Producción** | Despliegue (migrate → api → worker), respaldos cifrados, restauración probada, checklist de seguridad, README e integración del primer producto | `08` §10 |

**Definición de terminado de la Fase 0:**

- [ ] Un producto real en producción envía sus correos por el servicio.
- [ ] `docker compose up` desde un clon limpio permite enviar un correo a Mailpit sin credenciales externas.
- [ ] Todos los FR **Must** tienen sus criterios de aceptación cubiertos por tests automatizados.
- [ ] Tests de aislamiento multi-tenant en verde para cada endpoint `/v1/**`, más el test sin contexto (RLS) y el de FK cruzada.
- [ ] Con el proveedor caído (simulado), la API sigue aceptando y la cola se drena al recuperarlo.
- [ ] Test de "respuesta perdida": el proveedor simulado registra **un** envío.
- [ ] NFR-19 medido en staging: aceptación → `SENT` p95 ≤ 5 s.
- [ ] Checklist de seguridad (`08` §10) completo.
- [ ] README con guía de integración en < 30 minutos.

## Fase 1 — Comodidad de operación (después de 2–3 productos integrados)

| Mejora | Por qué | Requisito previo |
|---|---|---|
| **Panel web mínimo** (lista de envíos, detalle, reenvío manual, preview de plantillas, tenants pausados) | Dejar de consultar con `curl` y SQL | Que la operación diaria duela de verdad |
| **Editor y previsualización de plantillas** con datos de ejemplo guardados | Iterar diseño sin desplegar | Panel web |
| **SDK cliente** (Java y TypeScript) generado desde OpenAPI, con keep-alive, reintentos e idempotencia correctos por defecto | Integrar productos nuevos más rápido y sin errores de contrato | 3+ productos |
| **Métricas exportadas a Prometheus + panel** | Visibilidad histórica | Volumen que lo justifique |
| **Adjuntos** (FR-30, diseño en ADR-0015) | Solo si aparece un caso que un enlace firmado no resuelva | Un caso de uso real |

## Fase 2 — Robustez

| Mejora | Por qué |
|---|---|
| ~~Row Level Security en PostgreSQL~~ | **(rev. 2026-10)** Pasa al MVP (ADR-0008) |
| **Contenido inline sin plantilla** (activable por tenant) | Casos puntuales; con sanitización y linter |
| **Más idiomas y parciales controlados** | es-CR y en ya están en el MVP (ADR-0018); cabecera y pie compartidos |
| **Firma de peticiones (HMAC) además de la API key** | Defensa extra si el servicio se expone fuera de la red privada |
| **Pruebas de carga automatizadas** | Validar NFR-01, NFR-02 y NFR-19 al crecer |
| **Modo sandbox por tenant** | Simular envíos en integraciones de prueba |
| **Webhooks salientes** (FR-32) | Solo si se cumple su disparador |

## Fase 3 — Escala (solo con métricas que lo justifiquen) (rev. 2026-10)

| Mejora | Disparador concreto |
|---|---|
| **Cola externa (SQS)** | p95 de toma > 50 ms durante 3 días, antigüedad de cola > 60 s con el proveedor sano durante 3 días, > 3 M correos/mes o fan-out (ADR-0010) |
| **Multi-proveedor con failover** | Una caída real del proveedor con correos `SECURITY` retrasados > 30 min |
| **Migración a Postmark** | Demoras/rebotes atribuibles al proveedor > 2 % durante 7 días, o un incidente de cuenta (ADR-0016) |
| **Migración a Amazon SES** | Ahorro verificado > 150 USD/mes durante 3 meses (ADR-0016) |
| **Particionado de `email_message`** | > 50 millones de filas o degradación medible de los listados |
| **Réplica de lectura** | Los listados afectan a la latencia de aceptación |
| **IP dedicada y calentamiento** | > 3 000 correos/día sostenidos (requisito de Resend) y una reputación de IP compartida que perjudique de forma medible |
| **Protocolo de entrada distinto de REST** | > 200 req/s sostenidas, o p95 > 150 ms no atribuible a la BD, o > 30 M correos/mes (ADR-0014) |
| **Una cuenta de proveedor por producto** | Una queja o una revisión de cuenta provocada por un producto afecta de forma medible a otro (ADR-0012) |

## Correo masivo y marketing (rev. 2026-10, ADR-0013)

No entra en ninguna fase hasta que se cumpla su disparador (`11-correo-masivo.md` §2):
- (a) ≥ 1 000 destinatarios no transaccionales más de una vez al mes durante 2 meses;
- (b) ≥ 2 productos con campañas y sin registro de consentimiento probado en la herramienta del proveedor;
- (c) > 100 USD/mes en el producto de marketing del proveedor durante 3 meses.

Mientras tanto, los avisos masivos se hacen con el producto de *broadcast* del proveedor en una cuenta y un subdominio separados.

## Anti-roadmap (lo que no se hará)

- ~~Campañas de marketing, listas de suscripción, segmentación, A/B testing~~ **(rev. 2026-10)**: sustituido por "Correo masivo y marketing" arriba. Hay un disparador y un diseño obligatorio; no se hace "a medias" dentro del flujo transaccional.
- Recepción y parseo de correo entrante.
- Editor visual drag-and-drop de plantillas.
- Multirregión activo-activo.
- Kubernetes o service mesh para un servicio con dos roles.
- Convertirlo en un producto SaaS para terceros (cambiaría por completo los requisitos de facturación, aislamiento y cumplimiento; sería otro proyecto).
- gRPC o ingestión por bus "por rendimiento" (medido: no aporta, ADR-0014).

## Criterio para mover algo de fase

Una mejora sube de fase cuando existe **una métrica o un incidente real** que la justifique, no cuando parece interesante. Se registra en un ADR: qué se observó, qué se decide y qué se espera que mejore.
