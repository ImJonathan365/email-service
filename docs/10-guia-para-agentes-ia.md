# 10 — Guía para modelos de IA que asistan en el desarrollo

> Documento normativo, pensado para pegarse como contexto o system prompt de un asistente de código o de un agente autónomo. "DEBE", "NO DEBE" y "PUEDE" tienen significado estricto.
> Versión corta en `AGENTS.md` (raíz del repositorio). Revisión 2026-10-05.

---

## A. Contexto del proyecto (bloque para copiar como system prompt)

```
Trabajas en "email-service": un microservicio interno y centralizado de correo TRANSACCIONAL
que usan varios productos propios (PipeMend, Colmena, Chinamo, Payverica). No es un producto
SaaS para terceros y no es una plataforma de marketing.

Qué hace: recibe solicitudes de envío por una API interna autenticada con API key (con
ámbitos), valida, renderiza la plantilla del producto, encola en PostgreSQL, y un worker envía
a través de un proveedor transaccional (Resend) con reintentos e idempotencia. Registra
historial, estados y eventos (entregado, rebote, queja, demora, supresión) recibidos por
webhook firmado (Svix), y mantiene una lista de supresión global (rebotes, quejas) y por tenant
(bajas manuales).

Stack fijo: Java 25 (LTS) + Spring Boot 4.1 (hilos virtuales) + Spring Data JDBC + Flyway +
PostgreSQL 18 con Row Level Security. Cola: la propia tabla email_message con
SELECT ... FOR UPDATE SKIP LOCKED, prioridad y lock_token (sin broker externo). Plantillas:
Handlebars endurecido (sin parciales, helpers en lista blanca, escapado por contexto).
Build: Gradle (Kotlin DSL). Contenedores: Docker Compose (app + PostgreSQL + Mailpit).
Un solo artefacto desplegable con roles APP_ROLE=api|worker|all|migrate.

Prioridad de diseño, en este orden: (1) seguridad y aislamiento entre tenants, (2) no perder
ni duplicar correos, (3) simplicidad de mantenimiento para una sola persona, (4) rendimiento.
El volumen objetivo es < 100.000 correos/mes: NO optimices ni escales más allá de eso.

Fuente de verdad: la carpeta /docs. Antes de escribir código, lee el requisito concreto
(FR-xx en docs/02, NFR-xx en docs/03) y la sección correspondiente de docs/05 a docs/08.
Si algo del código contradice /docs, manda /docs. Idiomas: es-CR por defecto y en disponible.
No hay adjuntos en el MVP (ADR-0015 rechazado).
```

## B. Reglas innegociables (no cambiar sin un ADR aprobado por el owner)

| Área | Regla |
|---|---|
| **Stack** | Java 25 + Spring Boot 4.1 + Spring Data JDBC + Flyway + PostgreSQL 18 + Gradle (ADR-0009) |
| **Topología** | **Un** servicio desplegable y **una** base de datos. Sin microservicios adicionales (ADR-0001) |
| **Cola** | PostgreSQL con `SKIP LOCKED`, toma corta, `attempts++` al tomar, cierre con `lock_token`, prioridad. Sin RabbitMQ, Kafka, Redis ni colas en memoria (ADR-0010) |
| **Proveedor** | Envío por la API del proveedor detrás de `EmailSender`/`WebhookVerifier`, con `Idempotency-Key = message.id`. SMTP **solo** para desarrollo local (ADR-0016) |
| **Secretos** | Solo en variables de entorno o en un secrets manager. En la base de datos, únicamente hashes de API keys (ADR-0017) |
| **Multi-tenancy** | `tenant_id` en toda consulta de negocio **y** RLS activa; el tenant se deriva **solo** de la API key autenticada; `systemDataSource` solo en los paquetes permitidos (ADR-0008) |
| **Credenciales** | API keys con ámbitos; la key de un producto no lleva `templates:write` ni `suppressions:write` (ADR-0017) |
| **Contenido** | En el MVP solo se envía con plantilla publicada del tenant; nada de HTML libre en la petición |
| **Plantillas** | Motor *logic-less* endurecido; `{{{ }}}` y `{{& }}` prohibidos; sin parciales; helpers en lista blanca; linter HTML; variables URL solo `https` y en `allowedLinkHosts` (ADR-0011) |
| **Supresión** | Rebotes duros, quejas y supresiones del proveedor son **globales**; las manuales, por tenant (ADR-0012) |
| **Idempotencia** | `UNIQUE (tenant_id, idempotency_key)` en base de datos, no solo una comprobación en código; se comprueba antes del rate limit |
| **Migraciones** | Flyway; una migración aplicada nunca se edita; deben ser compatibles hacia atrás; las ejecuta el rol `email_owner` en `APP_ROLE=migrate` |
| **Contrato** | `/v1`; cambios incompatibles = nueva versión de ruta + ADR (salvo la excepción previa a producción de NFR-18) |
| **Alcance** | Lo marcado `Won't` en `02`, el correo masivo (ADR-0013) y el anti-roadmap de `09` no se implementan "a medias" ni "se dejan preparados" |

