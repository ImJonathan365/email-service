# 12 — Estado del proyecto y uso en local

> Creado el 2026-10-14; revisado el 2026-10-15 para que otra sesión pueda retomar el trabajo sin el historial del chat. Primera parte: dónde está el proyecto y qué falta. Segunda parte: cómo usar el servicio desde otros proyectos en local. Despliegue y H9 están en pausa hasta que el owner tome las decisiones de infraestructura.

## Parte 1 — Estado actual

### Para retomar el trabajo

1. **Leer, en este orden:**
   - `AGENTS.md`: reglas, ramas, tags y comandos;
   - `docs/10-guia-para-agentes-ia.md`;
   - este documento;
   - el requisito concreto en `02`.
2. **Versión estable:** `main` en `v0.8.1`. Es la que usan los otros proyectos en local.
3. **Forma de trabajo con el owner:**
   - plan primero, y esperar su OK antes de escribir código;
   - una rama `feat/*` o `fix/*` desde `dev`, con commits Conventional que citan los FR;
   - CI en verde antes de integrar en `dev` con `--no-ff`;
   - `dev` → `main` lo hace solo el owner, y después el agente crea y sube el tag anotado.
4. **CI:** `.github/workflows/ci.yml`, con dos jobs:
   - `build`: tests con Testcontainers;
   - `gitleaks`: versión fijada en `GITLEAKS_VERSION`; la configuración es `.gitleaks.toml` más `.gitleaksignore`.

   Se dispara en `main`, `dev`, `feat/**`, `fix/**` y en los pull requests. Antes de esperar un CI, hay que comprobar que la rama está entre esos disparadores.
5. **Lo siguiente:** la parte A de H9 (abajo), ítem por ítem y con plan previo. La parte B espera las decisiones del owner.

### Hitos y versiones

| Hito | Contenido | Tag |
|---|---|---|
| H1–H3 | Esqueleto, tenancy y plantillas | `v0.3.0-h3` |
| H4 | Envío de extremo a extremo: cola, worker, SMTP → Mailpit | `v0.4.0-h4` |
| H5 | Proveedor real (Resend), circuit breaker y limitador | `v0.5.0-h5` |
| H6 | Webhooks, estados por precedencia y supresiones | sin tag propio: va dentro de `v0.7.0-h7` (decisión del owner) |
| H7 | Operación y protección: consultas, cuotas, anomalías, métricas, alertas, purgas, borrado por titular | `v0.7.0-h7` |
| H8 | Envío por lote, `sendAt` y cancelación | `v0.8.0-h8` |
| — | Uso local desde otros proyectos: red Docker, seed idempotente, ADR-0021, eventos simulados, plantillas desde cualquier repositorio (parte 2) | `v0.8.1` |

### H9 (Producción), parte A: lo que se puede hacer en el repositorio

Ninguno de estos ítems necesita decisiones de infraestructura. Están ordenados por dependencia: la auditoría y el producto simulado van primero, porque el resto se apoya en ellos.

1. **Auditoría de cierre de la Fase 0**, con el detalle en la tabla de abajo.
   - Cada criterio de `09` debe quedar marcado como hecho, con su evidencia (test o documento), o como pendiente, con la razón.
   - Lo más grande es la cobertura de los FR **Must**.
2. **Producto simulado en `examples/`.** Todavía no existe. Es un proyecto mínimo que hace lo que hará un producto real, siguiendo solo la parte 2 de este documento:
   - su propio `docker-compose.yml`, unido a `email-service-net`;
   - un `.env` con `EMAIL_SERVICE_URL` y `EMAIL_SERVICE_API_KEY`, más un `.env.example` versionado;
   - sus plantillas en su propia carpeta, publicadas con `publish-template.sh` por ruta;
   - envíos con `Idempotency-Key` derivada de un evento de dominio (`07`): uno simple, uno por lote y uno con `sendAt`;
   - lectura del estado con `GET /v1/emails/{id}`.

   Cuenta como "primer producto integrado en local" y es la prueba de la guía de integración. Debe tener su propio script de prueba de punta a punta, que llegue a Mailpit y simule un evento. Sin lógica de producto real: el servicio no puede conocer a ningún tenant.
