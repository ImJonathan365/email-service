# ADR-0006 — Versiones fijadas y herramienta de build

- **Estado:** Superseded by ADR-0009 (2026-10-05)
- **Fecha:** 2026-09-20
- **Requisitos relacionados:** NFR-09, NFR-12, NFR-13

## Contexto
Spring Boot 3.5 alcanzó el fin de su soporte open source el 30/06/2026; las ramas con soporte son 4.0 y 4.1. Spring Framework 7 / Spring Boot 4.x soportan JDK 17–25 y recomiendan JDK 25 en producción. Java 25 es la LTS vigente. Además, buena parte del material público (y del conocimiento de los modelos de IA) corresponde a Spring Boot 3.x.

## Decisión
| Componente | Versión |
|---|---|
| Java | **25 (LTS)**, toolchain de Gradle fijado |
| Spring Boot | **4.1.x** (última parche al iniciar) |
| Acceso a datos | Spring Data JDBC + Flyway (sin JPA/Hibernate) |
| PostgreSQL | **18.x** (`postgres:18-alpine`) |
| Plantillas | Handlebars.java |
| Build | **Gradle (Kotlin DSL)**, wrapper commiteado |
| Imagen | `eclipse-temurin:25-jdk` (build) → `eclipse-temurin:25-jre` (runtime), usuario no-root |
| Tests | JUnit 5, Testcontainers, WireMock, Awaitility |

Reglas derivadas:
- Las versiones de parche viven en `build.gradle.kts` y `docker-compose.yml`; esta tabla fija las mayores.
- **No se usan APIs de Spring Boot 3.x.** Si un ejemplo no compila, se busca el equivalente en la documentación de la versión fijada; nunca se baja la versión del framework para que compile.
- Antes de añadir una dependencia, verificar que tenga versión compatible con Spring Boot 4 y Java 25.
- Subir una versión mayor de cualquier fila requiere un ADR nuevo.

## Consecuencias
- Positivas: stack con soporte vigente y parches de seguridad; un solo conjunto de versiones para todos mis proyectos Java.
- Negativas: menos ejemplos públicos de Boot 4 que de 3.x; los asistentes de IA tienden a generar código de 3.x (mitigación: esta regla explícita y fallo de compilación temprano).

## Criterio de revisión
Nueva LTS de Java soportada por la rama de Boot en uso, fin de soporte de Spring Boot 4.1, o una dependencia imprescindible sin soporte para el stack fijado.

---
> **Nota de estado (2026-10-05):** de este ADR solo se ha modificado la línea de *Estado*; su texto aceptado no se edita. El owner aceptó ADR-0009, que lo sustituye, el 2026-10-05.
