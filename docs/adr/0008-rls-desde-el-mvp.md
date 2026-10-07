# ADR-0008 — Row Level Security desde el MVP y claves foráneas compuestas por tenant

- **Estado:** Accepted (owner, 2026-10-05); criterio de revisión de coste aclarado por ADR-0019 (se mide sobre la transacción completa)
- **Fecha:** 2026-10-05
- **Autor:** agente: Claude (auditoría de documentación)
- **Supersede:** ADR-0007
- **Requisitos relacionados:** NFR-08, FR-01, FR-29, `08-seguridad.md` §5, `06-modelo-de-datos.md` §1 y §5

## Contexto

ADR-0007 eligió la columna `tenant_id` en una base compartida (decisión que se mantiene) y aplazó RLS a la fase 2 con este argumento: "añade complejidad de sesión/roles desde el día 1". La auditoría del 2026-10-05 encontró que:

1. Tus otros proyectos exigen RLS desde el MVP. Este servicio guarda datos personales (destinatarios, variables con enlaces de activación y recuperación, eventos) de **cuatro** productos distintos, incluido uno de pagos (Payverica). Tiene más motivos que los demás para la segunda barrera, no menos.
2. Sin RLS, el aislamiento depende de que **cada** consulta lleve `tenant_id`. Un repositorio nuevo, una consulta de listado escrita deprisa o un asistente de IA que "simplifica" una consulta bastan para filtrar datos entre productos, y el test por endpoint solo lo detecta si cubre ese camino concreto.
3. La propia base de datos no garantiza invariantes entre tenants: `email_message.template_version_id` referencia `template_version(id)` sin `tenant_id`, y `template_version.tenant_id` está "desnormalizado" sin ninguna restricción que lo ate a `template.tenant_id`.
4. El costo real de RLS es pequeño y conocido:
   - un `set_config('app.tenant_id', $1, true)` al inicio de cada transacción (el tercer argumento `true` lo limita a la transacción, así que un pool de conexiones no puede filtrarlo a otra petición);
   - una política por tabla;
   - separar el rol que migra del rol que ejecuta.

## Decisión

1. **RLS activa desde la migración V2**, con `ENABLE` + `FORCE ROW LEVEL SECURITY` en todas las tablas con `tenant_id`: `api_key`, `template`, `template_version`, `email_message`, `email_attachment`, `email_event`, `suppression`, `rate_limit_counter` y `audit_log`.
2. **Política *fail-closed*:** `tenant_id = current_setting('app.tenant_id', true)::uuid`. Si la variable no está fijada, `current_setting(..., true)` devuelve `NULL`, la comparación no es verdadera y no se ve ni se escribe ninguna fila.
3. **Tres roles de base de datos:**
   - `email_owner`: dueño del esquema; solo lo usa el paso de migración `APP_ROLE=migrate`.
   - `email_app`: peticiones de tenant; sujeto a RLS; sin DDL.
   - `email_system`: `BYPASSRLS`; sin DDL; lo usan el worker (toma de la cola entre tenants), los barridos, la purga, la correlación de webhooks (que no conocen el tenant hasta encontrar el mensaje), el lookup de API key por prefijo, la administración y las supresiones globales.
4. **Dos `DataSource`:** `tenantDataSource` (`email_app`) y `systemDataSource` (`email_system`). Un test de arquitectura (ArchUnit) prohíbe usar `systemDataSource` fuera de `sending.worker`, `events`, `tenancy.auth`, `tenancy.admin`, `abuse` y `maintenance`.
5. **Claves foráneas compuestas** `(tenant_id, id)` en `template`, `template_version`, `email_message` y `email_attachment`, para que la BD rechace referencias cruzadas entre tenants.
6. **Tests obligatorios** (NFR-08): aislamiento por endpoint; 0 filas sin `app.tenant_id`; violación de FK con una versión de plantilla ajena.

## Alternativas consideradas

| Alternativa | A favor | En contra | Motivo de descarte |
|---|---|---|---|
| Mantener ADR-0007 (RLS en fase 2) | Menos piezas el día 1 | Un solo error de código expone datos de varios productos; inconsistente con el estándar de tus otros proyectos | El argumento de complejidad no compensa el riesgo para datos personales de 4 productos |
| RLS con `SET ROLE` por tenant (un rol de BD por tenant) | Aislamiento por privilegios | N roles que crear y migrar; alta de tenant = operación de BD | Desproporcionado; la variable de sesión basta |
| Esquema por tenant | Aislamiento intermedio | Migraciones multiplicadas; la cola entre tenants se complica | Igual que en ADR-0007 |
| Solo FK compuestas, sin RLS | Invariantes de escritura garantizados | No protege las lecturas | Insuficiente sola; se adopta junto con RLS |

## Consecuencias

- **Positivas:**
  - un error de código en una ruta de tenant devuelve 0 filas en vez de datos ajenos;
  - la BD impide referencias cruzadas;
  - el modelo ya está listo si algún día hay terceros;
  - coherente con tus otros proyectos.
- **Negativas / trade-offs aceptados:**
  - dos `DataSource` y dos pools (el de sistema es pequeño, 2–4 conexiones);
  - el rol `email_system` es un punto de atención: confinado por test de arquitectura y revisión en PR;
  - las migraciones pasan a un paso de despliegue propio (`APP_ROLE=migrate`);
  - crear `email_system` con `BYPASSRLS` requiere un rol con privilegios en el PostgreSQL gestionado (a confirmar con el proveedor de hosting; la alternativa es una política explícita `TO email_system USING (true)`);
  - coste de rendimiento de RLS con un predicado de igualdad sobre una columna indexada: despreciable a este volumen (a medir en H2).

## Criterio de revisión

Un incidente en el que `email_system` se use fuera de su ámbito; que el PostgreSQL gestionado elegido no permita `BYPASSRLS` ni políticas por rol; o que la latencia de las consultas de tenant aumente más de un 20 % por RLS (medido en H2).
