# ADR-0020 — Publicación conjunta de las versiones de varios idiomas

- **Estado:** Accepted (owner, 2026-10-07)
- **Fecha:** 2026-10-07
- **Autor:** agente: Claude (H3)
- **Supersede:** — (complementa ADR-0018)
- **Requisitos relacionados:** FR-05, FR-37 (AC-37.6, AC-37.8), FR-22

## Contexto

ADR-0018 exige que todas las versiones publicadas de una plantilla, una por idioma, declaren las mismas variables `required` (AC-37.6), y en H3 se publicaba una versión cada vez. Juntas, esas dos reglas impiden cambiar el conjunto `required` de una plantilla que ya tiene varios idiomas publicados: al publicar el primero, choca con el otro, que sigue publicado con el conjunto anterior. Se reprodujo contra el servicio: `required: [a]` → `[a, b]` con `es-CR` y `en` publicados da `422` en los dos.

## Decisión

1. Nuevo endpoint `POST /v1/templates/{key}/publish` (ámbito `templates:write`) con cuerpo `{ "versions": { "es-CR": 5, "en": 3 } }`: un borrador por idioma.
2. Las versiones indicadas se publican en **una sola transacción**, con la fila de la plantilla bloqueada:
   - cada versión se valida igual que al publicarla sola: es un borrador, su `locale` coincide con la clave, tiene esquema (AC-05.5) y declara sus variables URL como `uri` (AC-04.8);
   - AC-37.6 se comprueba sobre el **conjunto resultante**: las versiones nuevas más las publicadas de los idiomas que no se tocan;
   - se archiva la versión publicada anterior de cada idioma;
   - se audita una entrada `TEMPLATE_PUBLISHED` por versión;
   - si cualquier comprobación falla, no se publica ninguna.
3. `POST /v1/templates/{key}/versions/{version}/publish` se mantiene para los cambios que no alteran `required`; comparte el mismo código.
4. `scripts/publish-template.sh` usa el endpoint conjunto siempre que cambie más de un idioma.

## Alternativas consideradas

| Alternativa | A favor | En contra | Motivo de descarte |
|---|---|---|---|
| Aceptar la limitación y crear otra `key` | Sin código nuevo | Cada cambio de variables obliga a migrar la `key` en los productos | Coste recurrente para el owner y los productos |
| Relajar AC-37.6 durante una ventana | Sin endpoint nuevo | Deja temporalmente idiomas incompatibles publicados; un envío entre medias puede fallar | Rompe la garantía que AC-37.6 protege |
| Despublicar un idioma | Simple | Deja la plantilla sin ese idioma mientras tanto | Pérdida de servicio evitable |

## Consecuencias

- **Positivas:** se pueden cambiar las variables obligatorias de cualquier plantilla multilingüe sin cambiar su `key`; el script nunca deja una publicación a medias.
- **Negativas / trade-offs aceptados:** un endpoint más en el contrato público (compatible, NFR-18).

## Criterio de revisión

Si llega a haber plantillas con muchos idiomas (> 5) o publicaciones lentas por el bloqueo de la fila de la plantilla.
