# ADR-0009 — Stack ratificado: Java 25 + Spring Boot 4.1 + PostgreSQL 18, con una justificación medible

- **Estado:** Accepted (owner, 2026-10-05)
- **Fecha:** 2026-10-05
- **Autor:** agente: Claude (auditoría de documentación)
- **Supersede:** ADR-0006
- **Requisitos relacionados:** NFR-01, NFR-02, NFR-09, NFR-10, NFR-12, NFR-13, NFR-16, NFR-19

## Contexto

ADR-0006 fijó las versiones y `04` §2 justificaba el lenguaje casi solo por "consistencia con mis otros proyectos". Se pidió cuestionar la elección para **este** servicio, cuyo perfil es:
- muchas conexiones de E/S y poca CPU;
- latencia de aceptación baja;
- trabajo en segundo plano continuo;
- un solo mantenedor;
- < 100 000 correos/mes (≈ 0,04 msg/s de media).

### Versiones vigentes (verificadas el 2026-10-05)

| Componente | Versión vigente | Soporte | Fuente |
|---|---|---|---|
| Java | 25 LTS (Java 26 no es LTS) | LTS | Spring Boot 4.1 soporta Java 17–26 ([endoflife.date/spring-boot](https://endoflife.date/spring-boot)) |
| Spring Boot | 4.1.1 (2026-08-20); 4.0.8 | 4.1 OSS hasta 2027-07-31; 4.0 hasta 2026-12-31; 3.5 OSS terminó el 2026-06-30 | [endoflife.date/spring-boot](https://endoflife.date/spring-boot) |
| PostgreSQL | 18.6 (2026-08-11) | Hasta 2030-11-14; la 19 no figuraba como publicada | [endoflife.date/postgresql](https://endoflife.date/postgresql) |
| Go | 1.27.1 (1.27: 2026-08-19) | Las dos últimas versiones | [endoflife.date/go](https://endoflife.date/go) |
| Node.js | 24 LTS (24.21.0); 26 pasa a LTS en oct. 2026 | 24: seguridad hasta 2028-04-30 | [endoflife.date/nodejs](https://endoflife.date/nodejs) |
| Elixir / Erlang OTP | 1.20.4 / OTP 29.1.1 | Activas | [endoflife.date/elixir](https://endoflife.date/elixir), [endoflife.date/erlang](https://endoflife.date/erlang) |
| Rust | 1.99.0 (2026-10-01) | Solo la última versión estable | [endoflife.date/rust](https://endoflife.date/rust) |

### Medidas propias (2026-10-05, contenedor de 2 vCPU, PostgreSQL 16 local)

- Camino de aceptación completo (servidor de referencia en Node 22 + `pg`): **1,38 ms p50 / 2,33 ms p95**, de los cuales la BD aporta **1,33 ms (96 %)**. El trabajo del lenguaje (parse, auth, validación y serialización) suma ~44 µs. **El lenguaje no es el cuello de botella**; la BD sí.
- RSS en reposo tras 3 000 peticiones:
  - servidor Node de referencia (HTTP/2 + `pg`): **72 MB**;
  - servidor mínimo de la JDK (`HttpServer` + hilos virtuales, Java 21): **125 MB**. Es la cota inferior de la JVM.
  - Una app Spring Boot real con Actuator, JDBC y Flyway suele quedar en 200–300 MB (**a confirmar**: se mide en H1; criterio de aceptación: RSS ≤ 350 MB con `-Xmx256m`).
  - Go no se pudo medir (el proxy de módulos no es accesible desde el entorno de la auditoría); referencia habitual de 10–30 MB, **a confirmar**.
- Todos los candidatos caben en una instancia de 1 GB. En un VPS de 512 MB solo caben Go, Rust y Node con holgura: el diferencial de hosting es de 0 a ~5 USD/mes (**a confirmar** según el hosting elegido).

## Decisión

**Se mantiene Java 25 LTS + Spring Boot 4.1.x + Spring Data JDBC + Flyway + PostgreSQL 18.x + Gradle (Kotlin DSL).** Es una recomendación firme, con la justificación corregida:

1. **El perfil de E/S está resuelto en la JVM actual:** `spring.threads.virtual.enabled=true` (hilos virtuales) para la API y para el worker. Las llamadas bloqueantes a la BD y al proveedor no consumen hilos de plataforma.
2. **El rendimiento no diferencia a los candidatos** a este volumen ni a 100× (medido: el 96 % del tiempo es la BD).
3. **El ecosistema cubre todo sin código artesanal:** validación, Actuator/Micrometer, OpenAPI (springdoc), Flyway, Testcontainers, ArchUnit, un cliente HTTP con timeouts, JSON Schema y Handlebars.java. Go necesitaría ensamblar más piezas (validación, OpenAPI y migraciones por separado).
4. **El criterio decisivo es el costo de cartera:** ya operas Java 25 + Spring Boot 4.1 en otro servicio (mismas convenciones, mismo pipeline, mismas alertas). Con cuatro productos y un solo mantenedor, un stack más es la mayor fuente de coste a largo plazo.

### Matriz ponderada (1 = peor, 5 = mejor)

| Criterio (peso) | Java + Spring Boot | Go | Node/TypeScript | Elixir (BEAM) | Rust |
|---|---|---|---|---|---|
| Latencia y throughput de E/S (10) | 4 — hilos virtuales | 5 — goroutines | 4 — event loop | 5 — procesos BEAM | 5 — async/tokio |
| Memoria y costo de hosting (10) | 2 — ≥ 125 MB medido, ~250 MB típico | 5 | 5 — 72 MB medido | 4 | 5 |
| Madurez de librerías de correo y de colas (15) | 5 — Spring, Flyway, Handlebars.java, SDK de Resend | 5 — pgx, River (cola en PG), SDK de Resend | 5 — SDK oficial de Resend, pg-boss, graphile-worker | 4 — Swoosh, Oban | 3 — apalis, SDK no oficial |
| Fiabilidad del trabajo en segundo plano (15) | 4 — propio con SKIP LOCKED, probado | 5 — River | 3 — un solo hilo; un bloqueo de CPU frena todo | 5 — Oban + supervisión OTP | 2 — ecosistema joven |
| Facilidad de operar para una persona (15) | 4 — imagen JRE, Actuator | 5 — binario estático | 4 | 3 — releases, configuración de la VM | 4 |
| Velocidad de desarrollo para ti (15) | 4 — conocido | 3 | 3 | 2 | 1 |
| Costo de un stack adicional en la cartera (20) | 5 — ya en uso | 1 | 2 — TypeScript ya se usa en los frontends (confirmado por el owner) | 1 | 1 |
| **Total ponderado** | **4,15** | **3,90** | **3,55** | **3,20** | **2,70** |

**Sensibilidad:** sin el criterio de cartera (pesos re-normalizados sobre 80), el resultado se invierte: Go 4,63, Java 3,94, Node 3,94, Elixir 3,75 y Rust 3,13. La decisión depende, por tanto, de que Java siga siendo el stack principal de tu cartera. Dicho sin rodeos: **si este fuera tu único servicio, la recomendación sería Go**.

### Piezas no-lenguaje

| Pieza | Decisión | Motivo |
|---|---|---|
| Base de datos | PostgreSQL 18.x gestionado (1 vCPU/1 GB) | Cola, RLS, `jsonb` e índices parciales; 18 tiene soporte hasta 2030 |
| Cola | Se queda en PostgreSQL (ADR-0010) | Toma de 2,4–4,8 ms con 12 800 mensajes en cola; sin doble escritura |
| Motor de plantillas | Handlebars.java endurecido (ADR-0011); jmustache de reserva | Helpers de formato con lista blanca; reserva si deja de mantenerse |
| Almacenamiento de objetos | **Ninguno** | Adjuntos ≤ 2 MB, transitorios en PG y purgados al enviar (ADR-0015); exportaciones grandes como enlaces firmados del producto |
| Observabilidad | Micrometer + endpoint Prometheus; logs JSON a stdout con retención de 30 días en el destino de logs del hosting; OTLP desactivado pero configurable | Sin componentes propios que operar |
| Circuit breaker y reintentos | Código propio (~50 líneas) | No justifica añadir Resilience4j |
| Imagen | `eclipse-temurin:25-jre` (runtime) y `25-jdk` (build), usuario no-root | Igual que ADR-0006 |
| Arranque | CDS/AOT de Spring solo si el arranque supera 10 s en el hosting | No es un requisito hoy |

### Versiones fijadas

| Componente | Versión |
|---|---|
| Java | **25 (LTS)**, toolchain de Gradle fijado |
| Spring Boot | **4.1.x** (4.1.1 o posterior al iniciar) |
| Acceso a datos | Spring Data JDBC + Flyway (sin JPA/Hibernate) |
| PostgreSQL | **18.x** (`postgres:18-alpine` en local) |
| Plantillas | Handlebars.java 4.5.x (endurecido) |
| Build | **Gradle (Kotlin DSL)**, wrapper commiteado |
| Tests | JUnit 5, Testcontainers, WireMock, Awaitility, ArchUnit |

Reglas heredadas de ADR-0006 que se mantienen:
- no se usan APIs de Spring Boot 3.x;
- las versiones de parche viven en `build.gradle.kts` y `docker-compose.yml`;
- subir una versión mayor requiere un ADR.

### Costo de la decisión

Es casi nulo: no hay aprendizaje ni pipeline nuevos. El único coste nuevo es configurar hilos virtuales y los dos `DataSource` (ADR-0008). Cambiar a Go costaría, como estimación propia **a confirmar**:
- unas 2–3 semanas a tiempo parcial de aprendizaje y andamiaje;
- un pipeline de CI nuevo;
- un segundo conjunto de convenciones, alertas y dependencias que vigilar indefinidamente.

## Alternativas consideradas

Ver la matriz. Descarte en una línea cada una:
- **Go:** mejor ajuste técnico, peor ajuste de cartera.
- **Node/TS:** el mejor ecosistema de correo, pero el más frágil para el worker de larga vida.
- **Elixir:** la mejor tecnología de colas (Oban), pero el mayor salto de aprendizaje.
- **Rust:** coste de desarrollo desproporcionado para un servicio de E/S.

## Consecuencias

- **Positivas:** cero coste de transición; justificación verificable; criterio de cambio explícito.
- **Negativas / trade-offs aceptados:** mayor memoria (instancia de 1 GB en lugar de 512 MB); arranque más lento que Go (irrelevante con despliegues *rolling*).

> **Confirmado por el owner (2026-10-05):** todos los servicios actuales son Spring Boot y los frontends usan TypeScript; el supuesto de cartera se mantiene.

## Criterio de revisión

- Tus nuevos servicios dejan de ser Java (por ejemplo, si PipeMend o Colmena se construyen en Go o TypeScript): el criterio de cartera se invierte y conviene reevaluar con la matriz.
- RSS medido en H1 > 350 MB con `-Xmx256m`, o el coste de hosting de la app > 15 USD/mes por memoria.
- Fin de soporte OSS de Spring Boot 4.1 (2027-07-31): subir a la siguiente versión menor con su ADR.
