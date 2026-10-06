# ADR-0018 — Plantillas por idioma (es-CR por defecto, inglés disponible) en el MVP

- **Estado:** Accepted
- **Fecha:** 2026-10-05
- **Autor:** agente: Claude, a partir de la respuesta del owner a la pregunta abierta 14
- **Complementa:** ADR-0011 (no lo supersede: activa su criterio de revisión "necesidad de i18n por locale")
- **Requisitos relacionados:** FR-04, FR-05, FR-07, FR-37

## Contexto

Todos los correos se envían hoy en español, pero el owner quiere que el inglés ya esté implementado y se pueda usar sin un desarrollo posterior. El diseño anterior dejaba "i18n por locale" para la fase 2 y solo tenía un `locale` por tenant, que se usaba para formatear fechas e importes.

## Decisión

1. Cada **versión** de plantilla tiene un `locale` ∈ `SUPPORTED_LOCALES` (default `es-CR,en`). Una plantilla puede tener, como mucho, **una versión `PUBLISHED` por locale**; publicar una versión archiva solo la publicada anterior de **su mismo** locale.
2. `POST /v1/emails` acepta un `locale` opcional. Resolución:
   1. el `locale` pedido, si existe una versión publicada en ese locale;
   2. si no, `tenant.locale` (default `es-CR`);
   3. si tampoco existe, `422 TEMPLATE_NOT_PUBLISHED`.

   La respuesta indica el `locale` efectivo, y el mensaje lo guarda junto con la versión fijada.
3. Los helpers `formatDate`, `formatNumber` y `formatMoney` usan el locale **efectivo del mensaje** y `tenant.timezone`.
4. Todos los locales de una plantilla comparten la `key`, la categoría y el `variablesSchema` exigido. Al publicar, se valida que el esquema de la nueva versión sea compatible con el de las otras versiones publicadas: mismas variables `required`.
5. Sin traducción automática ni plantillas "maestras": cada idioma es una versión escrita por una persona.

## Alternativas consideradas

| Alternativa | A favor | En contra | Motivo de descarte |
|---|---|---|---|
| Una plantilla distinta por idioma (`welcome-en`) | Cero cambios de modelo | El producto tiene que conocer los idiomas disponibles; las claves se duplican | Peor contrato para los productos |
| Catálogo de textos (i18n por claves) dentro de una sola plantilla | Un solo HTML | Más lógica en el motor (contra ADR-0011) | Contradice el modelo *logic-less* |
| Dejarlo para la fase 2 | Menos trabajo ahora | El owner lo quiere disponible | Descartada por el owner |

## Consecuencias

- **Positivas:** el inglés está disponible sin cambiar el contrato más adelante; el producto solo envía `locale`.
- **Negativas / trade-offs aceptados:** hay que mantener cada plantilla en dos idiomas si se quiere usar el inglés; si falta la traducción, se cae silenciosamente al español (visible en la respuesta y en la métrica `email_locale_fallback_total`).

## Criterio de revisión

Necesidad de más de 3 idiomas, o de textos compartidos entre plantillas (→ parciales controlados).
