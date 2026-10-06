# ADR-0007 — Multi-tenancy por columna `tenant_id` en una base compartida

- **Estado:** Superseded by ADR-0008 (2026-10-05)
- **Fecha:** 2026-09-20
- **Requisitos relacionados:** NFR-08, FR-01, `08-seguridad.md` §5

## Contexto
Varios productos propios usan el servicio simultáneamente y sus datos deben estar aislados. Las opciones clásicas son: base de datos por tenant, esquema por tenant o columna discriminadora.

## Decisión
Una base de datos compartida con **columna `tenant_id`** en todas las tablas de negocio.

- El tenant se deriva **exclusivamente** de la API key autenticada; un `tenantId` en el cuerpo o en la URL se ignora o se rechaza.
- Todos los repositorios exigen `tenantId` en su firma; no existen consultas "globales" fuera de administración y mantenimiento.
- Los identificadores son UUID; el acceso a un recurso de otro tenant devuelve `404`, no `403`.
- Test obligatorio de aislamiento por cada endpoint `/v1/**`.
- Row Level Security en PostgreSQL queda como segunda barrera para la fase 2.

## Alternativas consideradas
| Alternativa | A favor | En contra | Motivo de descarte |
|---|---|---|---|
| Base de datos por tenant | Aislamiento fuerte | N migraciones, N pools, N respaldos; añadir un producto se vuelve una operación | Desproporcionado para productos propios |
| Esquema por tenant | Aislamiento intermedio | Migraciones multiplicadas; consultas agregadas incómodas | Descartada |
| RLS desde el MVP | Barrera a nivel de motor | Añade complejidad de sesión/roles desde el día 1 | Se pospone a fase 2, con los tests como red mientras tanto |

## Consecuencias
- Positivas: una migración, un pool, un respaldo; alta de un producto = una fila; métricas agregadas triviales.
- Negativas: el aislamiento depende de la disciplina del código (mitigado con repositorios que exigen `tenantId`, tests por endpoint y, más adelante, RLS).

## Criterio de revisión
Que el servicio deje de usarse solo con productos propios (clientes externos, requisitos de cumplimiento o residencia de datos) o un incidente real de fuga entre tenants.

---
> **Nota de estado (2026-10-05):** de este ADR solo se ha modificado la línea de *Estado*; su texto aceptado no se edita. El owner aceptó ADR-0008, que lo sustituye, el 2026-10-05.
