# email-service

Microservicio interno de correo **transaccional** para PipeMend, Colmena, Chinamo y Payverica. Es un único servicio multi-tenant con aislamiento por RLS en PostgreSQL, plantillas por producto, envío asíncrono y trazabilidad. No es una plataforma de marketing.

La fuente de verdad es [`docs/`](docs/README.md). Reglas para asistentes de IA: [`AGENTS.md`](AGENTS.md).

> Estado: hitos H1 (esqueleto), H2 (tenancy), H3 (plantillas) y H4 (envío de extremo a extremo con SMTP hacia Mailpit). El proveedor real (Resend) llega en H5 ([`docs/09-roadmap.md`](docs/09-roadmap.md)). La guía de integración para productos se completa en H9.

## Arranque local

Requisitos:
- Docker con Compose;
- para los tests y los scripts: Java 25 y `jq`, ambos declarados en [`mise.toml`](mise.toml) (`mise install`).

```bash
cp .env.example .env            # solo marcadores locales; .env está en .gitignore
docker compose up --build       # app (APP_ROLE=all) + PostgreSQL 18 con 3 roles + Mailpit
./scripts/seed-local.sh         # tenant "demo", una key de producto, una de operador y las plantillas de templates/demo
./scripts/smoke-test.sh         # POST /v1/emails -> cola -> worker -> SMTP -> Mailpit, verificado por su API
```

| URL | Qué es |
|---|---|
| http://localhost:8080/swagger-ui.html | Swagger UI (grupos `public` y `admin`) |
| http://localhost:8080/actuator/health/readiness | Readiness (depende solo de la BD) |
| http://localhost:8025 | Mailpit (SMTP de desarrollo; no entrega nada fuera) |

Tests: `./gradlew build` (necesita Docker: Testcontainers con los mismos roles y RLS que producción).

## Plantillas como código

Las plantillas viven en este repositorio y se publican por la API ([`docs/05`](docs/05-arquitectura.md) §9):

```
templates/{tenant}/{templateKey}/
├── template.json     # key, name, category, subject por locale, variablesSchema, previewVariables
├── es-CR.html        # un .html por locale (obligatorio)
├── es-CR.txt         # texto plano (opcional)
├── en.html
└── en.txt
```

```bash
EMAIL_SERVICE_TEMPLATES_KEY=esk_test_... ./scripts/publish-template.sh demo password-reset
```

El script:
- crea la plantilla si no existe;
- omite los locales sin cambios;
- crea los borradores y los previsualiza con `previewVariables`;
- publica solo si todas las previsualizaciones pasan; si algo falla, borra los borradores que creó.

- Con varios idiomas cambiados, los publica todos en una sola operación (ADR-0020), lo que permite cambiar las variables obligatorias de una plantilla multilingüe.

La key debe ser de operador (`templates:write` y `emails:read`), nunca la que se despliega en un producto. En CI, `TemplatesAsCodeTest` valida cada plantilla con las mismas reglas que el servicio.

El CI no usa mise ni `jq`: Java lo instala `setup-java` y ningún job ejecuta los scripts. `jq` solo hace falta en local para los scripts; lo instala `mise install`, y en los runners de GitHub ya viene incluido.

Reglas de las plantillas (ADR-0011):
- Handlebars *logic-less*;
- solo `if`, `unless`, `each`, `with`, `formatDate`, `formatNumber` y `formatMoney`;
- sin `{{{ }}}`, `{{& }}` ni parciales;
- HTML escapado en el cuerpo;
- variables URL con `"format": "uri"`, `https` y un host de `allowedLinkHosts` del tenant.

## Ramas

- `main`: siempre desplegable.
- `dev`: integración.
- `feat/*`: una rama por hito.

Detalle en [`AGENTS.md`](AGENTS.md#branching).
