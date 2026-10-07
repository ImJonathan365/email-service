# Changelog del proyecto

Registro de avance y decisiones. Formato: hecho / pendiente / decisiones.

## 2026-10-06 (tarde) — H2: tenancy

- **Hecho:**
  - Errores `application/problem+json` con el catálogo cerrado de `07` §11; `X-Request-Id` en toda respuesta; IP de origen con `TRUSTED_PROXIES`.
  - Administración (FR-02): `X-Admin-Key` contra `ADMIN_API_KEYS`; crear, listar, actualizar, suspender y reactivar tenants; valores por defecto de AC-02.5.
  - API keys (FR-03, FR-33): emisión `esk_{env}_{prefix}_{secret}` (solo prefijo y SHA-256 en la BD), listado sin secretos, revocación idempotente, ámbitos y CIDR fijados al emitir.
  - Autenticación de `/v1/**` (FR-01, FR-33): comparación en tiempo constante, `401` uniforme, `403` por ámbito, CIDR o tenant suspendido, `last_used_at` por minuto y contexto de tenant (RLS) para el resto de la petición. `@RequiresScope` es obligatorio en cada handler `/v1`.
  - Auditoría (FR-22) en la misma transacción que la acción.
  - OpenAPI en dos grupos (AC-25.3): `contracts/email-service.openapi.json` (productos) y `contracts/email-service-admin.openapi.json`.
  - `APP_ROLE=migrate` arranca un contexto mínimo (solo configuración y Flyway).
  - `scripts/seed-local.sh`: tenant `demo` y dos keys.
- **Medido: coste de RLS (ADR-0008)** con `./gradlew benchmarkRls`. PostgreSQL 18 en Docker local, 100 000 mensajes en 20 tenants, 5 000 transacciones por caso, dos ejecuciones. El plan sigue usando `ix_msg_tenant_created`; la política queda como un único `One-Time Filter`.

  | Consulta (p50, µs por transacción) | `email_app` + `set_config` + RLS | `email_system` + `set_config` | `email_system` solo consulta |
  |---|---|---|---|
  | Últimos 20 del tenant | 144–158 | 133–137 | 91–92 |
  | Por tenant + id | 127–128 | 119–122 | 82–91 |

  - El ida y vuelta extra de `set_config` cuesta ~30–45 µs (+40–45 % sobre consultas tan pequeñas).
  - La evaluación de la política sola cuesta +5–15 % en p50.
  - p95/p99 son ruidosos en este entorno.
  - En total, +40–65 µs por transacción: ~3–5 % del camino de aceptación medido (1,38 ms, `04` §2).
- **Pendiente de decisión del owner:**
  - si ese resultado activa el criterio de revisión de ADR-0008 (> 20 %, cumplido por la consulta aislada, no por la transacción de aceptación);
  - código de error para `405` (hoy `404 RESOURCE_NOT_FOUND`);
  - sin caché de API keys (`08` §2 revisado);
  - auditoría agregada y rate limit de `401` por IP, previstos para H7.

## 2026-10-06 — H1: esquema, roles y RLS

- **Decisiones (owner, 2026-10-06):**
  - `BYPASSRLS` de `email_system` se concede en `scripts/init-db-roles.sql` (requiere superusuario), no en `V2__rls.sql`. Si el PostgreSQL gestionado no lo permite, se usará la alternativa de ADR-0008 (política `TO email_system USING (true)`).
  - `tenant` pasa a tener RLS con `FORCE` (política `id = app.tenant_id`). `email_app` solo tiene `SELECT` sobre su propia fila; el alta, la modificación y el borrado de tenants solo se hacen con `email_system`.
  - `email_system` tiene `SELECT/INSERT/UPDATE/DELETE` sobre todas las tablas, salvo `UPDATE`/`DELETE` en `audit_log`.
  - En `suppression`, `email_app` no puede leer `source_tenant_id` ni `source_message_id` (permisos por columna): un tenant no sabe qué otro tenant originó una supresión global.
  - Las políticas usan `nullif(current_setting('app.tenant_id', true), '')::uuid`: en una conexión reutilizada, tras una transacción con `set_config(..., true)`, la variable vale `''` y no `NULL`, y `''::uuid` daría error en lugar de 0 filas.
- **Hecho:**
  - `docs/06` §1 y §5 actualizados con lo anterior.
  - H1 (esqueleto):
    - `APP_ROLE` con validación de configuración al arrancar;
    - los dos `DataSource` y `set_config` por transacción de tenant;
    - V1/V2 y el script de roles;
    - health (liveness/readiness), Swagger y `contracts/email-service.openapi.json` con test de diferencias;
    - ArchUnit, Docker Compose y CI (build, tests, gitleaks).
  - **RSS medido (ADR-0009):** 300 MiB (306 896 KiB, ≈ 314 MB) en reposo con `-Xmx256m`, `APP_ROLE=all`, imagen `eclipse-temurin:25-jre`. Cumple el criterio de ≤ 350 MB.
- **Pendiente:** el resto de validaciones de configuración y los indicadores `provider`/`queue` llegan con el hito que los usa (H2–H7).

## 2026-10-05 (tarde) — Respuestas del owner: H0 cerrado

