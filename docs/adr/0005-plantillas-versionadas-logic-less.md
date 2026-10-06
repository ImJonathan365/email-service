# ADR-0005 — Plantillas versionadas, inmutables al publicar y logic-less

- **Estado:** Superseded by ADR-0011 (2026-10-05)
- **Fecha:** 2026-09-20
- **Requisitos relacionados:** FR-04, FR-05, FR-06, NFR-15

## Contexto
Cada producto tiene su propia plantilla (logo, colores, estilo). Las plantillas viven en la base de datos para poder cambiarlas sin desplegar. Eso plantea dos riesgos: (a) que un cambio rompa correos en curso o haga irreproducible lo que se envió, y (b) que una plantilla dinámica se convierta en un vector de ejecución de código (SSTI) o de XSS.

## Decisión
1. Plantilla identificada por `key` dentro del tenant, con versiones numeradas.
2. Las versiones `DRAFT` se pueden editar; al publicarlas pasan a `PUBLISHED` y son **inmutables**. Publicar una nueva archiva la anterior, que sigue siendo utilizable si un mensaje la referencia.
3. Al aceptar un envío se **fija** `template_version_id`: un reintento renderiza exactamente lo mismo, y el historial es reproducible.
4. Motor **logic-less** (Handlebars.java) con escapado HTML por defecto; la interpolación sin escapar (`{{{ }}}`) está prohibida y se rechaza al validar la plantilla.
5. `variablesSchema` opcional por versión permite validar las variables antes de aceptar el envío.
6. El contenido renderizado se almacena solo si el tenant activa `storeRenderedContent` (privacidad).

## Alternativas consideradas
| Alternativa | A favor | En contra | Motivo de descarte |
|---|---|---|---|
| Plantillas en el repositorio (archivos) | Versionadas con git, revisables en PR | Cada cambio de copy exige un despliegue; no sirve si algún día las edita otra persona | Descartada (se puede mantener un export a git como respaldo) |
| Plantillas en el proveedor (Resend/Postmark) | Menos código propio | Ata el contenido al proveedor; dificulta migrar y versionar | Contradice ADR-0003 |
| Thymeleaf o FreeMarker | Más potentes | Evalúan expresiones: superficie de SSTI con plantillas de origen dinámico | Descartada por seguridad |
| Versiones mutables | Más cómodo | Historial irreproducible; un error de edición afecta a envíos en curso | Descartada |

## Consecuencias
- Positivas: historial reproducible; cambios de plantilla sin desplegar; superficie de inyección mínima.
- Negativas: publicar una corrección exige crear una versión nueva (deseado); la validación de variables es un subconjunto de JSON Schema, no el estándar completo.

## Criterio de revisión
Necesidad de i18n por locale, de composición de parciales compartidos entre plantillas o de edición por personas no técnicas (→ panel, fase 1).

---
> **Nota de estado (2026-10-05):** de este ADR solo se ha modificado la línea de *Estado*; su texto aceptado no se edita. El owner aceptó ADR-0011, que lo sustituye, el 2026-10-05.
