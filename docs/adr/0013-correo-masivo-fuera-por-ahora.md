# ADR-0013 — Correo masivo/marketing: todavía no; y cuando llegue, en una cuenta y un stream de proveedor separados

- **Estado:** Accepted (owner, 2026-10-05)
- **Fecha:** 2026-10-05
- **Autor:** agente: Claude (auditoría de documentación)
- **Requisitos relacionados:** FR-34, FR-35, FR-36, NFR-20, NFR-21, FR-40 a FR-54 (`11-correo-masivo.md`)

## Contexto

Se pidió evaluar incorporar envío masivo: boletines, avisos a todos los clientes de un producto y campañas. El riesgo que importa no es de código sino de **reputación de envío**: que una campaña con muchas quejas arruine la entregabilidad de los correos críticos (verificación de cuenta, recuperación de contraseña, confirmación de pago).

Hechos verificados el 2026-10-05:

- **Gmail:** es remitente masivo quien envía cerca de 5 000 mensajes o más a cuentas personales de Gmail en 24 h; los subdominios del mismo dominio principal **cuentan juntos**, y el estado de remitente masivo **no caduca**. Tasa de spam recomendada < 0,1 %; ≥ 0,3 % tiene consecuencias. Baja de un clic (RFC 8058) en mensajes comerciales y bajas atendidas en 48 h. Desde noviembre de 2025, rechazos temporales y permanentes al tráfico que no cumple ([Gmail sender guidelines FAQ](https://support.google.com/mail/answer/14229414?hl=en)).
- **Yahoo:** SPF y DKIM, DMARC al menos `p=none` que pase, spam < 0,3 %, `List-Unsubscribe` con un clic y bajas en 2 días ([Yahoo Sender Hub](https://senders.yahooinc.com/best-practices/)).
- **Microsoft (Outlook.com):** desde mayo de 2025, los remitentes de > 5 000 correos/día deben tener SPF, DKIM y DMARC alineados; si no, son rechazados ([resumen](https://powerdmarc.com/microsoft-sender-requirements/)).
- **Resend:**
  - la lista de supresión es de **todo el equipo** ([supresiones](https://resend.com/docs/dashboard/emails/email-suppressions));
  - en los planes sin IP dedicada se envía desde IP compartidas, y la IP dedicada exige > 3 000 correos/día ([IP dedicadas](https://resend.com/docs/knowledge-base/how-do-dedicated-ips-work));
  - ofrece un producto de marketing (*Broadcasts*) facturado por contactos: gratis hasta 1 000 y "Pro Marketing" desde 40 USD/mes por 5 000 ([pricing KB](https://resend.com/docs/knowledge-base/what-is-resend-pricing)).
- **Postmark** separa transaccional y *broadcast* en infraestructuras e IP distintas: "transactional and broadcast traffic never intersect in Postmark, including IP ranges" ([Message Streams](https://postmarkapp.com/message-streams)).

## Decisión

1. **No se incorpora correo masivo ni de marketing al email-service por ahora.** Se mantiene el carácter transaccional.
2. **La separación de reputación se hace en el proveedor, no en el código.** La reputación se decide por:
   - el dominio que firma (DKIM `d=`) y el dominio principal;
   - las IP de salida;
   - la cuenta del proveedor (su lista de supresión y su revisión de abuso).

   Un microservicio aparte que envíe por la **misma** cuenta de Resend no protege nada; el mismo servicio enviando por una cuenta o un *stream* separado sí.
3. **Política provisional:**
   - mientras no se cumpla el disparador, un producto que necesite un aviso a todos sus clientes lo hace con la herramienta de *broadcast* del proveedor, en una **cuenta distinta** de la transaccional y desde un subdominio dedicado (`news.<dominio>`), nunca a través de `/v1/emails`;
   - el endpoint de lote (FR-35) admite avisos `NOTICE` operativos (mantenimiento, cambios de términos) dentro de la cuota diaria del tenant, con la prioridad más baja y con detección de anomalías (FR-34): no es una herramienta de campañas;
   - todo aviso a más de `dailyQuota` destinatarios va por la vía de *broadcast*.
4. **Disparador concreto** para construirlo (basta cualquiera):
   - **(a)** un producto necesita enviar comunicaciones no transaccionales a ≥ 1 000 destinatarios más de una vez al mes durante 2 meses seguidos;
   - **(b)** dos o más productos necesitan campañas y la herramienta del proveedor no cubre el **registro de consentimiento con prueba** que exige la normativa (`11` §4);
   - **(c)** el coste del producto de marketing del proveedor supera 100 USD/mes durante 3 meses.
5. **Si se construye**, el diseño obligatorio de `11-correo-masivo.md`:
   - cuenta de proveedor o *stream* separado;
   - subdominio propio con SPF, DKIM y DMARC alineados;
   - tablas y cola propias (`campaign`, `campaign_message`);
   - rol de worker propio (`APP_ROLE=bulk-worker`) con su propio límite de tasa y ventanas horarias;
   - claves con un ámbito propio (`campaigns:write`);
   - consentimiento, doble opt-in, `List-Unsubscribe` + `List-Unsubscribe-Post`;
   - aprobación previa de campañas grandes y pausa automática por quejas.

   Puede vivir en el mismo repositorio y artefacto como módulo `campaigns` con su propio rol; desplegarlo como servicio separado no aporta reputación y sí costo.

## Alternativas consideradas

| Alternativa | A favor | En contra | Motivo de descarte |
|---|---|---|---|
| Añadir campañas al servicio actual, misma cuenta del proveedor | Rápido | Una campaña con quejas suprime direcciones y expone la cuenta entera, incluidas las recuperaciones de contraseña | Riesgo directo sobre el correo crítico |
| Servicio de marketing separado, misma cuenta del proveedor | "Separación" aparente | No separa nada de lo que deciden los buzones ni el proveedor | Coste sin beneficio |
| Producto de marketing del proveedor desde cada producto (provisional) | Cero código; baja de un clic y contactos resueltos | Consentimiento y segmentación limitados a lo que ofrezca; coste por contactos | **Adoptado como política provisional** |
| Postmark *Broadcast stream* para masivo y Resend para transaccional | IP separadas por diseño | Dos proveedores que integrar | Candidato preferente si se construye (dentro del mismo `EmailSender`, distinta configuración) |

## Consecuencias

- **Positivas:** el correo crítico no comparte cuenta, supresión ni IP con campañas; cero desarrollo hasta que exista una necesidad medida; el diseño futuro ya está escrito.
- **Negativas / trade-offs aceptados:**
  - los avisos masivos de hoy pasan por una herramienta externa y una cuenta adicional;
  - el estado de "remitente masivo" de Gmail se cuenta por dominio principal, así que un producto que haga campañas desde un subdominio de su dominio principal puede convertir en remitente masivo también a su dominio transaccional. Mitigación: cumplir los requisitos de remitente masivo **también** en el transaccional (DMARC alineado, quejas < 0,1 %), que ya exige NFR-21.

## Criterio de revisión

Cualquiera de los disparadores del punto 4, un cambio en las políticas de supresión o de cuentas del proveedor, o la entrada en vigor de la Ley 10946 (prevista para junio de 2027 según fuentes secundarias, **a confirmar**) si obliga a centralizar el registro de consentimiento.
