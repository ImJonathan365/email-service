# ADR-0014 — Protocolo de entrada: REST/JSON sobre conexiones persistentes, más un endpoint de lote

- **Estado:** Accepted (owner, 2026-10-05)
- **Fecha:** 2026-10-05
- **Autor:** agente: Claude (auditoría de documentación)
- **Requisitos relacionados:** FR-07, FR-35, NFR-01, NFR-02, NFR-17, NFR-18

## Contexto

La API interna es REST/JSON sobre HTTPS. Se pidió valorar si otra tecnología reduce el tiempo de envío o es más liviana, **midiendo** en lugar de opinar. Volumen del primer año: < 100 000 correos/mes; escenario de estrés: 100 veces más (10 M/mes).

### Medición (2026-10-05)

Banco de pruebas: servidor que reproduce el camino de aceptación de `POST /v1/emails`:
1. parse JSON;
2. auth (SHA-256 + comparación en tiempo constante);
3. validación y hash canónico;
4. transacción PostgreSQL con 2 `UPSERT` de rate limit y un `INSERT … ON CONFLICT` idempotente sobre `email_message` con los 6 índices de `06`;
5. `COMMIT` con `fsync=on` y `synchronous_commit=on`;
6. respuesta `202`.

Detalles del entorno:
- Node 22 + `pg`, PostgreSQL 16, contenedor Linux de 2 vCPU, TLS 1.3 con certificado ECDSA P-256, cliente en la misma máquina (loopback);
- 1 500 peticiones secuenciales por modo, tras 300 de calentamiento.

El lenguaje del banco (Node) no es el del servicio (Java). Lo que se mide aquí es la **proporción** entre protocolo y base de datos, que no depende del lenguaje.

**Presupuesto de latencia por componente (servidor, p50):**

| Componente | Tiempo | % del servidor |
|---|---|---|
| Parse JSON del cuerpo (323 bytes) | 10,5 µs | 0,8 % |
| Autenticación (SHA-256 + `timingSafeEqual`) | 12,1 µs | 0,9 % |
| Validación + hash canónico | 11,5 µs | 0,8 % |
| **Base de datos** (2 UPSERT + INSERT + COMMIT) | **1 333 µs** | **96,6 %** |
| Serializar la respuesta | 9,9 µs | 0,7 % |
| **Total en servidor** | **1 380 µs** (p95 2 331 µs) | 100 % |

**Latencia observada por el cliente (loopback):**

| Modo | p50 | p95 | p99 |
|---|---|---|---|
| HTTPS/1.1, conexión TLS nueva por petición | 4,36 ms | 6,78 ms | 11,10 ms |
| HTTPS/1.1 keep-alive | **1,84 ms** | 3,01 ms | 5,87 ms |
| HTTP/2, una sesión | 2,05 ms | 3,84 ms | 6,13 ms |

Una conexión nueva cuesta ~2,5 ms de CPU de handshake en loopback, más 2 RTT de red reales (TCP + TLS 1.3). Entre regiones, eso suma decenas de ms.

**Throughput** (16 clientes concurrentes, pool de BD de 10, todo en 2 vCPU compartidas): HTTPS/1.1 keep-alive 634 req/s; HTTP/2 multiplexado 569 req/s.

**Formatos de cuerpo** (codificar + decodificar el payload de ejemplo, 200 000 iteraciones):

| Formato | Bytes | µs por ida y vuelta |
|---|---|---|
| JSON | 323 (256 con gzip) | 3,43 |
| Protobuf (protobufjs) | 221 | 4,81 |
| MessagePack (msgpackr) | 272 | 5,44 |
| CBOR (cbor-x) | 273 | 6,48 |

**Lote frente a unitario** (100 inserciones):
- 100 transacciones: 68–85 ms (0,68–0,85 ms/mensaje);
- 1 transacción: 22,6–35,6 ms (0,23–0,36 ms/mensaje).

**Lectura:**
- El protocolo y la serialización suman ~1,5 % del tiempo de servidor; todo lo que no es BD, ~3,4 %.
- Un formato binario ahorra 100 bytes y **no** ahorra CPU frente al JSON nativo.
- Lo que sí mueve la aguja: (1) no abrir una conexión TLS por petición (−58 % de latencia del cliente en loopback) y (2) agrupar inserciones en una transacción (~3× por mensaje).

### Dimensionamiento

