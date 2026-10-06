# ADR-0010 — Cola en PostgreSQL con *fencing*, prioridad e idempotencia hacia el proveedor

- **Estado:** Accepted (owner, 2026-10-05)
- **Fecha:** 2026-10-05
- **Autor:** agente: Claude (auditoría de documentación)
- **Supersede:** ADR-0002
- **Requisitos relacionados:** FR-11, FR-12, FR-13, FR-36, NFR-03, NFR-04, NFR-06, NFR-19, NFR-21

## Contexto

ADR-0002 decidió que la tabla `email_message` es la cola (`FOR UPDATE SKIP LOCKED`) y que un barrido devuelve a `QUEUED` los mensajes con lock vencido. La decisión de fondo es correcta y se **mantiene**. Medido el 2026-10-05: la toma con `LIMIT 4` sobre 12 800 mensajes en cola tarda entre 2,4 y 4,8 ms en PostgreSQL 16 local; una transacción de aceptación completa, 1,38 ms p50.

Pero la auditoría encontró cinco defectos que hacían falsa la promesa de NFR-04 ("exactamente una vez"):

1. Resend ofrece idempotencia nativa (`Idempotency-Key`, 24 h, `409` si el cuerpo cambia; [documentación](https://resend.com/docs/dashboard/emails/idempotency-keys)) y el diseño no la usaba ("cuando la ofrezca").
2. El duplicado real (el proveedor acepta, la respuesta se pierde) no deja `provider_message_id`, así que la salvaguarda "si hay `provider_message_id` no se reenvía" no lo cubre.
3. El barrido no incrementaba `attempts`: un mensaje que tumba al worker se reintenta indefinidamente.
4. No había *fencing*: un worker lento podía cerrar como `SENT` un mensaje que el barrido ya había reasignado a otro, y ambos lo enviaban.
5. El lote de 25 compartía un único vencimiento de lock, pero se procesaba de 4 en 4; además no estaba prohibido llamar al proveedor dentro de la transacción de toma.

Además no había prioridad: un aviso masivo (`NOTICE`) podía retrasar una recuperación de contraseña. El criterio de revisión "> 50 msg/s" equivalía a ~130 M de correos/mes y nunca se dispararía.

## Decisión

1. **La tabla `email_message` sigue siendo la cola.** Sin broker.
2. **Toma corta y sin red:**
   - una sola sentencia (CTE `SELECT … FOR UPDATE SKIP LOCKED` + `UPDATE … RETURNING`) en autocommit;
   - **ninguna** llamada HTTP con locks de fila abiertos;
   - se toman como máximo tantos mensajes como huecos libres tenga la concurrencia del worker, en lugar de un lote fijo.
3. **Intentos al tomar:** `attempts = attempts + 1` y `first_attempt_at = coalesce(first_attempt_at, now())` en la propia toma. El barrido devuelve a `QUEUED` (o a `FAILED`/`MAX_ATTEMPTS_EXCEEDED` si se agotaron) y registra `LOCK_EXPIRED` en `attempt_log`.
4. ***Fencing token*:**
   - cada toma genera un `lock_token` (uuid);
   - todo cierre (`SENT`, `FAILED`, vuelta a `QUEUED`) se hace con `WHERE id = :id AND status = 'SENDING' AND lock_token = :token`;
   - si afecta 0 filas, el worker no hace nada más que registrar la métrica `email_lost_lock_total`.
5. **Idempotencia hacia el proveedor:**
   - `Idempotency-Key = email_message.id` en todos los intentos;
   - el cuerpo enviado es determinista (versión fijada, variables fijas, adjuntos ordenados, sin helpers de "fecha actual");
   - un `409` del proveedor por clave reutilizada se clasifica como permanente (`PROVIDER_IDEMPOTENCY_CONFLICT`) y genera una alerta.
6. **Ventana:**
   - todos los intentos de un mensaje ocurren en < 23 h desde `first_attempt_at`;
   - la app no arranca si la configuración de backoff + locks puede superar esa ventana;
   - pasada la ventana, `FAILED`/`RETRY_WINDOW_EXCEEDED`.
7. **Lock por mensaje:** `LOCK_TIMEOUT_SECONDS` = 60 (> 3 s de conexión + 10 s de lectura + margen).
8. **Prioridad:**
   - columna `priority` (0 `SECURITY`, 1 `TRANSACTIONAL`, 2 `NOTICE`) heredada de la categoría de la plantilla;
   - índice parcial `(priority, next_attempt_at) WHERE status = 'QUEUED'`;
   - la toma ordena por `priority, next_attempt_at`.
9. **Tenants retenidos:** la toma excluye tenants `SUSPENDED` o en pausa (join con `tenant`); sus mensajes siguen en `QUEUED`.
10. **Límite hacia el proveedor:**
    - limitador de tasa en memoria por instancia de worker, `WORKER_PROVIDER_RPS` (default 8);
    - la suma de todas las instancias ≤ 80 % del límite de la cuenta (Resend: 10 rps por equipo por defecto);
    - con 1 worker (el default) no hace falta coordinación.
11. **Circuit breaker** hecho a mano: 5 fallos transitorios seguidos lo abren durante 60 s, tras los cuales se prueba un único envío. Mientras está abierto no se toman mensajes.
12. **Tareas programadas** (barrido, purga, métricas, anomalías) coordinadas entre instancias con `pg_try_advisory_xact_lock`; se elimina la tabla `scheduled_lock`.
13. **Semántica declarada:** al menos una vez, con deduplicación en el proveedor dentro de 24 h (NFR-04).

## Alternativas consideradas

| Alternativa | A favor | En contra | Motivo de descarte |
|---|---|---|---|
| Mantener ADR-0002 tal cual | Nada que cambiar | Duplicados posibles, bucles infinitos y ausencia de prioridad, todo demostrable | Incumple NFR-04 |
| Llamar al proveedor dentro de la transacción de toma | El lock "protege" el envío | Locks y conexiones de BD retenidos hasta 10 s; un timeout aborta la transacción y aun así el correo puede haber salido | Peor en todo |
| Librería de colas (JobRunr, db-scheduler) | Reintentos y barridos ya hechos | Segunda tabla de trabajos separada del registro de envío (vuelve la doble escritura) o adaptar el dominio a la librería; dependencia más | ~300 líneas propias, explícitas y probadas cuestan menos que acoplarse |
| Broker (SQS, RabbitMQ) | Prioridades y DLQ nativas | Doble escritura y otro componente; el volumen no lo justifica | Igual que en ADR-0002 |
| LISTEN/NOTIFY para despertar al worker | Latencia < 1 s | Más código y casos borde con el pool | NFR-19 se cumple con sondeo de 1 s; se reconsidera si no |

## Consecuencias

- **Positivas:**
  - no se pierden mensajes ni se duplican dentro de las 24 h de idempotencia del proveedor;
  - los mensajes "veneno" terminan en `FAILED`;
  - la prioridad protege los correos críticos;
  - la cola sigue inspeccionable con SQL.
- **Negativas / trade-offs aceptados:**
  - dependemos de que el proveedor ofrezca idempotencia (si se cambia a uno que no la tenga, vuelve la ventana de duplicado por timeout, y se advierte al arrancar);
  - el render debe ser determinista (restricción sobre los helpers);
  - con N workers hay que repartir manualmente el límite de tasa.

## Criterio de revisión

Cualquiera de estas señales:
- p95 de la consulta de toma > 50 ms durante 3 días;
- antigüedad de cola > 60 s con el proveedor sano durante 3 días;
- volumen > 3 M de correos/mes;
- necesidad de fan-out a varios consumidores independientes;
- `email_lost_lock_total` o `PROVIDER_IDEMPOTENCY_CONFLICT` > 0 de forma recurrente.

Candidato preferente en ese caso: SQS con patrón outbox, manteniendo `email_message` como registro de verdad.
