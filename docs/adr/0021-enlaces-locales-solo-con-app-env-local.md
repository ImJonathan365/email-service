# ADR-0021 — Enlaces a localhost en variables URL, solo con APP_ENV=local

- **Estado:** Accepted (owner, 2026-10-14)
- **Fecha:** 2026-10-14
- **Autor:** agente: Claude (integración local)
- **Supersede:** — (modifica la regla 6 de ADR-0011, solo en local)
- **Requisitos relacionados:** FR-06, FR-07 (AC-07.8), NFR-15

## Contexto

ADR-0011 (regla 6) y AC-07.8 exigen que toda variable `format: uri` sea `https` y apunte a un host de `tenant.allowedLinkHosts`. La validación de `allowedLinkHosts` exige además un dominio con punto. En local, un producto corre en `http://localhost:3000` o `http://colmena.localhost:5173`: ningún enlace de sus correos (verificar cuenta, restablecer contraseña) pasa la regla, y la única salida sería falsear dominios o apagar la regla, que es la protección contra una key filtrada usada para *phishing*.

## Decisión

1. **Solo con `APP_ENV=local`**, una variable URL también se acepta si:
   - su esquema es `http` o `https`;
   - su host es `localhost` o termina en `.localhost`;
   - no lleva credenciales en la URL (`user@`).

   Da igual lo que tenga el tenant en `allowedLinkHosts`.
2. Con cualquier otro `APP_ENV` (`staging`, `production`), la regla es exactamente la de ADR-0011, sin cambios. Aunque un tenant tuviera `localhost` en `allowedLinkHosts`, `http` sigue rechazándose.
3. **No hay interruptor propio:** se deriva solo de `APP_ENV`. No existe variable de entorno ni campo del tenant que lo active. Con `APP_ENV=production` es imposible por construcción, igual que SMTP (ADR-0016), que se rechaza al arrancar en producción.
4. Se aplica igual en la aceptación (`POST /v1/emails`, lote) y en la vista previa (FR-06): misma clase `UrlPolicy`.
5. Sigue rechazándose en local todo lo demás que ya se rechazaba:
   - `http` a otros hosts;
   - `127.0.0.1` y `[::1]`, porque solo se acepta el nombre;
   - `localhost.evil.test`;
   - `javascript:`;
   - `ftp:`.

## Alternativas consideradas

| Alternativa | A favor | En contra | Motivo de descarte |
|---|---|---|---|
| Interruptor `ALLOW_LOCAL_LINKS` | Explícito | Un interruptor puede acabar activado en producción por error | Exigencia del owner: imposible en producción |
| Permitir `localhost` en `allowedLinkHosts` del tenant | Sin código especial | Seguiría exigiendo `https` en local, y abre la puerta a tenants de producción con `localhost` | No resuelve `http` y relaja la validación del tenant en todos los entornos |
| Dominios de desarrollo con TLS (mkcert) | Ninguna excepción | Cada desarrollador configura certificados y DNS por producto | Coste alto para un servicio interno de un solo owner |

## Consecuencias

- **Positivas:** un producto en desarrollo envía correos con enlaces a sí mismo y se ven en Mailpit sin tocar la regla de producción.
- **Negativas / trade-offs aceptados:**
  - un servicio desplegado por error con `APP_ENV=local` aceptaría enlaces a `localhost`;
  - el daño es acotado, porque esos enlaces solo resuelven en la máquina de quien abre el correo;
  - y `APP_ENV=local` ya cambia otros comportamientos visibles (migraciones al arrancar, keys `esk_test_`).
- **Pruebas:**
  - `UrlPolicyTest` comprueba los dos casos: en `local` se aceptan, y en `staging` y `production` se rechazan los mismos enlaces;
  - `EmailAcceptanceTest` lo comprueba de punta a punta (los tests corren con `APP_ENV=local`).

## Criterio de revisión

Si se necesitan enlaces locales en `staging`, o si aparece un entorno de desarrollo compartido con dominio propio.
