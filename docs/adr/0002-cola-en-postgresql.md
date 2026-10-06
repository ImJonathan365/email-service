# ADR-0002 — PostgreSQL como cola de trabajos (sin broker externo)

- **Estado:** Superseded by ADR-0010 (2026-10-05)
- **Fecha:** 2026-09-20
- **Requisitos relacionados:** FR-11, FR-12, FR-13, NFR-03, NFR-06, NFR-10

## Contexto
El envío debe ser asíncrono, con reintentos e idempotencia. La opción "por defecto" del sector es añadir RabbitMQ, Redis o Kafka. Eso implica un componente más que instalar, asegurar, respaldar y vigilar, y abre el problema clásico de la doble escritura (guardar el mensaje en la base de datos y publicarlo en el broker sin transacción común).

## Decisión
La tabla `email_message` **es** la cola. El worker toma trabajo con:

```sql
SELECT id FROM email_message
WHERE status = 'QUEUED' AND next_attempt_at <= now()
ORDER BY next_attempt_at
FOR UPDATE SKIP LOCKED LIMIT :batch;
```

Los mensajes tomados se marcan `SENDING` con `lock_expires_at`; un barrido cada 60 s devuelve a `QUEUED` los que quedaron con lock vencido. El backoff se expresa con `next_attempt_at`.

## Alternativas consideradas
| Alternativa | A favor | En contra | Motivo de descarte |
|---|---|---|---|
| RabbitMQ | Colas, prioridades, DLQ maduras | Componente extra; doble escritura; estado de la cola opaco | Complejidad no justificada por el volumen |
| Redis (listas/streams) | Rápido y simple de arrancar | Durabilidad configurable y más débil; otro servicio que respaldar | La durabilidad es requisito duro (NFR-03) |
| Amazon SQS | Gestionado, sin operación | Ata a AWS; sigue habiendo doble escritura; peor depuración local | Se reserva como salida futura |
| Kafka | Escala masiva | Absurdo a esta escala | Descartada |

## Consecuencias
- Positivas: atomicidad real entre aceptar y encolar; estado inspeccionable con SQL; reintentos y prioridad temporal sin infraestructura extra; pruebas locales sin dependencias.
- Negativas: sondeo (polling) cada segundo en lugar de push; la carga de la cola compite con las consultas de la API (mitigado con índices parciales y, si hiciera falta, una réplica de lectura); no hay DLQ nativa (su equivalente es `status = 'FAILED'` con `failure_code`).

## Criterio de revisión
> 50 mensajes/segundo sostenidos, necesidad de prioridades estrictas entre tipos de mensaje, o fan-out a varios consumidores independientes. En ese caso, el candidato preferente es SQS (o RabbitMQ si se busca independencia de nube), manteniendo `email_message` como registro de verdad y usando el patrón outbox.

---
> **Nota de estado (2026-10-05):** de este ADR solo se ha modificado la línea de *Estado*; su texto aceptado no se edita. El owner aceptó ADR-0010, que lo sustituye, el 2026-10-05.