| Escenario | Media | Pico supuesto | CPU de protocolo en el pico | Ahorro máximo de pasar a binario |
|---|---|---|---|---|
| Año 1: < 100 000/mes | 0,04 req/s | 20 req/s | 20 × ~20 µs ≈ 0,04 % de un núcleo | Inapreciable |
| ×100: 10 M/mes | 3,9 req/s | 200 req/s | ≈ 0,4 % de un núcleo | Inapreciable; el límite real es el commit de la BD y el proveedor (10 rps en Resend) |

## Decisión

1. **REST/JSON sobre HTTPS se queda** como única interfaz de entrada.
2. **Se añade:**
   - **conexiones persistentes obligatorias en los clientes** (HTTP/1.1 keep-alive o HTTP/2; documentado en `07` §1 y §12), con timeouts de cliente de 2 s (conexión) y 5 s (lectura);
   - **endpoint de lote** `POST /v1/emails/batch` (FR-35), hasta 100 elementos en una transacción;
   - el servidor acepta HTTP/2 y compresión gzip en peticiones de lote (opcional para el cliente);
   - nada más.
3. **No** se adopta gRPC, Connect, cuerpos binarios, HTTP/3, ingestión por cola ni SMTP de *submission*.

### Comparativa

| Opción | Ganancia de latencia medida o estimada | Liviandad | Depuración (curl/OpenAPI) | Compatibilidad con clientes | Infraestructura nueva | Coste para un mantenedor | Veredicto |
|---|---|---|---|---|---|---|---|
| REST/JSON HTTP/1.1 keep-alive (base) | — (1,84 ms p50) | Alta | Total | Total | Ninguna | Ninguno | **Se queda** |
| REST/JSON HTTP/2 | Ninguna con 1 petición a la vez (2,05 ms); útil para multiplexar lotes | Alta | Total (curl `--http2`) | Alta | Ninguna | Bajo | Se acepta en el servidor, opcional para el cliente |
| REST/JSON HTTP/3 (QUIC) | Solo con redes con pérdidas o móviles; no es el caso servidor-a-servidor | Media | Parcial | Baja en librerías JVM | Proxy con QUIC | Medio | No |
| gRPC (HTTP/2 + protobuf) | ≈ 0 (protobuf no es más rápido que JSON aquí; HTTP/2 ya disponible) | Media | Peor (grpcurl, sin Swagger) | Requiere stubs generados en cada producto | Ninguna, pero otro contrato (`.proto`) | Alto: dos contratos | No |
| Connect / gRPC-Web | Igual que gRPC | Media | Mejor que gRPC (JSON posible) | Media | Ninguna | Medio | No |
| Cuerpo binario (protobuf/MessagePack/CBOR) sobre HTTP | −100 bytes; +1,4–3 µs de CPU | Igual | Peor | Requiere librería | Ninguna | Medio | No |
| Ingestión por cola o bus (SQS, NATS, RabbitMQ, Redis Streams, Kafka) | La latencia del productor baja a la del bus, pero aumenta la latencia hasta el envío; se pierde la respuesta síncrona (id, validación, 422) | Baja | Peor | Requiere cliente del bus | **Sí** (viola NFR-10) | Alto | No; el desacople se consigue con un *outbox* en el producto |
| SMTP de *submission* desde los productos | Peor (diálogo SMTP de varios RTT) | Baja | Peor | Alta (cualquier librería) | Servidor SMTP de entrada | Alto (parseo MIME, auth SMTP) | No (contradice ADR-0003) |
| Endpoint de lote REST | ~3× menos coste de BD por mensaje en envíos múltiples | Alta | Total | Total | Ninguna | Bajo | **Se añade** |

### Lo que se perdería al salir de REST

- depuración con `curl`;
- Swagger/OpenAPI como contrato único (FR-25);
- compatibilidad con cualquier cliente sin generar código;
- la misma pila para los webhooks entrantes del proveedor (que son HTTP/JSON y lo seguirán siendo);
- y un segundo contrato que versionar.

## Consecuencias

- **Positivas:** cero infraestructura nueva; contrato único; las dos mejoras con efecto medido (keep-alive y lote) cuestan poco.
- **Negativas / trade-offs aceptados:** los productos deben configurar bien su cliente HTTP (pool y keep-alive); el lote introduce respuestas parciales que el cliente debe procesar por elemento.

## Criterio de revisión (umbral medible)

Reconsiderar el protocolo solo si:
- > 200 peticiones/s sostenidas en la API durante una semana;
- p95 de aceptación > 150 ms con la BD descartada como causa (`db_time` < 50 % del total);
- > 30 M correos/mes.

Reconsiderar la ingestión por cola si un producto necesita seguir encolando con el email-service caído más de 15 min y su *outbox* propio no basta.
