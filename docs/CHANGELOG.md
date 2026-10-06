# Changelog del proyecto

Registro de avance y decisiones. Formato: hecho / pendiente / decisiones.

## 2026-10-06 — H1: esquema, roles y RLS

- **Decisiones (owner, 2026-10-06):**
  - `BYPASSRLS` de `email_system` se concede en `scripts/init-db-roles.sql` (requiere superusuario), no en `V2__rls.sql`. Si el PostgreSQL gestionado no lo permite, se usará la alternativa de ADR-0008 (política `TO email_system USING (true)`).
  - `tenant` pasa a tener RLS con `FORCE` (política `id = app.tenant_id`). `email_app` solo tiene `SELECT` sobre su propia fila; el alta, la modificación y el borrado de tenants solo se hacen con `email_system`.
  - `email_system` tiene `SELECT/INSERT/UPDATE/DELETE` sobre todas las tablas, salvo `UPDATE`/`DELETE` en `audit_log`.
  - En `suppression`, `email_app` no puede leer `source_tenant_id` ni `source_message_id` (permisos por columna): un tenant no sabe qué otro tenant originó una supresión global.
  - Las políticas usan `nullif(current_setting('app.tenant_id', true), '')::uuid`: en una conexión reutilizada, tras una transacción con `set_config(..., true)`, la variable vale `''` y no `NULL`, y `''::uuid` daría error en lugar de 0 filas.
- **Hecho:** `docs/06` §1 y §5 actualizados con lo anterior.

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
