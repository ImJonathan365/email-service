# ADR-0003 — Proveedor transaccional por API (Resend) y SMTP solo en desarrollo

- **Estado:** Superseded by ADR-0016 (2026-10-05)
- **Fecha:** 2026-09-20
- **Requisitos relacionados:** FR-14, FR-15, NFR-05

## Contexto
Enviar por SMTP con usuario y contraseña de una cuenta de correo (Gmail, cPanel, etc.) obliga a custodiar esas credenciales, no ofrece webhooks de rebotes ni aperturas, no da control de tasa, y la reputación de envío queda fuera de control. Un servicio centralizado necesita exactamente lo contrario.

## Decisión
1. En producción se envía **por la API HTTP de un proveedor transaccional**. Proveedor elegido para el MVP: **Resend** (comparativa y criterios en `04-stack-tecnologico.md` §5).
2. Toda la integración vive detrás de la interfaz `EmailSender`, con implementaciones `ResendEmailSender`, `SmtpEmailSender` (Mailpit) y `NoopEmailSender` (tests), seleccionadas por `MAIL_PROVIDER`.
3. Ningún tipo, excepción o cabecera del SDK del proveedor cruza esa frontera.
4. SMTP queda **solo** para desarrollo local. Si `APP_ENV=production` y `MAIL_PROVIDER=smtp`, la aplicación no arranca salvo `ALLOW_SMTP_IN_PRODUCTION=true` (escape documentado, no recomendado).
5. Un solo proveedor activo por entorno; el failover automático es fase 3.

## Alternativas consideradas
| Alternativa | A favor | En contra | Motivo de descarte |
|---|---|---|---|
| SMTP de un proveedor transaccional (mismo proveedor, vía SMTP) | Cambiar de proveedor es trivial | Se pierden webhooks e identificadores de mensaje; peor manejo de errores | Contradice el objetivo de trazabilidad |
| Postmark como proveedor inicial | Mejor deliverability y soporte del sector | Plan gratuito de 100 correos/mes; coste por millar más alto | Reservado como alternativa si la entrega crítica pesa más que el costo |
| Amazon SES desde el inicio | El más barato a volumen | Sandbox inicial, webhooks vía SNS, gestión propia de reputación | Fricción alta para un MVP; migración prevista a partir de ~200.000 correos/mes |

## Consecuencias
- Positivas: webhooks de eventos, identificadores de mensaje, control de tasa y mejor manejo de errores; sin credenciales de cuentas de correo personales; cambio de proveedor = una clase nueva + variables de entorno.
- Negativas: dependencia de un tercero y de su API (mitigada por la interfaz); el modo local no reproduce webhooks (aceptado: los tests los simulan).

## Criterio de revisión
Cambio de proveedor según los disparadores de `04` §5 (deliverability crítica → Postmark; > 200.000 correos/mes → SES) o una caída del proveedor con impacto real (→ evaluar failover, fase 3).

---
> **Nota de estado (2026-10-05):** de este ADR solo se ha modificado la línea de *Estado*; su texto aceptado no se edita. El owner aceptó ADR-0016, que lo sustituye, el 2026-10-05.