3. **Guía de prueba de extremo a extremo con Resend real.** El README tiene las piezas sueltas, pero falta un único procedimiento que las una:
   - **Piezas que ya existen en el README:**
     - "Envío real desde staging (Resend)": equipo de Resend, dominio, key y variables;
     - "Webhooks de Resend en staging": proxy que solo deja pasar `/webhooks/*` y túnel temporal de `cloudflared`;
     - las direcciones de prueba `delivered@resend.dev`, `bounced@resend.dev` y `complained@resend.dev`.
   - **Lo que tiene que cubrir el procedimiento,** paso a paso y desde una máquina de desarrollo:
     - la app con `APP_ENV=staging` y `MAIL_PROVIDER=resend`, contra el PostgreSQL de compose;
     - `ALLOWED_RECIPIENT_DOMAINS=resend.dev`;
     - el túnel, con el endpoint temporal registrado en Resend y su secreto;
     - un envío a cada dirección `@resend.dev`, comprobando el estado (`DELIVERED`, `BOUNCED`, `COMPLAINED`) y las supresiones globales;
     - la limpieza: túnel, endpoint y supresiones de prueba.
   - **Para ejecutarlo** hace falta la cuenta de Resend de staging (parte B), pero el documento se puede escribir y revisar antes.
   - Sigue sin enviar nunca a direcciones reales, y los tests siguen sin llamar a Resend.
4. **Manifiestos de despliegue genéricos:**
   - el orden `migrate → api → worker`;
   - las variables por rol con sus valores de producción, salvo los secretos;
   - sin elegir todavía proveedor de hosting.
5. **Respaldos de PostgreSQL:**
   - script de respaldo cifrado y de restauración;
   - un ensayo de restauración contra una base desechable, que quede como test o como procedimiento repetible.
6. **Lista de `08` §10, solo los puntos que se comprueban en código:**
   - escaneo de secretos;
   - Actuator mínimo: hoy `/actuator/health` muestra los componentes; hay que revisar qué se expone fuera de local;
   - roles y RLS;
   - keys de producto sin `templates:write` ni `suppressions:write`;
   - tests de aislamiento.
7. **README de integración en menos de 30 minutos:** la parte 2 de este documento sin lo que es solo local, probado con el producto simulado del punto 2.

### H9 (Producción), parte B: lo que espera decisiones del owner

Cada ítem depende de una decisión de la lista siguiente.

| Ítem | Depende de |
|---|---|
| Dominio remitente por producto, con SPF, DKIM y DMARC verificados en Resend | Dominio |
| Equipos de Resend de staging y producción; endpoint de webhooks registrado y firma verificada con un evento real | Cuentas de Resend, dominio, hosting |
| Secretos cargados en el gestor: `ADMIN_API_KEYS`, `SUPPRESSION_HASH_KEY` (con respaldo fuera de línea), `RESEND_API_KEY`, `MAIL_WEBHOOK_SIGNING_SECRET` y contraseñas de BD | Secretos |
| Staging desplegado; NFR-19 medido allí (aceptación → `SENT`, p95 ≤ 5 s) | Hosting, cuentas de Resend |
| Producción desplegada: PostgreSQL con respaldo cifrado activo y **restauración probada una vez**, TLS, y solo `/webhooks/*` público | Hosting, secretos |
| Alertas conectadas al hosting de logs (campo `alert`, README "Operación") | Hosting |
| Primer producto real enviando en producción | Todo lo anterior, más los textos legales de ese producto |

**Decisiones pendientes del owner:**
- **Dominio:** qué dominio o subdominio remitente usa cada producto (por ejemplo, `mail.colmena.cr`), y quién gestiona su DNS.
- **Hosting:**
  - dónde corren `api`, `worker` y PostgreSQL;
  - cómo se exponen solo `/webhooks/*` y el resto por red privada;
  - dónde van los respaldos.
- **Cuentas de Resend:** equipos de staging y producción, plan y límites.
- **Secretos:** qué gestor de secretos se usa y quién tiene acceso; dónde se guarda el respaldo fuera de línea de `SUPPRESSION_HASH_KEY`.
- **Textos legales** (`08` §6.1 y §10), que bloquean la salida a producción de cada producto, no el desarrollo:
  - el aviso de privacidad de cada producto, que debe cubrir la transferencia al proveedor en EE. UU. (Ley 8968, art. 14);
  - los términos de Colmena con sus negocios.
