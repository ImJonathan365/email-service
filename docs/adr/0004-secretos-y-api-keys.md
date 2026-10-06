# ADR-0004 — Secretos fuera de la base de datos; API keys con prefijo y hash

- **Estado:** Superseded by ADR-0017 (2026-10-05)
- **Fecha:** 2026-09-20
- **Requisitos relacionados:** FR-01, FR-03, NFR-07, `08-seguridad.md`

## Contexto
El servicio maneja dos clases de credenciales: las suyas hacia el exterior (proveedor de correo, base de datos, firma de webhooks) y las que emite para que sus clientes internos se autentiquen. Guardar cualquiera de ellas en claro en la base de datos convierte un volcado de datos en una toma de control del dominio de correo.

## Decisión
1. Las credenciales propias viven **solo** en variables de entorno inyectadas por el orquestador o un secrets manager. Nunca en la base de datos, el repositorio, la imagen ni los logs. Sin ellas, la aplicación no arranca en `production`.
2. Las API keys de tenant tienen formato `esk_{env}_{prefix}_{secret}` con `secret` de ≥ 32 bytes aleatorios criptográficos.
3. En la base de datos se guardan `key_prefix` (en claro, para localizar la fila) y `SHA-256(secret)`. El secreto completo se muestra **una sola vez**, al emitirlo.
4. La verificación compara hashes en tiempo constante. Se cachean claves validadas ≤ 60 s.
5. Un tenant puede tener varias claves activas, para rotar sin downtime.

## Alternativas consideradas
| Alternativa | A favor | En contra | Motivo de descarte |
|---|---|---|---|
| Argon2id / bcrypt para las API keys | Estándar para contraseñas | Coste por petición alto e innecesario: el secreto tiene 256 bits de entropía, no es una contraseña humana | SHA-256 es suficiente aquí; se revisará si alguna vez se emiten claves de baja entropía |
| JWT firmados por tenant | Sin lookup en base de datos | Revocación complicada; rotación de claves más compleja | Peor ajuste para claves de larga vida |
| mTLS entre servicios | Muy fuerte | Gestión de certificados desproporcionada para este tamaño | Descartada |
| Guardar la clave del proveedor cifrada en la base de datos | "Todo en un sitio" | Hay que custodiar la clave de cifrado igualmente; amplía el impacto de un volcado | Contradice el requisito original |

## Consecuencias
- Positivas: un volcado de la base de datos no permite enviar correo ni suplantar a un tenant; rotación sencilla y auditada; una clave filtrada se identifica por su prefijo.
- Negativas: si se pierde el secreto de una key, hay que emitir otra (por diseño); la revocación tarda hasta 60 s por la caché.

## Criterio de revisión
Emisión de credenciales de baja entropía, exposición del servicio fuera de la red privada (→ añadir firma HMAC de petición) o requisitos de cumplimiento que exijan otro esquema.

---
> **Nota de estado (2026-10-05):** de este ADR solo se ha modificado la línea de *Estado*; su texto aceptado no se edita. El owner aceptó ADR-0017, que lo sustituye, el 2026-10-05.