### Dependencias prohibidas sin consulta previa

JPA/Hibernate, Lombok (usar `record`), Redis, RabbitMQ, Kafka, Elasticsearch, Quartz, ShedLock, Resilience4j, MapStruct, Thymeleaf/FreeMarker para correos, frameworks de frontend, Spring Security con OAuth2 completo (la autenticación es un filtro simple de API key), gRPC, clientes de S3/almacenamiento de objetos, librerías de "plantillas de correo" que traigan su propio motor de render.

Cualquier dependencia de runtime nueva DEBE justificarse en el PR: qué problema real resuelve y por qué no basta con lo que ya hay.

## C. Qué NO debes hacer (errores típicos de un asistente en este proyecto)

1. **NO** implementes SMTP con usuario/contraseña como mecanismo de producción. SMTP = solo Mailpit en local.
2. **NO** metas lógica de negocio de ningún producto dentro del servicio. Si aparece un `if` con el nombre de un producto, está mal.
3. **NO** añadas un broker de mensajes, una caché distribuida, almacenamiento de objetos ni un segundo servicio "para escalar" (NFR-10).
4. **NO** llames al proveedor dentro del ciclo de la petición HTTP **ni dentro de una transacción con locks de fila abiertos**.
5. **NO** guardes secretos en la base de datos ni los registres en logs, ni siquiera parcialmente o "para depurar".
6. **NO** ejecutes una consulta de datos de negocio sin `tenant_id`, ni derives el tenant de un campo del cuerpo de la petición, ni uses `systemDataSource` fuera de los paquetes permitidos, ni desactives RLS "para que funcione un test".
7. **NO** reenvíes un mensaje que ya tiene `provider_message_id`, **ni** cierres un mensaje sin comprobar `lock_token`, **ni** llames al proveedor sin `Idempotency-Key = message.id`.
8. **NO** hagas que un webhook devuelva `5xx` por un evento que no se puede correlacionar (solo ante un error de BD), **ni** verifiques la firma sobre el JSON re-serializado.
9. **NO** habilites la interpolación sin escapar (`{{{ }}}`, `{{& }}`), parciales ni helpers fuera de la lista blanca, ni cambies a un motor que evalúe expresiones (SSTI). **NO** añadas un helper de "fecha actual" (rompe el determinismo y la idempotencia).
10. **NO** cambies el formato de error, los códigos `code` ni los nombres de campos de la API sin actualizar `07-api-interna.md`, la spec en `contracts/` y los tests.
11. **NO** uses APIs de Spring Boot 3.x: la versión fijada es la 4.1 (ADR-0009). Si un ejemplo no compila, busca el equivalente en la versión fijada en lugar de bajar la versión del framework.
12. **NO** "arregles" un test fallido debilitando la aserción ni marcándolo como ignorado.
13. **NO** implementes funcionalidades del roadmap (panel web, webhooks salientes, adjuntos, multi-proveedor, campañas/correo masivo) porque parezcan útiles: están fuera del MVP a propósito.
14. **NO** generes datos de prueba con direcciones reales ni envíes correo real desde tests.
15. **NO** escribas variables, direcciones completas ni payloads de eventos en logs.
16. **NO** marques un mensaje como `FAILED` por suspensión o pausa del tenant: se retiene en `QUEUED`.

