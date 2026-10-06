# ADR-0011 — Plantillas versionadas, logic-less y con escapado por contexto, linter y lista blanca de helpers

- **Estado:** Accepted (owner, 2026-10-05)
- **Fecha:** 2026-10-05
- **Autor:** agente: Claude (auditoría de documentación)
- **Supersede:** ADR-0005
- **Requisitos relacionados:** FR-04, FR-05, FR-06, FR-07 (AC-07.5, AC-07.8), FR-33, FR-36, NFR-15

## Contexto

ADR-0005 decidió plantillas en la BD, versionadas, inmutables al publicar, fijadas por mensaje y con Handlebars *logic-less* y `{{{ }}}` prohibido. Las tres primeras decisiones se **mantienen**. La auditoría encontró que la protección contra inyección y XSS no estaba cerrada:

1. Handlebars también permite salida sin escapar con `{{& variable}}` ([README de handlebars.java](https://github.com/jknack/handlebars.java)).
2. Handlebars.java registra por defecto helpers como `partial`, `block`, `embedded`, `precompile`, `i18n` y `log`. `partial` y `embedded` cargan contenido a través del `TemplateLoader`, y la librería ha corregido vulnerabilidades de *path traversal* en sus cargadores ([releases 4.5.x](https://github.com/jknack/handlebars.java/releases)).
3. Los *value resolvers* por reflexión (`JavaBeanValueResolver`, `MethodValueResolver`) permiten invocar getters o métodos públicos de los objetos del contexto.
4. El escapado HTML no protege URL (`href="javascript:…"`, o un dominio de phishing), CSS ni atributos sin comillas.
5. Se aplicaba escapado HTML al asunto y al texto plano, lo que corrompe caracteres (`&amp;`).
6. La misma API key del producto podía publicar plantillas, así que una key filtrada publicaba contenido arbitrario.

## Decisión

1. Se mantiene: plantillas en BD, versiones `DRAFT`/`PUBLISHED`/`ARCHIVED`, inmutables al publicar y fijadas por mensaje (`template_version_id`).
2. **Motor:** Handlebars.java endurecido.
   - Se crea con un `TemplateLoader` que **no carga nada**.
   - Se **eliminan** todos los helpers por defecto y se registran solo: `if`, `unless`, `each`, `with`, `formatDate`, `formatNumber` y `formatMoney`.
   - El contexto es siempre un `Map` construido desde JSON y se usa **solo** `MapValueResolver`.
3. **Validación sintáctica al guardar:** se rechazan `{{{`, `{{&`, `{{>`, `{{#>` y cualquier helper o identificador de bloque fuera de la lista blanca → `422 UNSAFE_TEMPLATE_CONSTRUCT`.
4. **Linter de contexto HTML** (con un parser HTML real, p. ej. jsoup):
   - prohíbe variables dentro de `<script>`, `<style>`, atributos `style`, atributos `on*` y atributos sin comillas;
   - prohíbe las etiquetas `<script>`, `<iframe>`, `<object>`, `<embed>` y `<form>`;
   - exige que toda variable en `href`/`src` esté declarada con `format: uri`.
5. **Escapado por contexto:** HTML en `htmlTemplate`; ninguno en `subjectTemplate` y `textTemplate` (el asunto además pierde CR/LF).
6. **URL en variables:** en la aceptación, toda variable `format: uri` debe ser `https` y su host debe pertenecer a `tenant.allowedLinkHosts` → si no, `422 UNSAFE_URL`.
7. **Esquema obligatorio para publicar**, con un subconjunto de JSON Schema definido (`06` §3.3), incluido `x-sensitive` para las variables que se purgan al enviar y se redactan en el contenido guardado.
8. **Render determinista:** no hay helper de "fecha actual"; formateo con `tenant.locale`/`tenant.timezone` explícitos.
9. **Render también al aceptar** (resultado descartado): los errores de render son `422` síncronos.
10. **Gestión de plantillas solo con el ámbito `templates:write`** (FR-33), que nunca lleva la key desplegada en un producto.
11. **Seguimiento de aperturas y clics** solo en la categoría `NOTICE`, y desactivado por defecto.
12. **Límites:** fuente HTML ≤ 256 KB; aviso si el HTML renderizado supera 100 KB (recorte de Gmail, cifra a confirmar).

## Alternativas consideradas

| Alternativa | A favor | En contra | Motivo de descarte |
|---|---|---|---|
| jmustache (Mustache puro) | Sin helpers ni parciales por defecto; superficie menor | También tiene `{{& }}`; sin helpers de formato habría que preformatear todo en el producto; menos validación sintáctica | Opción de reserva si Handlebars.java deja de mantenerse |
| Sanitizar el HTML renderizado (OWASP Java HTML Sanitizer) | Defensa en profundidad | Puede alterar diseños de correo legítimos (tablas, estilos en línea); coste por mensaje | No se adopta en el MVP; el linter al publicar cubre el riesgo |
| Plantillas en el repositorio (archivos) | Revisión en PR | Cada cambio de texto exige desplegar | Igual que en ADR-0005 |
| Thymeleaf o FreeMarker | Más potentes | Evalúan expresiones (SSTI) | Igual que en ADR-0005 |

## Consecuencias

- **Positivas:** una key filtrada del producto ya no puede publicar contenido; la inyección de URL queda acotada a hosts del propio producto; asunto y texto sin corrupción; superficie del motor mínima.
- **Negativas / trade-offs aceptados:**
  - las plantillas no pueden compartir parciales (cabecera y pie se repiten en cada plantilla; se acepta, son pocas);
  - el linter es código propio que hay que probar (cobertura ≥ 90 %, NFR-12);
  - Handlebars.java tiene una cadencia de publicación baja (la última serie es la 4.5.x; fecha exacta de la última versión a confirmar).

## Criterio de revisión

- Handlebars.java sin versiones en 24 meses o con una CVE sin parche en 30 días → migrar a jmustache.
- Necesidad de i18n por locale o de parciales compartidos (→ diseñar una composición controlada).
- Edición de plantillas por personas no técnicas (→ panel, fase 1).