- **Propuestas de H7 que siguen sin confirmar:**
  - AC-23.9: `DELIVERED` cuenta como final a las 72 h, para purgar sus variables;
  - `03` NFR-11: las alertas `PROVIDER_CONFIGURATION` y `AUTH_FAILURE_SPIKE`, y el mínimo de 20 mensajes para `FAILED_RATE`.

  Están implementadas tal como se propusieron.

### Auditoría de cierre de la Fase 0 (`09`)

Estado al 2026-10-15. "Hecho" solo cuando hay evidencia concreta.

| Criterio de `09` | Estado | Evidencia o qué falta |
|---|---|---|
| Un producto real en producción envía sus correos por el servicio | Pendiente | H9 parte B |
| `docker compose up` desde un clon limpio envía a Mailpit sin credenciales externas | Hecho | `scripts/smoke-test.sh` (AC-15.2), probado el 2026-10-14 |
| FR **Must** con sus criterios cubiertos por tests | **Por auditar** | Ver la nota siguiente |
| Tests de aislamiento multi-tenant, sin contexto (RLS) y de FK cruzada | Hecho, a confirmar en la auditoría | `RowLevelSecurityTest`, `SchemaInvariantsTest` y los tests por endpoint de H2–H8; falta comprobar que cada endpoint `/v1/**` tenga el suyo |
| Con el proveedor caído, la API acepta y la cola se drena al recuperarlo | Hecho, a confirmar en la auditoría | Tests del circuit breaker de H5 (`ResendWorkerTest`); AC-11.2 no tiene un test que lo nombre |
| Test de "respuesta perdida": un solo envío en el proveedor simulado | Hecho | `ResendWorkerTest` (WireMock) |
| NFR-19 medido en staging | Pendiente | No hay staging (parte B) |
| Lista de `08` §10 completa | Pendiente | Parte A, punto 6, y parte B |
| README con guía de integración en < 30 minutos | Pendiente | Parte A, punto 7 |

**Cobertura de los FR Must:**
- **Método:** cruce mecánico entre las AC de `02` (las de los FR marcados como Must en la tabla de §1) y las referencias en `src/test/java`: nombres `ac_XX_Y` o comentarios `AC-XX.Y`.
- **Resultado:** de **150** AC Must, **122** tienen un test que las nombra. Las **28** restantes son: AC-01.4, 02.5, 02.6, 03.6, 05.3, 10.3, 11.1, 11.2, 11.4, 11.5, 12.1, 12.4, 14.1, 14.3, 14.4, 14.5, 14.6, 14.7, 15.2, 15.4, 16.6, 21.6, 23.6, 23.8, 23.9, 29.4, 33.2 y 33.4.
- **Qué hay que hacer con cada una:** encontrar el test que ya la cubre con otro nombre y referenciarla en él, o escribir el test que falta.
  - Varias tienen tests probables: por ejemplo, 14.3 y 14.7 con ArchUnit, 21.6 en `BatchAnomalyTest`, 33.2 con `INSUFFICIENT_SCOPE` y 15.2 con el smoke test.
  - Otras no son verificables con un test, y deben quedar marcadas como tales:
    - AC-23.6 no aplica, porque FR-30 está rechazado;
    - AC-23.9 espera la confirmación del owner;
    - AC-29.4 es un procedimiento de `08` §6;
    - AC-14.5 es una propiedad de diseño.
- **Sin evidencia, la AC no se da por cubierta.**

## Parte 2 — Usar el servicio desde otros proyectos en local

### 1. Arrancar el servicio

Desde `main`, que siempre es desplegable (o fijo en un tag: `git switch --detach v0.8.1`):

```bash
git switch main && git pull
cp -n .env.example .env        # solo la primera vez; .env no se versiona
grep -q '^MAIL_WEBHOOK_SIGNING_SECRET=' .env \
  || grep '^MAIL_WEBHOOK_SIGNING_SECRET=' .env.example >> .env   # ver la nota de abajo
docker compose up -d --build
```

Contenedores y servicios:
- `app`, `postgres` y `mailpit` tienen `restart: unless-stopped`: vuelven a arrancar con Docker hasta que los pares con `docker compose stop`.
- La API queda en `http://localhost:8080`, Mailpit en `http://localhost:8025` y PostgreSQL en `localhost:5432`, todo solo en `127.0.0.1`.

