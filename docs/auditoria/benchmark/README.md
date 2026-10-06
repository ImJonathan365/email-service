# Banco de pruebas del camino de aceptación (2026-10-05)

Scripts usados para las medidas de ADR-0014, ADR-0009 y ADR-0010. Reproducibles en cualquier máquina con Node 22 y PostgreSQL ≥ 16.

1. Arrancar PostgreSQL en el puerto 55432 con socket en `/tmp`, usuario `bench`, `fsync=on`.
2. `psql -h /tmp -p 55432 -U bench -d postgres -f schema.sql`
3. `npm install` y generar el certificado: `openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:P-256 -nodes -keyout key.pem -out cert.pem -days 2 -subj /CN=localhost`
4. `node server.js &` y luego `N=1500 node client.js` (latencia por modo de transporte y desglose del servidor).
5. `node ser.js` (JSON frente a protobuf, MessagePack y CBOR).
6. `node batch.js` (lote frente a una transacción por mensaje).

Resultados obtenidos (contenedor Linux de 2 vCPU, PostgreSQL 16.15, Node 22.22): ver la tabla de ADR-0014.
El servidor está en Node, no en Java: lo que se mide es la proporción entre protocolo y BD, no el rendimiento absoluto del servicio final. Repetir con el servicio real en H4.
