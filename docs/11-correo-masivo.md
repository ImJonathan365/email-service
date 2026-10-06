# 11 — Correo masivo y marketing: decisión, disparador y diseño obligatorio

> Documento nuevo (2026-10-05). Decisión en ADR-0013. Todos los requisitos de este documento están en **Won't (MVP)** y no se implementan hasta que se cumpla el disparador del §2. Están escritos para que, llegado el momento, nadie improvise la separación de reputación ni el cumplimiento.

## 1. Recomendación

**No incorporar correo masivo todavía.** Si se incorpora, **no** por la misma cuenta del proveedor ni por el mismo dominio que el correo transaccional.

### 1.1 Por qué: reputación, no elegancia de código

Lo que deciden Gmail, Yahoo y Microsoft, y lo que vigila el proveedor, depende de tres cosas:

1. **El dominio que firma** (DKIM `d=`) y su dominio principal. Gmail cuenta los subdominios del mismo dominio principal **juntos** para decidir quién es remitente masivo (≈ 5 000 mensajes/día a cuentas personales de Gmail), y ese estado **no caduca** ([Gmail FAQ](https://support.google.com/mail/answer/14229414?hl=en)).
2. **Las IP de salida.** En Resend sin IP dedicada (que exige > 3 000 correos/día), todo sale por IP compartidas ([Resend](https://resend.com/docs/knowledge-base/how-do-dedicated-ips-work)).
3. **La cuenta del proveedor.** En Resend, la lista de supresión es de **todo el equipo** ([Resend](https://resend.com/docs/dashboard/emails/email-suppressions)), y la revisión de abuso afecta a la cuenta.

**Escenario de daño:** Colmena envía un boletín a 8 000 contactos importados de una hoja de cálculo. El 0,5 % lo marca como spam (40 quejas) y rebota un 6 %.
- Las 40 direcciones quedan suprimidas en la cuenta para **todos** los productos, así que esos usuarios dejan de recibir la recuperación de contraseña de Payverica.
- La tasa de quejas del dominio supera el 0,3 % de Gmail y empiezan los rechazos.
- El proveedor revisa la cuenta, y la cuenta es la misma que envía los comprobantes de Chinamo.

Nada de esto lo evita "otro microservicio" que envíe por la misma cuenta; lo evita **otra cuenta (o *stream*), otro subdominio y otras reglas**.

### 1.2 Mientras tanto (política provisional)

- Avisos operativos pequeños (mantenimiento, cambio de términos) a usuarios del producto: `POST /v1/emails/batch`, categoría `NOTICE`, dentro de la cuota diaria del tenant, con la prioridad más baja y con detección de anomalías (FR-34, FR-35).
- Cualquier envío mayor que la cuota diaria del tenant, o de carácter comercial: con el producto de *broadcast* del proveedor (Resend Broadcasts u otro) en una **cuenta distinta** y desde `news.<dominio-del-producto>`, gestionado por el propio producto. Referencia de coste: Resend Marketing gratis hasta 1 000 contactos y desde 40 USD/mes por 5 000 ([Resend pricing KB](https://resend.com/docs/knowledge-base/what-is-resend-pricing)).

## 2. Disparador concreto para construirlo

Basta con cualquiera:

| ID | Disparador | Cómo se mide |
|---|---|---|
| T-1 | Un producto necesita enviar comunicaciones no transaccionales a ≥ 1 000 destinatarios más de una vez al mes, durante 2 meses seguidos | Registros del producto de *broadcast* |
| T-2 | ≥ 2 productos necesitan campañas y la herramienta del proveedor no ofrece registro de consentimiento con prueba (§4.3) | Revisión de cumplimiento |
| T-3 | El producto de marketing del proveedor supera 100 USD/mes durante 3 meses | Facturación |

Al dispararse: ADR nuevo con la métrica observada y el diseño de este documento como punto de partida.

## 3. Separación obligatoria (si se construye)

| Dimensión | Transaccional (actual) | Masivo (nuevo) |
|---|---|---|
| Cuenta del proveedor | Equipo de Resend de producción | **Otra** cuenta: equipo de Resend aparte, o Postmark con un *Broadcast stream*, que usa IP separadas por diseño ([Postmark](https://postmarkapp.com/message-streams)) |
| Dominio remitente | `notify.<dominio>` o el dominio principal | `news.<dominio>` (subdominio dedicado), con SPF, DKIM y DMARC alineados. Ojo: cuenta para el umbral de Gmail del dominio principal, así que el transaccional también debe cumplir los requisitos de remitente masivo (NFR-21) |
| Política de IP | Compartidas del proveedor | Compartidas del pool de *broadcast*; dedicadas solo con > 3 000/día sostenidos y plan de calentamiento |
| Cola | `email_message` | Tablas propias `campaign`, `campaign_recipient`, `campaign_message`; nunca comparten filas ni índice de cola |
| Worker | `APP_ROLE=worker` | `APP_ROLE=bulk-worker`, con su limitador y su circuit breaker |
| Límite de tasa | 8 rps por instancia (≤ 80 % del límite de la cuenta) | Propio de su cuenta; por defecto 2 rps y ventana horaria 08:00–20:00 en la zona del tenant |
| Credenciales | Ámbitos `emails:*`, `templates:write`, `suppressions:write` | Ámbito nuevo `campaigns:write` en keys separadas; nunca en la key de producción del producto |
| Supresión | Global (rebote, queja) + tenant (manual) | Además: bajas por lista y "sin marketing" global por tenant; las quejas de campaña suprimen el **marketing**, no el transaccional |
| Plantillas | Categorías `SECURITY`, `TRANSACTIONAL`, `NOTICE` | Categoría `MARKETING`, solo enviable por el flujo de campañas; nunca por `/v1/emails` |

Código: puede vivir en el mismo repositorio y artefacto como módulo `campaigns` con su propio rol de despliegue (ADR-0001 sigue valiendo). Desplegarlo como servicio separado no aporta reputación y añade coste.

## 4. Requisitos (todos **Won't (MVP)**)

| ID | Requisito |
|---|---|
| FR-40 | Infraestructura de envío separada |
| FR-41 | Listas y contactos |
| FR-42 | Segmentos |
| FR-43 | Registro de consentimiento con prueba |
| FR-44 | Doble opt-in |
| FR-45 | `List-Unsubscribe` y `List-Unsubscribe-Post` (un clic) |
| FR-46 | Página y endpoint de baja |
| FR-47 | Supresión por lista frente a supresión global |
| FR-48 | Campañas: borrador, aprobación, programación y cancelación |
| FR-49 | Aceleración y ventanas de envío |
| FR-50 | Pruebas A/B |
| FR-51 | Métricas por campaña |
| FR-52 | Límites por plan y por tenant |
| FR-53 | Identificación comercial y contenido obligatorio |
| FR-54 | Anti-abuso específico de campañas |

### FR-40 — Infraestructura de envío separada
- **AC-40.1** — *Dado* `APP_ROLE=bulk-worker`, *cuando* la clave o la cuenta del proveedor configurada coincide con la del transaccional (`RESEND_API_KEY`), *entonces* la aplicación no arranca.
- **AC-40.2** — *Dado* un dominio remitente de campañas, *cuando* se registra, *entonces* debe ser distinto de todos los `allowedFromDomains` transaccionales del tenant y tener SPF, DKIM y DMARC verificados (comprobado en el alta con una consulta DNS).

### FR-41 — Listas y contactos
- **AC-41.1** — Un tenant crea listas (`audience`) con nombre y propósito; un contacto pertenece a 0..N listas, con `email`, atributos opcionales y estado por lista (`SUBSCRIBED`, `PENDING`, `UNSUBSCRIBED`, `CLEANED`).
- **AC-41.2** — La importación masiva solo acepta contactos con evidencia de consentimiento (FR-43); las filas sin evidencia se rechazan con un informe por fila.
- **AC-41.3** — Aislamiento por RLS igual que en el resto del servicio.

### FR-42 — Segmentos
- **AC-42.1** — Un segmento es un filtro guardado sobre atributos y actividad (p. ej., "abrió en 90 días"), evaluado al crear el envío y congelado en `campaign_recipient`.
- **AC-42.2** — Un segmento nunca incluye contactos `UNSUBSCRIBED`, `CLEANED`, suprimidos ni con la marca global "sin marketing".

### FR-43 — Registro de consentimiento con prueba
- **AC-43.1** — Cada alta guarda: quién (contacto), cuándo (timestamp), cómo (`source`: formulario, importación, API), texto exacto mostrado o su versión, IP y user-agent si es web, y el identificador del formulario; inmutable (solo inserciones).
- **AC-43.2** — *Dado* un contacto, *cuando* se consulta su consentimiento, *entonces* se obtiene la cadena completa de eventos (alta, confirmación, baja) en orden.
- **AC-43.3** — Sin un registro de consentimiento vigente para esa lista, el contacto no recibe la campaña.

### FR-44 — Doble opt-in
- **AC-44.1** — Las altas por formulario quedan `PENDING` y reciben un correo de confirmación (enviado por el flujo **transaccional**, categoría `SECURITY`); solo pasan a `SUBSCRIBED` al confirmar.
- **AC-44.2** — Los `PENDING` sin confirmar en 7 días se eliminan.
- **AC-44.3** — Configurable por lista, pero **obligatorio** para listas de captación web (decisión conservadora frente al art. 5 de la Ley 8968, que exige consentimiento expreso; a confirmar con asesoría).

### FR-45 — `List-Unsubscribe` y `List-Unsubscribe-Post` (un clic)
- **AC-45.1** — Todo mensaje de campaña lleva `List-Unsubscribe: <https://…/u/{token}>, <mailto:…>` y `List-Unsubscribe-Post: List-Unsubscribe=One-Click` (RFC 8058), y ambas cabeceras quedan cubiertas por la firma DKIM.
- **AC-45.2** — *Dado* un `POST` al URL de baja con el cuerpo `List-Unsubscribe=One-Click`, *entonces* la baja se aplica sin más interacción y responde `200`; el token es de un solo propósito, no adivinable y no caduca antes de 60 días (CAN-SPAM exige que el mecanismo funcione al menos 30).

### FR-46 — Página y endpoint de baja
- **AC-46.1** — El enlace visible en el cuerpo lleva a una página que confirma la baja **sin pedir inicio de sesión** y ofrece "darse de baja de todo el marketing de {producto}".
- **AC-46.2** — La baja se hace efectiva **inmediatamente** en el sistema (Ley 10946 art. 29 exige respetarla de inmediato según fuentes secundarias; Gmail exige 48 h; Yahoo, 2 días; CAN-SPAM, 10 días hábiles); objetivo verificable: ningún envío a esa dirección para esa lista tras el `200`.

### FR-47 — Supresión por lista frente a supresión global
- **AC-47.1** — Baja de una lista → `UNSUBSCRIBED` en esa lista solamente.
- **AC-47.2** — "Baja de todo" → marca `MARKETING_OPT_OUT` por tenant: no recibe ninguna campaña de ese tenant, pero **sí** transaccional.
- **AC-47.3** — Una queja (*feedback loop*) sobre un mensaje de campaña → `MARKETING_OPT_OUT` del tenant + supresión en la cuenta de marketing; no toca la cuenta transaccional.
- **AC-47.4** — Rebote duro en campaña → `CLEANED` en todas las listas del tenant; la supresión global transaccional se alimenta solo de rebotes duros (son un hecho sobre la dirección).

### FR-48 — Campañas
- **AC-48.1** — Una campaña tiene plantilla `MARKETING` (versión fijada), lista o segmento, remitente de campañas, `scheduledAt` y estado (`DRAFT` → `PENDING_APPROVAL` → `APPROVED` → `SENDING` → `SENT` \| `CANCELED` \| `PAUSED`).
- **AC-48.2** — Antes de aprobar se exige: envío de prueba a una dirección interna y la comprobación automática de FR-53.
- **AC-48.3** — Una campaña en `SENDING` se puede pausar y cancelar; los destinatarios aún no enviados no se envían.

### FR-49 — Aceleración y ventanas de envío
- **AC-49.1** — Ritmo por campaña configurable (default 2 msg/s) y nunca por encima del límite de la cuenta de marketing.
- **AC-49.2** — Ventana horaria por tenant (default 08:00–20:00 en `tenant.timezone`); fuera de ella, los envíos se reprograman.
- **AC-49.3** — Una campaña a > 5 000 destinatarios se reparte en al menos 2 días para dominios nuevos (calentamiento).

### FR-50 — Pruebas A/B
- **AC-50.1** — **Won't** incluso en la primera versión de campañas: con < 10 000 destinatarios por campaña la significancia es baja; se reconsidera con volumen real.

### FR-51 — Métricas por campaña
- **AC-51.1** — Por campaña: destinatarios, enviados, entregados, rebotes (duros y temporales), quejas, bajas y, si el seguimiento está activo, aperturas y clics únicos.
- **AC-51.2** — Alerta si la tasa de quejas de una campaña supera el 0,1 %, o la de rebotes el 2 %, en las primeras 1 000 entregas.

### FR-52 — Límites por plan y por tenant
- **AC-52.1** — Por tenant: máximo de contactos, de campañas al mes y de envíos de marketing al mes; superarlos → `429`/`422` con su código.
- **AC-52.2** — Los límites de marketing son independientes de los transaccionales (FR-21).

### FR-53 — Identificación comercial y contenido obligatorio
- **AC-53.1** — La plantilla `MARKETING` exige, verificado por el linter: nombre del remitente, dirección postal física válida (CAN-SPAM) y enlace de baja visible.
- **AC-53.2** — Si la campaña es publicidad, el asunto o el inicio del mensaje incluye la identificación "Publicidad" (u "Oferta"/"Invitación" según el caso), conforme al art. 9 de la Ley 10946 según fuentes secundarias (**a confirmar** con el texto oficial; vigencia prevista: 12 meses tras su publicación del 24/06/2026).
- **AC-53.3** — Asuntos no engañosos y cabeceras exactas (CAN-SPAM): el `From` corresponde al tenant y el `Reply-To` es atendido.

### FR-54 — Anti-abuso específico de campañas
- **AC-54.1** — Campañas a > 1 000 destinatarios (configurable) requieren aprobación de un administrador (`PENDING_APPROVAL`), que queda en auditoría.
- **AC-54.2** — Pausa automática de la campaña si, tras 500 entregas, quejas > 0,3 % o rebotes > 5 %.
- **AC-54.3** — Una lista importada cuyo primer envío supere el 5 % de rebotes se marca como "lista sospechosa" y bloquea nuevas campañas a ella hasta revisión.
- **AC-54.4** — Una key con `campaigns:write` filtrada no puede enviar sin aprobación por encima del umbral de AC-54.1.

## 5. Cumplimiento (verificado el 2026-10-05; no soy abogado)

> **(rev. 2026-10, owner):** todos los servicios son solo para Costa Rica. CAN-SPAM y GDPR **no aplican** mientras eso no cambie; se conservan en la tabla por si algún producto empieza a atender usuarios en EE. UU. o en la UE. Sí aplican la Ley 8968, la Ley 10946 (desde su vigencia) y las reglas de Gmail, Yahoo y Microsoft.

| Norma / política | Ámbito | Exigencias clave para campañas | Fuente |
|---|---|---|---|
| **CAN-SPAM** (EE. UU.) | Correo comercial a destinatarios en EE. UU. | Cabeceras no engañosas; asunto no engañoso; identificar el mensaje como anuncio; dirección postal física; mecanismo de baja que funcione ≥ 30 días tras el envío; bajas atendidas en ≤ 10 días hábiles; responsabilidad por terceros que envían en tu nombre; hasta 53 088 USD por correo infractor. Los mensajes transaccionales o de relación quedan exentos de la mayoría de los requisitos, pero no de los de cabeceras veraces | [FTC](https://www.ftc.gov/business-guidance/resources/can-spam-act-compliance-guide-business) |
| **GDPR** (UE) | Solo si un producto ofrece bienes o servicios a residentes de la UE o los monitoriza (art. 3.2) | Base jurídica (consentimiento para marketing directo por correo, salvo excepciones de la normativa ePrivacy de cada país), prueba del consentimiento, retirada tan fácil como otorgarlo y derecho de oposición. **A confirmar si aplica** a algún producto | — (no verificado en esta auditoría; aplicar solo si hay usuarios en la UE) |
| **Ley 8968** (Costa Rica) | Datos personales de residentes en Costa Rica | Consentimiento expreso, informado y documentado (art. 5); transferencia internacional solo con autorización (art. 14); ARCO en 5 días hábiles; registro ante PRODHAB si la base se "distribuye, difunde o comercializa" (una lista de marketing compartida podría entrar: **a confirmar**) | [Resumen 2026](https://www.recordinglaw.com/world-laws/world-data-privacy-laws/costa-rica-data-privacy-laws/) |
| **Ley 10946** (Costa Rica; gobernanza de servicios digitales y comercio electrónico) | Comerciantes que envían comunicaciones comerciales | Art. 29: no enviar comunicaciones comerciales no solicitadas ni consentidas salvo relación contractual o precontractual; procedimiento de baja fácil, respetado de inmediato. Art. 9: comunicación identificable como comercial e indicación "publicidad, invitación u oferta" al inicio del mensaje. Publicada el 24/06/2026; vigencia 12 meses después. **A confirmar con el texto oficial** | [Bufete de Costa Rica](https://bufetedecostarica.com/gobernanza-de-los-servicios-digitales-y-el-comercio-electronico-en-costa-rica-10946/) |
| **Gmail** | Remitentes a cuentas personales de Gmail | Todos: SPF o DKIM, PTR, TLS, spam < 0,3 %. Masivos (≈ 5 000/día; los subdominios cuentan con el dominio principal; el estado no caduca): SPF **y** DKIM, DMARC alineado, baja de un clic RFC 8058 en mensajes comerciales y bajas atendidas en 48 h; spam recomendado < 0,1 %; endurecimiento desde noviembre de 2025 | [Gmail FAQ](https://support.google.com/mail/answer/14229414?hl=en) |
| **Yahoo** | Remitentes masivos | SPF y DKIM, DMARC al menos `p=none` que pase, spam < 0,3 %, `List-Unsubscribe` de un clic y bajas atendidas en 2 días | [Yahoo Sender Hub](https://senders.yahooinc.com/best-practices/) |
| **Microsoft (Outlook.com)** | > 5 000 correos/día | Desde mayo de 2025: SPF, DKIM y DMARC (al menos `p=none`) alineados; si no, rechazo | [Resumen PowerDMARC](https://powerdmarc.com/microsoft-sender-requirements/) |

## 6. Anti-abuso: lo que ya aplica hoy y lo que añadiría el masivo

Hoy, en el transaccional (MVP), contra una key filtrada o un acceso futuro de terceros:

| Control | Dónde |
|---|---|
| Ámbitos: la key de un producto no puede publicar plantillas | FR-33 |
| CIDR por key | FR-33 |
| URL de variables limitadas a hosts del tenant | AC-07.8 |
| Cuota diaria ajustada a ~3× el volumen esperado | FR-21 |
| Pausa automática por volumen anómalo (3× el p95 horario de 14 días), rebotes > 4 % o quejas > 0,1 % | FR-34 |
| Supresión global de rebotes y quejas | ADR-0012 |
| Auditoría de keys, plantillas y pausas | FR-22 |

Con el masivo se añaden la aprobación previa de campañas grandes, la pausa por campaña, la detección de listas sospechosas y una cuenta separada (FR-40, FR-54).

Si algún día se da acceso a **terceros** (otro proyecto según el anti-roadmap): verificación de dominio por tenant, cuotas por plan, revisión manual del primer envío, firma HMAC de petición (fase 2) y cuentas de proveedor por cliente. Eso es un producto distinto; requiere su propio análisis.
