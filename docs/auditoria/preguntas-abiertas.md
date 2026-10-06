# Preguntas abiertas para el owner — auditoría del 2026-10-05

**Estado:** respondidas por el owner el 2026-10-05. Quedan pendientes solo los puntos legales (que **no** bloquean el desarrollo) y las decisiones de hosting, que se toman al tener el MVP.

| # | Pregunta | Respuesta del owner | Qué cambió | Estado |
|---|---|---|---|---|
| 1 | ¿Aceptas los ADR-0008 a 0017? | Acepta | Aceptados: 0008–0014, 0016 y 0017. El 0015 se rechaza por la respuesta 2 | Cerrada |
| 2 | ¿Chinamo/Colmena envían comprobantes con XML + PDF? | No envían | FR-30 → Won't; ADR-0015 Rejected; `email_attachment` no se crea; las exportaciones van como enlace firmado | Cerrada |
| 3 | ¿Una cuenta de Resend por producto o compartida? | Acepta la propuesta | Una cuenta compartida en producción y otra para staging; supresión global (ADR-0012) | Cerrada |
| 4 | ¿`sendAt` en el MVP? | Sí | FR-31 → Should (hito H8) | Cerrada |
| 5 | Volumen y cuota por tenant | Acepta | `dailyQuota` = 1 000 por defecto, ajustable por tenant | Cerrada |
| 6 | ¿IP de salida estables para `allowedCidrs`? ¿Hosting? | Se define con el MVP; acepta la propuesta | `allowedCidrs` vacío por defecto; red privada cifrada o TLS obligatorio | Pendiente (no bloquea) |
| 7 | ¿El PostgreSQL gestionado permite `BYPASSRLS`? | De acuerdo con la alternativa | Si no lo permite: política `TO email_system USING (true)`. Comprobar al elegir hosting | Pendiente (no bloquea; en local no hay problema) |
| 8 | ¿Usuarios en la UE o EE. UU.? | Solo Costa Rica | GDPR y CAN-SPAM no aplican (NFR-20, `11` §5) | Cerrada |
| 9 | ¿El aviso de privacidad de cada producto cubre la transferencia al proveedor en EE. UU.? | No la comprende; pendiente si no bloquea | Explicación abajo. Marcada como requisito de **salida a producción**, no de desarrollo (`08` §10) | Pendiente (no bloquea el desarrollo) |
| 10 | ¿Los servicios nuevos seguirán siendo Java? | Sí, todos son Spring Boot | Se confirma ADR-0009 | Cerrada |
| 11 | ¿TypeScript en algún producto? | Sí, en los frontends | Puntuación de cartera de Node confirmada (ADR-0009) | Cerrada |
| 12 | ¿Colmena trata datos de los clientes de sus clientes? | Sí (productos, deudas) | Colmena = encargada; email-service y Resend = subencargados; `fromName` por negocio (AC-09.5); montos de deuda `x-sensitive`; normativa de cobro a confirmar con asesoría (NFR-20) | Cerrada (con un punto legal pendiente que no bloquea) |
| 13 | ¿Canal alternativo para correos críticos a quien se quejó? | Mejor a una dirección específica | La respuesta lleva `suppressionReason`; el producto reintenta a otra dirección registrada del usuario (AC-10.1, `07` §12) | Cerrada |
| 14 | Idioma | Todo en español, pero con el inglés implementado y configurable | Versiones de plantilla por idioma (`es-CR`, `en`), `locale` por mensaje con caída al español (FR-37, ADR-0018) | Cerrada |
| 15 | Recorte de Gmail y retención del proveedor | Acepta | Sin cambios (explicación abajo) | Cerrada |
| 16 | Plazo de la Fase 0 | Sin problema | Sin cambios | Cerrada |

## Aclaraciones

**Pregunta 9, en palabras simples.** Cuando un producto manda un correo a través del email-service, la dirección y el contenido del correo terminan en los servidores de Resend, en EE. UU. La Ley 8968 trata eso como una "transferencia internacional" de datos personales y pide que la persona lo haya autorizado. En la práctica basta con que la política de privacidad o los términos de cada producto (los que el usuario acepta al registrarse) digan algo como: "usamos proveedores externos, algunos fuera de Costa Rica, para enviarte correos; les compartimos tu correo electrónico y el contenido del mensaje solo para eso". En Colmena, como envía correos a los clientes de sus negocios, lo deben cubrir también los términos de Colmena con cada negocio. **Es un texto legal, no código**: no bloquea el desarrollo, pero conviene tenerlo antes de enviar correos reales a usuarios reales. Confírmalo con un abogado.

**Pregunta 15.** (a) Gmail recorta los correos con HTML muy grande (alrededor de 100 KB) y muestra "ver mensaje completo": por eso la previsualización avisa al pasar de 100 KB. (b) Resend guarda una copia de cada correo enviado durante unos 30 días; importa para el borrado por titular (`08` §6). Ninguna de las dos cosas requiere decisión.