**`MAIL_WEBHOOK_SIGNING_SECRET` en `.env`.** Un `.env` copiado antes de `v0.8.1` no tiene esta variable. La segunda línea del bloque de arriba la copia de `.env.example` solo si falta.
- Es un secreto **ficticio**, solo para local; gitleaks lo admite por su valor exacto (`.gitleaks.toml`).
- Sin él, `/webhooks/resend` responde `404` y `scripts/simulate-event.sh` lo indica.
- Si ya estaba la app levantada, hay que recrearla con `docker compose up -d` para que lo lea.
- Nunca pongas aquí el secreto de un endpoint real de Resend.

**Datos:**
- `docker compose down` (sin `-v`) conserva la base.
- **`docker compose down -v` borra el volumen de PostgreSQL**, y con él todos los tenants, keys, plantillas, mensajes y supresiones. Después hay que repetir los pasos 2 y 4 en cada proyecto y actualizar las keys de sus `.env`.

### 2. Un tenant por proyecto

```bash
SLUG=colmena ./scripts/seed-local.sh
```

**Primera vez:**
- crea el tenant `colmena`, con remitente `no-reply@colmena.localhost` y `allowedLinkHosts: ["colmena.localhost"]`;
- imprime **una sola vez** dos keys: la de producto (`emails:send`, `emails:read`) y la de operador (`templates:write`, `suppressions:write`, `emails:read`);
- publica las plantillas de `templates/colmena/`, si existen en este repositorio.

**Siguientes veces:** el script no toca nada y lo dice. Las keys guardadas siguen funcionando.

**Keys perdidas:** con `--new-keys` emite un par nuevo. Las anteriores siguen siendo válidas hasta revocarlas (`GET /admin/v1/tenants/{slug}/api-keys` para ver su `id`, `DELETE /admin/v1/api-keys/{id}`).

### 3. Dónde guardar las keys

En el `.env` del proyecto, que **no se versiona** (debe estar en su `.gitignore`):

```bash
# .env de Colmena
EMAIL_SERVICE_URL=http://email-service:8080       # desde un contenedor; desde el host, http://localhost:8080
EMAIL_SERVICE_API_KEY=esk_test_...                # key de producto
EMAIL_SERVICE_TEMPLATES_KEY=esk_test_...          # key de operador: solo para publicar plantillas
```

**Reglas de las keys:**
- El código del producto solo usa la key de producto.
- La key de operador sirve para el script de publicación. No va en la imagen ni en el contenedor del producto (FR-33).
- Las keys locales empiezan por `esk_test_` y solo existen en la base local: en cada entorno se emiten keys propias.

### 4. Plantillas en el repositorio del producto

Cada plantilla es una carpeta con `template.json`, un `{locale}.html` por idioma y, opcionalmente, un `{locale}.txt`. El formato está en `05` §9 y el ejemplo en `templates/demo/password-reset/`.

```
colmena/
└── emails/
    └── password-reset/
        ├── template.json
        ├── es-CR.html
        └── en.html
```

Publicar desde el repositorio del producto, con la ruta de la carpeta:

```bash
set -a; source .env; set +a
EMAIL_SERVICE_URL=http://localhost:8080 ../email-service/scripts/publish-template.sh emails/password-reset
```

Cómo funciona el script:
- La key de operador decide el tenant.
- Repetirlo sin cambios no crea versiones.
- Solo publica si la previsualización de todos los idiomas pasa.
- Necesita `curl` y `jq`.

**Enlaces en las plantillas:** con `APP_ENV=local` (el de `.env.example`), las variables URL también aceptan `http`/`https` hacia `localhost` y `*.localhost`, como `http://colmena.localhost:3000/reset?t=…` (ADR-0021). En staging y producción solo valen `https` y los `allowedLinkHosts` del tenant.

### 5. La red Docker

El servicio crea la red `email-service-net` (nombre fijo, sin importar la carpeta) y la app tiene el alias `email-service` en ella. En el `docker-compose.yml` de cada proyecto:

```yaml
services:
  app:                                  # el servicio del producto que envía correo
    environment:
      EMAIL_SERVICE_URL: http://email-service:8080
      EMAIL_SERVICE_API_KEY: ${EMAIL_SERVICE_API_KEY}
    networks: [default, email-service]

networks:
  email-service:
    name: email-service-net
    external: true
```

