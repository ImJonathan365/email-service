# ADR-0012 — Supresión global para rebotes y quejas, por tenant para bajas manuales

- **Estado:** Accepted (owner, 2026-10-05)
- **Fecha:** 2026-10-05
- **Autor:** agente: Claude (auditoría de documentación)
- **Requisitos relacionados:** FR-10, FR-17, FR-18, FR-29, NFR-21; complementa ADR-0008 (no supersede ningún ADR: la semántica de supresión solo estaba en AC-10.2)

## Contexto

AC-10.2 decía: "La supresión es **por tenant**, no global". Las verificaciones del 2026-10-05 muestran que esa premisa es falsa con el proveedor elegido:

- En Resend la lista de supresión es **de todo el equipo**: "Any address added to the suppression list will be skipped across all your domains and subdomains". Se alimenta automáticamente con rebotes duros y quejas, y una dirección borrada sin corregir la causa vuelve a suprimirse sola ([Resend — Email suppressions](https://resend.com/docs/dashboard/emails/email-suppressions)).
- Resend emite `email.suppressed`, `suppression.added` y `suppression.removed` ([tipos de evento](https://resend.com/docs/dashboard/webhooks/event-types)); ninguno estaba mapeado, así que un mensaje saltado por el proveedor se quedaba en `SENT` indefinidamente.

Además:
- un rebote duro es un hecho sobre la **dirección** (no existe), no sobre el tenant;
- `cc` y `bcc` no se comprobaban;
- un tenant podía borrar un rebote duro y volver a dañar la reputación compartida.

## Decisión

1. **Alcance por motivo:**

| Motivo | Alcance | Origen |
|---|---|---|
| `HARD_BOUNCE` | GLOBAL | `email.bounced` |
| `COMPLAINT` | GLOBAL | `email.complained` |
| `PROVIDER` | GLOBAL | `email.suppressed`, `suppression.added` (espejo de la lista del proveedor) |
| `MANUAL` | TENANT | `POST /v1/suppressions` (p. ej., "el usuario pidió no recibir avisos de este producto") |

2. **Espejo:** `suppression.removed` elimina la fila `PROVIDER` correspondiente; las filas `HARD_BOUNCE`/`COMPLAINT` las elimina solo un administrador (`DELETE /admin/v1/suppressions/{email}`), que además debe retirarla en el panel del proveedor.
3. **Comprobación** sobre `to`, `cc` y `bcc`, al aceptar y otra vez justo antes de enviar. Un `to` suprimido produce `FAILED/SUPPRESSED`; los `cc`/`bcc` suprimidos se descartan y se informan en `droppedRecipients`.
4. **Privacidad:** clave de búsqueda `email_hash = HMAC-SHA256(SUPPRESSION_HASH_KEY, email normalizado)`; la dirección en claro es opcional y se anula con un borrado por titular (FR-29), de modo que se sigue sin escribirle sin conservar su dirección. Los tenants ven las supresiones globales enmascaradas y sin el tenant de origen.
5. **Correos críticos a quien se quejó:** no hay excepción. Aunque quisiéramos permitir una recuperación de contraseña a alguien que marcó como spam otro correo, el proveedor la saltaría igual (lista de equipo). Se documenta como comportamiento conocido; el producto debe ofrecer otro canal de recuperación si es crítico.

## Alternativas consideradas

| Alternativa | A favor | En contra | Motivo de descarte |
|---|---|---|---|
| Todo por tenant (decisión anterior) | Aislamiento conceptual entre productos | Falso con Resend; los mensajes saltados quedan en `SENT` | Contradice la realidad del proveedor |
| Todo global | Lo más simple | Una baja manual de un producto bloquearía a los demás | `MANUAL` debe seguir siendo por tenant |
| Una cuenta (equipo) de Resend por producto, con supresión por tenant real | Aislamiento real de quejas y reputación | 4 cuentas, 4 dominios verificados por cuenta, 4 claves de proveedor (la configuración pasa a ser por tenant) y, con Pro, hasta 80 USD/mes | Pregunta abierta para el owner; la decisión conservadora (global) funciona con una o con varias cuentas |

## Consecuencias

- **Positivas:** el modelo refleja lo que el proveedor ya hace; no quedan mensajes colgados en `SENT`; se protege la reputación compartida.
- **Negativas / trade-offs aceptados:** una queja contra un producto bloquea los correos de todos los productos a esa dirección (impuesto por el proveedor); se necesita un secreto más (`SUPPRESSION_HASH_KEY`), que no se puede rotar sin recalcular los hashes, y para eso hace falta la dirección en claro.

## Criterio de revisión

Si se adopta una cuenta de proveedor por producto, `HARD_BOUNCE` sigue siendo global y `COMPLAINT`/`PROVIDER` pasan a ser por tenant. También se revisa si el proveedor ofrece listas de supresión por dominio.
