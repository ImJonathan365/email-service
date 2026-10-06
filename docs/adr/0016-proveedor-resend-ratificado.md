# ADR-0016 — Proveedor transaccional: Resend ratificado, con datos verificados y disparadores medibles

- **Estado:** Accepted (owner, 2026-10-05)
- **Fecha:** 2026-10-05
- **Autor:** agente: Claude (auditoría de documentación)
- **Supersede:** ADR-0003
- **Requisitos relacionados:** FR-14, FR-15, FR-16, NFR-04, NFR-05, NFR-16, NFR-21

## Contexto

ADR-0003 eligió Resend por API (SMTP solo en desarrollo) detrás de `EmailSender`. Esa decisión se **mantiene**. Pero la comparativa de `04` §5 tenía datos erróneos y le faltaban hechos que cambian el diseño.

### Datos verificados el 2026-10-05

| Dato | Valor | Fuente |
|---|---|---|
| Resend gratis | 3 000/mes, 100/día, 3 dominios, 1 endpoint de webhook | [pricing](https://resend.com/pricing) |
| Resend Pro | 20 USD/mes por 50 000 (35 USD por 100 000); 10 dominios; 5 endpoints; excedente 0,90 USD/1 000 | [pricing](https://resend.com/pricing), [KB](https://resend.com/docs/knowledge-base/what-is-resend-pricing) |
| Resend Scale | 90 USD/mes por 100 000; hasta 2,5 M por 1 150 USD; IP dedicada como complemento de 30 USD/mes | Ídem |
| Resend 500 000/mes | **A confirmar** (no se encontró el tramo exacto) | — |
| Límite de tasa de Resend | 10 peticiones/s por equipo (por defecto) | [API reference](https://resend.com/docs/api-reference/introduction) |
| Idempotencia de Resend | `Idempotency-Key`, ≤ 256 caracteres, 24 h; `409` si el cuerpo cambia | [Idempotency keys](https://resend.com/docs/dashboard/emails/idempotency-keys) |
| Webhooks de Resend | Firmados con Svix (`svix-id`, `svix-timestamp`, `svix-signature`), secreto por endpoint, cuerpo crudo | [Verify webhooks](https://resend.com/docs/dashboard/webhooks/verify-webhooks-requests) |
| Supresión de Resend | De todo el equipo, automática por rebote duro y queja | [Suppressions](https://resend.com/docs/dashboard/emails/email-suppressions) |
| IP dedicada de Resend | Requiere > 3 000 correos/día | [Dedicated IPs](https://resend.com/docs/knowledge-base/how-do-dedicated-ips-work) |
| Postmark | Gratis 100/mes; 10 000 por 15 / 16,50 / 18 USD (Basic/Pro/Platform); a 50 000: 87 / 68,50 / 66 USD; *streams* transaccional y *broadcast* con IP separadas | [Postmark pricing](https://postmarkapp.com/pricing), [Message Streams](https://postmarkapp.com/message-streams) |
| Amazon SES | 0,10 USD/1 000 (el documento anterior decía 0,16); 0,12 USD/GB de adjuntos; hasta 200 USD de créditos para cuentas nuevas | [SES pricing](https://aws.amazon.com/ses/pricing/) |

## Decisión

1. **Resend** sigue siendo el proveedor del MVP, por API, detrás de `EmailSender` + `WebhookVerifier`. SMTP (Mailpit) solo en desarrollo, con *fail-fast* en producción (sin cambios respecto a ADR-0003, puntos 2–5).
2. **Se usan las capacidades verificadas:** `Idempotency-Key = message.id` (ADR-0010), verificación Svix sobre el cuerpo crudo, mapeo completo de eventos (`02` §2.1) y espejo de la supresión del equipo (ADR-0012).
3. **Cuentas:** un equipo de Resend para producción y **otro** para staging (límite, supresión y reputación separados). Todos los productos comparten el equipo de producción (riesgo aceptado; pregunta abierta sobre un equipo por producto).
4. **Plan:** gratuito mientras se integra el primer producto; **Pro** desde que haya más de un producto en producción, porque el límite de 100/día del plan gratuito lo agota cualquier pico y bloquea los correos `SECURITY` del resto del día.
5. **Disparadores de cambio**, medibles (sustituyen a los de ADR-0003):
   - **A Postmark** si ocurre cualquiera de estos:
     - (a) los eventos `email.delivery_delayed` + `email.bounced` atribuibles al proveedor (no a direcciones malas) superan el 2 % durante 7 días;
     - (b) un incidente de cuenta (suspensión o revisión) interrumpe el envío transaccional;
     - (c) se decide construir correo masivo propio (ADR-0013) y se quiere separación de IP por *stream* en un solo proveedor.
   - **A Amazon SES** cuando el ahorro mensual verificado supere 150 USD durante 3 meses seguidos. Referencia: a 200 000/mes, SES ≈ 20 USD frente a ≥ 90 USD en Resend (tramo exacto a confirmar). Se exige también presupuesto de tiempo para operar SNS/EventBridge y la reputación propia.
   - **Failover multi-proveedor:** solo tras una caída real del proveedor con impacto medido (correos `SECURITY` retrasados > 30 min).

## Alternativas consideradas

| Alternativa | A favor | En contra | Motivo de descarte |
|---|---|---|---|
| Postmark como inicial | Separación transaccional/*broadcast* por IP; reputación de referencia | 100 correos/mes gratis; a 50 000/mes cuesta 66–87 USD frente a 20 USD | Coste 3–4× para el volumen actual |
| Amazon SES como inicial | El más barato a volumen | Sandbox, SNS/EventBridge y reputación propia; hasta 200 USD en créditos, no gratis permanente | Fricción alta para el MVP |
| SMTP de un proveedor transaccional | Cambio de proveedor trivial | Sin idempotencia por API ni identificadores fiables | Igual que en ADR-0003 |

## Consecuencias

- **Positivas:** decisión basada en datos verificados; la idempotencia del proveedor cierra la ventana de duplicados; los disparadores se pueden medir con las métricas de FR-24.
- **Negativas / trade-offs aceptados:** una cuenta compartida por todos los productos (supresión, límite y revisión comunes); dependencia de funcionalidades específicas de Resend (idempotencia, Svix) encapsuladas tras los puertos.

## Criterio de revisión

Los disparadores del punto 5, un cambio de precios o límites del proveedor (se revisa la página de precios cada 6 meses y antes de cambiar de plan), o la retirada de la idempotencia por API.