Notas:
- **Orden de arranque:** el email-service va primero. Si la red no existe, Docker se niega a arrancar el proyecto con `network email-service-net declared as external, but could not be found`.
- **Red propia del proyecto:** `default` sigue siendo la red del proyecto, así que sus servicios internos no quedan expuestos al email-service.
- **Desde el host:** un producto que corre fuera de Docker (por ejemplo, `npm run dev`) usa `http://localhost:8080`.

### 6. Qué cambia al desplegar

En el código del producto, nada. El cliente lee dos variables y el resto del contrato (`07`) es igual en todos los entornos:

| Variable | Local | Staging / producción |
|---|---|---|
| `EMAIL_SERVICE_URL` | `http://email-service:8080` | La URL interna del servicio desplegado (H9) |
| `EMAIL_SERVICE_API_KEY` | `esk_test_…` del seed | Key emitida en ese entorno (`esk_live_…` en producción), desde el gestor de secretos |

Lo que sí cambia está del lado del servicio, no del producto:
- el remitente y los `allowedLinkHosts` reales del tenant;
- los enlaces, que deben ser `https`;
- en staging, la allowlist de destinatarios.

### 7. Ver los correos y simular eventos

**Correos:** todo lo que el worker envía llega a Mailpit, en `http://localhost:8025`. Nunca sale a internet.

**Eventos:** Mailpit no genera eventos de entrega, así que se simulan:

```bash
./scripts/simulate-event.sh <messageId> delivered     # o bounced, complained, delayed
```

**Cómo funciona:**
- El `messageId` es el `id` que devolvió `POST /v1/emails`.
- El script envía a `POST /webhooks/resend` un evento con el formato de Resend, firmado con Svix y el secreto local de `.env`. Sigue el mismo camino que uno real: verificación de firma, estado por precedencia y supresiones. No hay endpoint ni atajo especial.

**Efectos:**
- `bounced` y `complained` crean una supresión **global** de la dirección. Los envíos siguientes a ella se aceptan (`202`) pero quedan en `FAILED` con `failureCode: SUPPRESSED`, sin llegar a Mailpit (AC-10.1).
- Para quitarla: `DELETE /admin/v1/suppressions/{email}` con la cabecera `X-Admin-Key`.
- Muchos `bounced` o `complained` seguidos pueden pausar el tenant por tasa de rebotes o quejas (FR-34), como en producción. Se levanta con `resume-sending` (punto 8).
- `delayed` se registra sin cambiar el estado.
- Un `messageId` que no existe se acepta (`204`) y no suprime a nadie.

### 8. Subir los límites del tenant

Por defecto, cada tenant tiene 60 envíos por minuto, 1000 al día y la detección de anomalías de volumen (pausa si una hora supera 3× lo habitual, con un mínimo de 50). Para pruebas de carga en local:

```bash
set -a; source .env; set +a
curl -X PATCH http://localhost:8080/admin/v1/tenants/colmena \
  -H "X-Admin-Key: ${ADMIN_API_KEYS%%,*}" -H 'Content-Type: application/json' \
  -d '{"rateLimitPerMinute": 600, "dailyQuota": 50000, "anomalyOverrides": {"minHourly": 5000}}'
```

Si el tenant ya quedó en pausa por anomalía: `POST /admin/v1/tenants/colmena/resume-sending` con `{"note": "pruebas locales"}`.

### 9. Limitaciones en local

- **Sin proveedor real:**
  - no hay entrega real, ni SPF/DKIM/DMARC, ni eventos del proveedor; los eventos solo existen si se simulan;
  - `opened` y `clicked` no se simulan.
- **Sin deduplicación del proveedor:** SMTP no tiene `Idempotency-Key`, así que un reintento tras una respuesta perdida puede duplicar un correo en Mailpit. Con Resend no pasa (ADR-0010, ADR-0016).
- **Enlaces locales:** en local se aceptan enlaces a `localhost`, pero una plantilla que solo funciona con ellos fallará en staging. Prueba también con `https` y un host de `allowedLinkHosts`.
- **Datos:**
  - un solo PostgreSQL local para todos los proyectos;
  - `down -v` borra los datos de todos.
- **Red compartida:** todos los proyectos ven al email-service en `email-service-net`, y el aislamiento entre ellos lo dan las keys (y RLS), igual que en producción.
- **Reloj:** el servicio usa la hora del contenedor. `sendAt` y las ventanas de cuota dependen de ella.
