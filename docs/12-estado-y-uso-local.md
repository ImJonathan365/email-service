# 12 — Estado del proyecto y uso en local

> Creado el 2026-10-14. Primera parte: dónde está el proyecto. Segunda parte: cómo usar el servicio desde otros proyectos en la máquina de desarrollo. Despliegue y H9 están en pausa hasta que el owner tome las decisiones de infraestructura.

## Parte 1 — Estado actual

### Hitos terminados

| Hito | Contenido | Tag |
|---|---|---|
| H1–H3 | Esqueleto, tenancy y plantillas | `v0.3.0-h3` |
| H4 | Envío de extremo a extremo: cola, worker, SMTP → Mailpit | `v0.4.0-h4` |
| H5 | Proveedor real (Resend), circuit breaker y limitador | `v0.5.0-h5` |
| H6 | Webhooks, estados por precedencia y supresiones | sin tag propio: va dentro de `v0.7.0-h7` (decisión del owner) |
| H7 | Operación y protección: consultas, cuotas, anomalías, métricas, alertas, purgas, borrado por titular | `v0.7.0-h7` |
| H8 | Envío por lote, `sendAt` y cancelación | `v0.8.0-h8` |

Después de H8, la rama `feat/local-dev-integration` añadió el uso local desde otros proyectos (esta guía, ADR-0021). No es un hito de `09`.

### Qué falta de H9 (Producción)

H9 (`09`, `08` §10) se divide en dos partes:

**Parte A — se puede hacer en el repositorio, sin decisiones de infraestructura:**
- **Manifiestos de despliegue genéricos:** el orden `migrate → api → worker`, y las variables por rol con sus valores de producción, salvo los secretos.
- **Respaldos de PostgreSQL:** script de respaldo cifrado y de restauración, y un ensayo de restauración contra una base desechable.
- **Lista de `08` §10:** revisar los puntos que se pueden comprobar en código:
  - escaneo de secretos;
  - Actuator mínimo;
  - roles y RLS;
  - keys de producto sin `templates:write`;
  - tests de aislamiento.
- **README de integración en menos de 30 minutos:** partir de la parte 2 de este documento y quitarle lo que es solo local.
- **Primer producto integrado en local**, de punta a punta, con la red de esta guía.

**Parte B — necesita las decisiones del owner de abajo:**
- **Dominio remitente** con SPF, DKIM y DMARC verificados en Resend.
- **Hosting:**
  - PostgreSQL gestionado o propio, con respaldo cifrado activo;
  - **restauración probada una vez**;
  - TLS, y solo `/webhooks/*` público.
- **Secretos en un gestor:**
  - `ADMIN_API_KEYS`;
  - `SUPPRESSION_HASH_KEY`, con respaldo fuera de línea;
  - `RESEND_API_KEY`;
  - `MAIL_WEBHOOK_SIGNING_SECRET`;
  - contraseñas de BD.
- **Cuentas de Resend:**
  - equipos distintos para staging y producción;
  - endpoint de webhooks registrado;
  - firma verificada con un evento real.
- **Staging desplegado**, con NFR-19 medido allí (aceptación → `SENT`, p95 ≤ 5 s).
- **Alertas** conectadas al hosting de logs (campo `alert`, README).
- **Producción desplegada** y el primer producto enviando de verdad.

### Qué falta para cerrar la Fase 0 (`09`)

| Criterio | Estado |
|---|---|
| Un producto real en producción envía sus correos por el servicio | Pendiente (H9 parte B) |
| `docker compose up` desde un clon limpio envía a Mailpit sin credenciales externas | Hecho (`scripts/smoke-test.sh`) |
| FR **Must** con sus criterios cubiertos por tests | Hecho hasta H8 |
| Tests de aislamiento multi-tenant, sin contexto (RLS) y de FK cruzada | Hecho |
| Con el proveedor caído, la API acepta y la cola se drena al recuperarlo | Hecho (tests de H5) |
| Test de "respuesta perdida": un solo envío en el proveedor simulado | Hecho (H5) |
| NFR-19 medido en staging | Pendiente: no hay staging |
| Lista de `08` §10 completa | Pendiente (H9 A y B) |
| README con guía de integración en < 30 minutos | Pendiente (H9 A); esta guía es la base |

### Decisiones pendientes del owner

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

## Parte 2 — Usar el servicio desde otros proyectos en local

### 1. Arrancar el servicio

Desde `main`, que siempre es desplegable:

```bash
git switch main && git pull
cp -n .env.example .env        # solo la primera vez; .env no se versiona
docker compose up -d --build
```

Contenedores y servicios:
- `app`, `postgres` y `mailpit` tienen `restart: unless-stopped`: vuelven a arrancar con Docker hasta que los pares con `docker compose stop`.
- La API queda en `http://localhost:8080`, Mailpit en `http://localhost:8025` y PostgreSQL en `localhost:5432`, todo solo en `127.0.0.1`.

**Si tu `.env` es anterior a esta guía**, añade la línea `MAIL_WEBHOOK_SIGNING_SECRET` de `.env.example`. Sin ella, `/webhooks/resend` responde `404` y no se pueden simular eventos.

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
