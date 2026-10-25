# Acceptance path test bench (2026-10-05)

Scripts used for the measurements in ADR-0014, ADR-0009 and ADR-0010. Reproducible on any machine with Node 22 and PostgreSQL ≥ 16.

1. Start PostgreSQL on port 55432 with the socket in `/tmp`, user `bench`, `fsync=on`.
2. `psql -h /tmp -p 55432 -U bench -d postgres -f schema.sql`
3. `npm install` and generate the certificate: `openssl req -x509 -newkey ec -pkeyopt ec_paramgen_curve:P-256 -nodes -keyout key.pem -out cert.pem -days 2 -subj /CN=localhost`
4. `node server.js &` and then `N=1500 node client.js` (latency per transport mode and the server's breakdown).
5. `node ser.js` (JSON against protobuf, MessagePack and CBOR).
6. `node batch.js` (batch against one transaction per message).

Results obtained (a 2 vCPU Linux container, PostgreSQL 16.15, Node 22.22): see the table in ADR-0014.
The server is in Node, not Java: what is measured is the ratio between protocol and database, not the absolute performance of the final service. Repeat with the real service in H4.
