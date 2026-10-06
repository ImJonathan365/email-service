# ADR-0017 — Credenciales: API keys con ámbitos y origen restringible; rotación de la credencial de administración

- **Estado:** Accepted (owner, 2026-10-05)
- **Fecha:** 2026-10-05
- **Autor:** agente: Claude (auditoría de documentación)
- **Supersede:** ADR-0004
- **Requisitos relacionados:** FR-01, FR-03, FR-33, FR-34, NFR-07, `08-seguridad.md` §2–4

## Contexto

ADR-0004 fijó:
- secretos fuera de la BD;
- API keys `esk_{env}_{prefix}_{secret}` con hash SHA-256 y comparación en tiempo constante;
- caché ≤ 60 s;
- varias keys activas por tenant.

Todo eso se **mantiene**: SHA-256 sobre un secreto de 256 bits es correcto y Argon2 sería coste sin beneficio. La auditoría encontró tres huecos:

1. **Sin ámbitos:** la misma key de envío podía crear y publicar plantillas y borrar supresiones. Una key filtrada permitía publicar contenido arbitrario y enviarlo desde el dominio del producto.
2. **Formato impreciso:** un prefijo de 4 caracteres puede colisionar; "Base62" en el texto frente a hexadecimal en el ejemplo.
3. **Una sola credencial de administración** (`ADMIN_API_KEY`) sin forma de rotarla sin corte.

## Decisión

1. Se mantienen los puntos 1, 3, 4 y 5 de ADR-0004:
   - credenciales propias solo en el entorno o en un secrets manager;
   - hash SHA-256 y comparación en tiempo constante;
   - caché ≤ 60 s;
   - varias keys activas por tenant.
2. **Formato:** `esk_{env}_{prefix}_{secret}`.
   - `env` ∈ {`live`, `test`};
   - `prefix` = 8 caracteres Base62 (se regenera si colisiona con un `UNIQUE`);
   - `secret` = 43 caracteres Base62 de un CSPRNG (≥ 256 bits);
   - el hash se guarda como `bytea` de 32 bytes.
3. **Ámbitos por key** (FR-33): `emails:send`, `emails:read`, `templates:write` y `suppressions:write`.
   - La key desplegada en un producto lleva solo `emails:send` + `emails:read`.
   - `templates:write` y `suppressions:write` se emiten en keys aparte que usa el administrador desde su máquina o desde un job de CI que publica plantillas.
4. **Origen restringible:** `allowedCidrs` por key (vacío = cualquiera). La IP se toma del socket, o de `X-Forwarded-For` solo si el salto anterior es un proxy de confianza (`TRUSTED_PROXIES`).
5. **Inmutabilidad:** ámbitos y CIDR se fijan al emitir; cambiarlos = emitir otra key y revocar la anterior.
6. **Administración:**
   - `ADMIN_API_KEYS` admite varias claves separadas por comas, para rotar sin corte (añadir la nueva, desplegar, retirar la vieja);
   - cada una tiene ≥ 32 bytes aleatorios;
   - `/admin/v1/**` solo es accesible desde la red privada (regla en el balanceador) y queda en auditoría.
7. **Detección de abuso** (FR-34): pausa automática por volumen, rebotes o quejas anómalos, como segunda línea si una key se filtra.

## Alternativas consideradas

| Alternativa | A favor | En contra | Motivo de descarte |
|---|---|---|---|
| Sin ámbitos (ADR-0004) | Más simple | Una key filtrada controla plantillas y supresiones | Riesgo bloqueante (auditoría #3) |
| Firma HMAC de cada petición además de la key | Impide reutilizar una key capturada en tránsito | Más complejidad en cada cliente; con TLS obligatorio aporta poco | Se mantiene en fase 2, condicionado a exponer el servicio fuera de la red privada |
| mTLS entre servicios | Muy fuerte | Gestión de certificados desproporcionada | Igual que en ADR-0004 |
| OAuth2 client credentials | Estándar, ámbitos nativos | Requiere un servidor de autorización | Desproporcionado |

## Consecuencias

- **Positivas:** el impacto de una key filtrada queda en "enviar plantillas existentes, con enlaces a hosts del propio producto, hasta la cuota o la pausa automática"; administración rotable.
- **Negativas / trade-offs aceptados:** más keys que gestionar (al menos dos por tenant); `allowedCidrs` solo sirve si el hosting da IP de salida estables.

## Criterio de revisión

Exposición del servicio fuera de la red privada (→ HMAC de petición), clientes de terceros (→ OAuth2), o emisión de credenciales de baja entropía (→ Argon2id).
