# Resumen de decisiones — auditoría del 2026-10-05

Aceptadas por el owner el 2026-10-05, salvo la fila 16 (adjuntos), que rechazó. Las filas 31–36 recogen sus respuestas a las preguntas abiertas.

| # | Área | Decisión anterior | Decisión nueva | Motivo (una línea) | ADR / requisito |
|---|---|---|---|---|---|
| 1 | Aislamiento | `tenant_id` en código; RLS en fase 2 | RLS `FORCE` *fail-closed* desde V2 + FK compuestas + 3 roles de BD + 2 `DataSource` | Datos personales de 4 productos; un solo error de código no debe bastar para filtrarlos | ADR-0008 (supersede 0007), NFR-08 |
| 2 | Lenguaje / stack | Java 25 + Spring Boot 4.1 "por consistencia" | Se mantiene, con hilos virtuales, matriz ponderada (Java 4,15 frente a Go 3,90) y criterio de cambio explícito | El 96 % de la latencia es la BD; lo decisivo es no añadir un stack a la cartera | ADR-0009 (supersede 0006) |
| 3 | Semántica de entrega | "Exactamente una vez" con riesgo residual | Al menos una vez + `Idempotency-Key = message.id` hacia Resend (24 h) + ventana < 23 h | Resend ya ofrece idempotencia; el diseño anterior no cerraba el duplicado por timeout | ADR-0010, NFR-04 |
| 4 | Cola | Lote de 25, lock compartido, barrido sin contar intentos | Toma por huecos libres, `attempts++` al tomar, `lock_token` (*fencing*), lock de 60 s por mensaje | Evita duplicados entre workers y bucles infinitos con mensajes "veneno" | ADR-0010 (supersede 0002), FR-11/12 |
| 5 | Prioridad | Solo por `next_attempt_at` | `priority` por categoría (`SECURITY` > `TRANSACTIONAL` > `NOTICE`) | Un aviso masivo no debe retrasar una recuperación de contraseña | FR-36, NFR-19 |
| 6 | Disparador de la cola | > 50 msg/s sostenidos | p95 de toma > 50 ms, antigüedad > 60 s, > 3 M/mes o fan-out | 50 msg/s ≈ 130 M/mes: nunca se habría disparado | ADR-0010, NFR-06 |
| 7 | Máquina de estados | `DELIVERED` terminal; "nunca retrocede" | Orden de precedencia; `DELIVERED` estable → `BOUNCED`/`COMPLAINED`; efectos independientes del estado | Las quejas siempre llegan después de la entrega | `02` §2, FR-17 |
| 8 | Eventos | delivered/bounced (hard/soft)/complained/opened/clicked/deferred | Mapeo completo de Resend, incluidos `failed`, `suppressed` y `delivery_delayed`; `BOUNCED_SOFT` → `DELIVERY_DELAYED` | El proveedor no emite "soft bounce"; los mensajes suprimidos quedaban en `SENT` | `02` §2.1, `06` §3.6 |
| 9 | Webhooks | `Webhook-Id/Timestamp/Signature` | `svix-id/timestamp/signature`, cuerpo crudo, secreto por endpoint, dedupe por `svix-id` | Son las cabeceras reales de Resend | FR-16, `07` §9 |
| 10 | Supresión | Por tenant, siempre | `HARD_BOUNCE`/`COMPLAINT`/`PROVIDER` globales; `MANUAL` por tenant; se comprueban `to`/`cc`/`bcc`; `email_hash` | La lista de Resend es de todo el equipo | ADR-0012, FR-10, FR-18 |
| 11 | Plantillas | Handlebars logic-less, `{{{ }}}` prohibido | Además: `{{& }}` y parciales prohibidos, lista blanca de helpers, `MapValueResolver`, linter HTML, escapado por contexto, URL limitadas | Cerrar la inyección y el XSS de verdad | ADR-0011 (supersede 0005), FR-04, AC-07.8 |
| 12 | API keys | Una key lo puede todo | Ámbitos + CIDR opcional; prefijo de 8 caracteres; varias claves de admin | Una key filtrada no debe poder publicar plantillas | ADR-0017 (supersede 0004), FR-33 |
| 13 | Anti-abuso | Rate limit + cuota de 5 000/día | Cuota por defecto de 1 000 (~3× el volumen esperado) + pausa automática por volumen, rebotes y quejas | Contener una key filtrada en minutos | FR-34, FR-21 |
| 14 | Tenant suspendido | Contradictorio (`FAILED` frente a retenido) | Retenido en `QUEUED`; `TENANT_SUSPENDED` deja de ser `failure_code` | La suspensión preventiva no debe destruir la cola | AC-02.3, `06` §4 |
| 15 | Idempotencia del cliente | Clave recomendada `signup-{userId}-{fecha}`; comprobación después del rate limit | Clave = id del evento de dominio; comprobación antes del rate limit; contador en la misma transacción | La clave por fecha colapsaba solicitudes legítimas; las repeticiones consumían cuota | FR-08, FR-21, `07` §12 |
| 16 | Adjuntos | Fuera del MVP | ~~Dentro, acotados~~ → **siguen fuera** (owner: ningún producto los envía); diseño conservado | Sin caso de uso real | ADR-0015 **Rejected**, FR-30 Won't |
| 17 | Envíos programados | Fuera del MVP | **Should** (`sendAt` ≤ 30 días; owner) | Coste casi nulo gracias a `next_attempt_at` | FR-31 |
| 18 | Webhooks salientes | Fuera del MVP | Siguen fuera (Won't) con un disparador medible | Ningún producto los necesita para operar | FR-32 |
| 19 | Retención y privacidad | Variables 90 días; payload de eventos íntegro; purga Should | Variables a los 7 días (las sensibles al enviar); payload minimizado; purga Must; borrado por titular; logs 30 días | Minimizar datos personales y tokens (Ley 8968) | FR-23, FR-29, NFR-15, NFR-20 |
| 20 | Seguimiento de aperturas y clics | Activo como evento esperado | Desactivado por defecto; solo `NOTICE`; prohibido en `SECURITY` | No reescribir enlaces con token por un tercero | AC-04.11 |
| 21 | Proveedor | Resend; comparativa con precios erróneos | Resend ratificado; precios verificados (SES 0,10 USD/1 000); Pro en cuanto haya > 1 producto; staging en otra cuenta | Datos reales; el límite de 100/día del plan gratuito bloquea los correos críticos | ADR-0016 (supersede 0003), `04` §5 |
| 22 | Disparadores de proveedor | "Deliverability crítica" / > 200 000/mes | Demoras/rebotes > 2 % en 7 días o incidente de cuenta → Postmark; ahorro > 150 USD/mes en 3 meses → SES | Hacerlos medibles | ADR-0016 |
| 23 | Límite de tasa hacia el proveedor | "Respeta el rate limit" sin mecanismo | `WORKER_PROVIDER_RPS` = 8 por instancia; suma ≤ 80 % del límite de la cuenta (10 rps) | Resend limita a 10 rps por equipo | NFR-21, AC-12.6 |
| 24 | Correo masivo | Fuera para siempre (anti-roadmap) | Fuera por ahora, con disparador concreto y diseño obligatorio (otra cuenta, subdominio, cola y rol) | La reputación se separa por cuenta, dominio e IP, no por microservicio | ADR-0013, `11` |
| 25 | Protocolo de entrada | REST/JSON | REST/JSON + keep-alive obligatorio + endpoint de lote; sin gRPC, binario ni cola | Medido: el protocolo es ~1,5 % del tiempo; el lote ahorra ~3× de BD | ADR-0014, FR-35 |
| 26 | Costo | ≤ 25 USD/mes con una BD de 2 vCPU/4 GB | ≤ 25 USD (plan gratuito) / ≤ 50 USD (Pro) con una BD de 1 vCPU/1 GB | La BD de referencia costaba 60,90 USD/mes | NFR-16 |
| 27 | Migraciones | Flyway al arrancar en cada instancia | Job `APP_ROLE=migrate` con el rol propietario | Con RLS, el dueño de las tablas no debe ejecutar la app | NFR-09 |
| 28 | Health | `DEGRADED` y "refleja el proveedor" | Liveness sin dependencias; readiness solo BD; indicador del proveedor basado en resultados recientes; estado personalizado mapeado a 200 | `DEGRADED` no es estándar; el *ping* al proveedor gasta rate limit | FR-24 |
| 29 | Tareas programadas | Tabla `scheduled_lock` | `pg_try_advisory_xact_lock` | Más simple; los documentos se contradecían | `05` §5 |
| 30 | Transporte interno | TLS "si no hay fricción" | TLS o red privada cifrada obligatorios | Las API keys son *bearer* | `08` §2 |
| 31 | Idiomas | Solo `tenant.locale`; i18n en fase 2 | Versiones por idioma (`es-CR`, `en`), `locale` por mensaje con caída al español | El owner quiere el inglés implementado y listo | ADR-0018, FR-37 |
| 32 | Nombre del remitente | Fijo por tenant | `fromName` opcional por petición | Colmena envía en nombre de cada negocio | AC-09.5 |
| 33 | Destinatario suprimido | `FAILED/SUPPRESSED` | Además, `suppressionReason`, para que el producto use otra dirección | Canal alternativo para recuperación (respuesta 13) | AC-10.1 |
| 34 | Ámbito normativo | GDPR/CAN-SPAM "a confirmar" | No aplican (solo Costa Rica) | Respuesta 8 | NFR-20, `11` §5 |
| 35 | Datos de terceros (Colmena) | Supuesto de uso interno | Colmena encargada; email-service subencargado; deuda `x-sensitive` | Respuesta 12 | NFR-20, `08` §6.1 |
| 36 | Aviso de privacidad / transferencia | Bloqueante "para producción" | Pendiente; no bloquea el desarrollo | Respuesta 9 | `08` §10 |