- **Decisiones:**
  - ADR-0008 a 0014, 0016 y 0017 → Accepted; ADR-0002 a 0007 → Superseded.
  - ADR-0015 (adjuntos) → **Rejected**: ningún producto envía comprobantes. FR-30 → Won't; `email_attachment` no se crea; UC-13 DEPRECATED.
  - FR-31 `sendAt` → Should (H8).
  - Nuevo ADR-0018 (Accepted) y FR-37: plantillas por idioma, `es-CR` por defecto y `en` disponible.
  - AC-09.5: `fromName` por petición (Colmena envía en nombre de cada negocio).
  - AC-10.1: `suppressionReason` en la respuesta, para que el producto use una dirección alternativa.
  - NFR-20: solo Costa Rica (GDPR y CAN-SPAM no aplican); Colmena como encargada de los datos de los clientes de sus negocios (productos, deudas).
  - Transferencia internacional: pendiente, no bloquea el desarrollo.
  - ADR-0009: confirmado que todos los servicios son Spring Boot y los frontends TypeScript.
- **Pendiente (no bloquea H1):**
  - hosting (CIDR, `BYPASSRLS`);
  - textos legales de privacidad de cada producto y términos de Colmena;
  - consulta legal sobre la normativa de cobro para los recordatorios de deuda.
- **Siguiente:** Fase 0, hito H1.

## 2026-10-05 — Auditoría independiente y revisión de la documentación

- **Hecho:**
  - Auditoría completa de `/docs`, los ADR y `AGENTS.md`: 4 hallazgos bloqueantes, 17 graves, 22 menores y 3 mejoras (`auditoria/2026-10-05-informe-auditoria.md`).
  - Versiones, precios, límites de proveedores y normativa verificados con fuentes citadas.
  - Medido el camino de aceptación (REST/JSON frente a binario, keep-alive, lote) y la cola en PostgreSQL.
  - Documentos 01–10, README y `AGENTS.md` reescritos con las correcciones (marcadas **(rev. 2026-10)**).
  - Documento nuevo `11-correo-masivo.md`.
  - ADR nuevos 0008–0017 (estado `Proposed`).
  - ADR 0002–0007: solo cambia su línea de *Estado* ("en proceso de supersesión").
- **Decisiones propuestas** (detalle en `auditoria/resumen-de-decisiones.md`):
  - RLS desde el MVP con FK compuestas (ADR-0008, supersede 0007).
  - Stack ratificado con justificación medible (ADR-0009, supersede 0006).
  - Cola con *fencing*, prioridad e idempotencia hacia el proveedor; semántica al menos una vez (ADR-0010, supersede 0002).
  - Plantillas endurecidas (ADR-0011, supersede 0005).
  - Supresión global/tenant (ADR-0012).
  - Correo masivo fuera por ahora, con disparador (ADR-0013).
  - REST + keep-alive + lote (ADR-0014).
  - Adjuntos acotados en el MVP (ADR-0015).
  - Resend ratificado con datos verificados (ADR-0016, supersede 0003).
  - API keys con ámbitos y CIDR (ADR-0017, supersede 0004).
- **Requisitos:**
  - Nuevos: FR-29 a FR-36 y FR-40 a FR-54 (estos últimos, Won't, en `11`); NFR-19 a NFR-21.
  - FR-28 pasa a DEPRECATED (dividido en FR-30/31/32).
  - FR-23 sube de Should a Must.
  - Ningún ID renumerado.
- **Cambios de enums antes de la primera migración** (no hay datos):
  - `email_event.type`: `BOUNCED_HARD` → `BOUNCED`, `BOUNCED_SOFT` → `DELIVERY_DELAYED`; nuevos `SENT`, `SUPPRESSED`, `OTHER`.
  - `failure_code`: `TENANT_SUSPENDED` y `TEMPLATE_NOT_PUBLISHED` pasan a DEPRECATED; nuevos `PROVIDER_FAILED`, `PROVIDER_SUPPRESSED`, `PROVIDER_IDEMPOTENCY_CONFLICT`, `RETRY_WINDOW_EXCEEDED` y `LOCK_EXPIRED`.
  - Tabla `scheduled_lock` eliminada del diseño.
- **Contrato con los productos:** cambios y notas de migración en `07` §12.1. Como no hay ninguna versión en producción, se aplican sobre `/v1` (NFR-18).
- **Pendiente:**
  - H0: cerrado en la entrada de la tarde (arriba).

## 2026-09-20 — Documentación base

- **Hecho:** `/docs` completa (visión y alcance, requisitos funcionales con criterios de aceptación, no funcionales, stack, arquitectura, modelo de datos, API interna, seguridad, roadmap, guía para agentes de IA) y ADR-0001 a ADR-0007.
- **Decisiones cerradas:**
  - un solo artefacto desplegable;
  - cola en PostgreSQL con SKIP LOCKED;
  - Resend como proveedor del MVP detrás de `EmailSender`;
  - secretos fuera de la base de datos;
  - plantillas versionadas inmutables y logic-less;
  - Java 25 + Spring Boot 4.1 + PostgreSQL 18;
  - multi-tenancy por columna `tenant_id`.
- **Pendiente:** iniciar la Fase 0 (hito H1: esqueleto + Docker Compose + CI).