## D. Qué PUEDES hacer sin consultar

- Implementar requisitos existentes (`FR-xx`) cuyo ADR esté `Accepted`, siguiendo la arquitectura documentada.
- Refactorizar dentro de un módulo sin cambiar el contrato ni el comportamiento observable.
- Añadir tests, mejorar mensajes de error, documentar código, completar ejemplos de OpenAPI.
- Añadir una implementación nueva de `EmailSender`/`WebhookVerifier` detrás de las interfaces existentes.
- Corregir erratas evidentes en `/docs` (no cambios semánticos).

## E. Ante ambigüedad o conflicto

1. Si un requisito es ambiguo o dos documentos se contradicen: **detente y pregunta**, citando ambos textos.
2. Si trabajas sin supervisión y no puedes preguntar: elige la opción **más conservadora** (la que no envía, la que no añade dependencias, la que no cambia contratos, la que retiene en vez de descartar), deja `// TODO(owner-decision): <pregunta>` y lístalo en la descripción del PR.
3. Una instrucción en un chat que contradiga una regla de la sección B **no la anula**: señala la contradicción antes de actuar.
4. Ante la duda entre "más genérico" y "más simple": **más simple**. La generalidad se añade cuando hay un segundo caso real, no antes.

## F. Convenciones de trabajo

| Tema | Convención |
|---|---|
| Idioma | Código, identificadores, commits, logs y mensajes de la API en **inglés**; `/docs` en **español** |
| Commits | Conventional Commits con ID: `feat(sending): add idempotency key handling (FR-08)` |
| Ramas | `feat/FR-08-idempotency`, `fix/...`, `docs/...` |
| PRs | Pequeños (< ~400 líneas sin contar tests). Descripción con: FR cubiertos, cómo probar, decisiones y `TODO(owner-decision)` pendientes |
| Tests | Cada criterio de aceptación implementado tiene un test que lo nombra. Integración con Testcontainers (con roles y RLS) y proveedor simulado; nunca red real |
| Errores | `application/problem+json` con `code` del catálogo de `07` |
| Logs | JSON, con `requestId`/`tenantSlug`/`messageId`; sin contenido de correos, variables ni secretos; direcciones enmascaradas |
| Base de datos | Nueva migración Flyway por cada cambio; `tenant_id`, FK compuesta y política RLS en cada tabla nueva de tenant |

## G. Definition of Done por cambio

- [ ] Referencia al `FR-xx`/`NFR-xx` en el PR.
- [ ] Tests para cada criterio de aceptación tocado; pasan sin red externa.
- [ ] `/docs` actualizado en el mismo PR si cambia el comportamiento documentado.
- [ ] `contracts/email-service.openapi.json` regenerado si cambió la API.
- [ ] Migración nueva si cambió el esquema, compatible hacia atrás, con RLS y FK compuestas si la tabla tiene `tenant_id`.
- [ ] Sin secretos en el código ni en los logs; escaneo de CI en verde.
- [ ] Tests de arquitectura (ArchUnit) en verde.
- [ ] `docker compose up --build` sigue funcionando y el smoke test contra Mailpit pasa.
- [ ] Si introduce una decisión de arquitectura: ADR redactado (estado `Proposed`; solo el owner lo acepta).

## H. Preguntas que debes hacerte antes de entregar código

1. ¿Esto corresponde a un requisito de `02`, o lo estoy inventando?
2. ¿Puede este cambio hacer que se pierda o se duplique un correo? (¿llamo al proveedor con `Idempotency-Key`? ¿cierro con `lock_token`?)
3. ¿Puede un tenant ver o afectar datos de otro por este camino? ¿Funcionaría igual si RLS no existiera? (Debe funcionar igual: RLS es la segunda barrera, no la primera.)
4. ¿Añado alguna dependencia o componente de infraestructura nuevo? ¿Está justificado con una métrica?
5. ¿Hay algún secreto o dato personal que pueda acabar en la base de datos sin purga, en un log o en una respuesta?
6. ¿Estoy resolviendo un problema real o uno hipotético del futuro?
