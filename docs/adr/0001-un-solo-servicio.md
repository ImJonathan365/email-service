# ADR-0001 — Un solo servicio desplegable (API + worker) en lugar de varios microservicios

- **Estado:** Accepted
- **Fecha:** 2026-09-20
- **Requisitos relacionados:** NFR-09, NFR-10, NFR-12

## Contexto
El email-service tiene dos modos de ejecución naturales: atender peticiones HTTP y procesar la cola de envíos. La tentación habitual es separarlos en dos servicios (o más: plantillas, webhooks, administración). El proyecto lo mantiene una sola persona y el volumen objetivo es < 100.000 correos/mes.

## Decisión
Un único artefacto desplegable que contiene API, worker y webhooks, con el rol activo seleccionado por la variable `APP_ROLE=api|worker|all`. Una sola base de datos PostgreSQL, exclusiva del servicio.

Escalado: N instancias con `APP_ROLE=api` detrás del balanceador y M instancias con `APP_ROLE=worker`. Misma imagen, misma configuración base.

## Alternativas consideradas
| Alternativa | A favor | En contra | Motivo de descarte |
|---|---|---|---|
| API y worker como servicios separados | Despliegue y escalado independientes "puros" | Dos pipelines, dos imágenes, dos configuraciones, código compartido en una librería | `APP_ROLE` da el mismo escalado con una fracción del costo operativo |
| Descomposición por dominio (plantillas, envíos, eventos) | "Arquitectura limpia de microservicios" | Latencia, transacciones distribuidas, complejidad enorme para un dominio de 8 tablas | Sobreingeniería evidente |
| Función serverless por envío | Sin servidores que mantener | El worker necesita conexiones persistentes y control de tasa; arranque en frío y límites de tiempo | Mal ajuste |

## Consecuencias
- Positivas: un pipeline, un artefacto, una configuración; depuración local trivial; transacciones locales.
- Negativas: un fallo en el módulo de API puede afectar al proceso `all` en despliegues pequeños (mitigado separando roles en producción); los módulos deben respetar límites internos por disciplina y tests de arquitectura, no por frontera de red.

## Criterio de revisión
Que la API y el worker necesiten perfiles de recursos o cadencias de despliegue radicalmente distintos, o que el servicio deje de ser mantenido por un equipo pequeño.
