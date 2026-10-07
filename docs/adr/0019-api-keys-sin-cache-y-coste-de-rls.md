# ADR-0019 — API keys sin caché (revocación inmediata) y criterio de coste de RLS por transacción

- **Estado:** Accepted (owner, 2026-10-06)
- **Fecha:** 2026-10-06
- **Autor:** agente: Claude (H2)
- **Supersede:** ADR-0017, solo en el punto "caché ≤ 60 s"; aclara el criterio de revisión de ADR-0008
- **Requisitos relacionados:** FR-01, FR-03 (AC-03.4), NFR-08, `08-seguridad.md` §2

## Contexto

1. ADR-0017 mantenía de ADR-0004 una caché en memoria de las API keys validadas con TTL ≤ 60 s, para no consultar la base de datos en cada petición. Su precio es que una key revocada sigue autenticando hasta 60 s, justo cuando más importa cortarla: ante una filtración (`08` §3).
2. En H2 la autenticación se implementó sin caché: un lookup por `key_prefix` (índice `UNIQUE`) por petición, a través de `email_system`. A < 100 000 correos/mes el coste es despreciable.
3. ADR-0008 fija como criterio de revisión que "la latencia de las consultas de tenant aumente más de un 20 % por RLS (medido en H2)", sin decir sobre qué se mide. Medido en H2 con `./gradlew benchmarkRls`:
   - PostgreSQL 18 en Docker local, 100 000 mensajes en 20 tenants, 5 000 transacciones por caso, dos ejecuciones;
   - p50 por transacción, en µs.

   | Consulta | `email_app` + `set_config` + RLS | `email_system` + `set_config` | `email_system` solo consulta |
   |---|---|---|---|
   | Últimos 20 del tenant | 144–158 | 133–137 | 91–92 |
   | Por tenant + id | 127–128 | 119–122 | 82–91 |

   - El plan sigue usando `ix_msg_tenant_created`; la política queda como un único `One-Time Filter`.
   - El ida y vuelta de `set_config` cuesta ~30–45 µs y la evaluación de la política, +5–15 % en p50.
   - En total, +40–65 µs por transacción: más del 20 % sobre una consulta aislada, pero un 3–5 % del camino de aceptación (1,38 ms p50, `04` §2).

## Decisión

1. **Sin caché de API keys.** Cada petición `/v1` consulta la key por su prefijo. La revocación es efectiva en la siguiente petición (AC-03.4 revisado).
2. **El criterio de coste de RLS de ADR-0008 se mide sobre la transacción completa de la petición**, no sobre una consulta aislada. Con los números de arriba (3–5 %) el criterio **no** está activado. No se optimiza el ida y vuelta de `set_config`.
3. La medición se repite con `./gradlew benchmarkRls` (no forma parte de `./gradlew test`). El informe queda en `build/reports/rls-benchmark.txt`.

## Alternativas consideradas

| Alternativa | A favor | En contra | Motivo de descarte |
|---|---|---|---|
| Caché con TTL ≤ 60 s (ADR-0017) | Una lectura menos por petición | Hasta 60 s de una key revocada aún válida; invalidación entre instancias | El ahorro es despreciable a este volumen y la revocación inmediata vale más |
| Caché con invalidación activa | Revocación inmediata y sin lectura | Requiere un canal entre instancias (LISTEN/NOTIFY o similar) | Complejidad sin beneficio medible |
| Medir el criterio de RLS sobre la consulta aislada | Más sensible | Se dispara por el ida y vuelta de `set_config`, que no es coste de RLS sino de red | No representa lo que paga una petición |
| Enviar `set_config` en el mismo viaje que la primera consulta | −30–45 µs por transacción | Más código en el acceso a datos; JDBC no lo permite de forma limpia con parámetros | No compensa un 3–5 % |

## Consecuencias

- **Positivas:** la revocación es inmediata; no hay estado de autenticación en memoria que invalidar entre instancias; el criterio de RLS es claro y repetible.
- **Negativas / trade-offs aceptados:** una lectura indexada más por petición `/v1` (≈ 0,1 ms en local).

## Criterio de revisión

- La autenticación supera 1 ms p95 en producción, o la base de datos se satura por los lookups de keys: valorar una caché con invalidación activa.
- `./gradlew benchmarkRls`, medido sobre la transacción completa de la petición, muestra más de un 20 % de coste atribuible a RLS.
