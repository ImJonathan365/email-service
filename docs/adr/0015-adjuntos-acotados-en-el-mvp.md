# ADR-0015 — Adjuntos acotados en el MVP, transitorios en PostgreSQL y sin almacenamiento de objetos

- **Estado:** Rejected (owner, 2026-10-05): ningún producto envía comprobantes ni otros adjuntos por correo. El diseño se conserva como referencia si aparece un caso real (FR-30 queda en Won't).
- **Fecha:** 2026-10-05
- **Autor:** agente: Claude (auditoría de documentación)
- **Requisitos relacionados:** FR-28 (DEPRECATED), FR-30, FR-23, NFR-10, NFR-15

## Contexto

El MVP excluía los adjuntos ("implica almacenamiento, límites y antivirus") hasta que existiera "un caso de uso real". Ese caso ya existe: Chinamo es un POS en Costa Rica y debe hacer llegar el comprobante electrónico al receptor. La práctica habitual es enviar por correo el XML firmado, la respuesta de Hacienda y la representación en PDF. La normativa habla de que el comprobante se "transmite al receptor" ([guía v4.4](https://blog.alegra.com/costa-rica/comprobantes-provisionales-y-electronicos-costa-rica/)); el canal exacto es **a confirmar** contra la resolución vigente de la DGT. Es probable que Colmena tenga la misma necesidad si factura.

La exportación mensual de PipeMend no necesita adjunto: un enlace firmado y con caducidad es mejor (tamaño, privacidad, sin reenvíos).

Resend admite adjuntos en base64 o por URL, hasta 40 MB por correo tras la codificación ([Send email](https://resend.com/docs/api-reference/emails/send-email)).

## Decisión

1. Los adjuntos entran en el MVP con prioridad **Should** (FR-30), acotados:
   - ≤ 3 archivos;
   - solo `application/pdf`, `application/xml` y `text/xml`;
   - ≤ 2 MB decodificados en total;
   - comprobación de *magic bytes*;
   - nombre ≤ 100 caracteres sin separadores de ruta ni CR/LF.
2. **Transporte:** base64 dentro de `POST /v1/emails` (límite de cuerpo de 3 MB cuando hay adjuntos). No se aceptan URL remotas: evita SSRF y elimina la dependencia de que el enlace siga vivo durante los reintentos.
3. **Almacenamiento:**
   - tabla `email_attachment` (`bytea`) en la misma transacción que el mensaje;
   - el contenido se anula al llegar el mensaje a `SENT` o a un estado terminal;
   - **sin almacenamiento de objetos**: a ~3 000 comprobantes/mes de ~100 KB, el volumen vivo es de decenas de MB como mucho, purgado en segundos o minutos.
4. **Idempotencia:** el `request_hash` incluye el SHA-256 de cada adjunto; el orden de los adjuntos es estable (determinismo hacia el proveedor).
5. **Sin antivirus:** se exige por contrato que los productos adjunten solo documentos que ellos mismos generan; nunca archivos subidos por usuarios.
6. El endpoint de lote no admite adjuntos.

## Alternativas consideradas

| Alternativa | A favor | En contra | Motivo de descarte |
|---|---|---|---|
| Mantenerlos fuera del MVP | Menos código | Chinamo no puede usar el servicio para su caso principal | Rompe un caso real |
| Adjuntos por URL (el proveedor descarga) | Sin bytes en nuestra BD | Las URL deben vivir hasta 23 h (reintentos); SSRF si alguna vez descargamos nosotros; dependencia del almacenamiento del producto | Más frágil |
| S3/R2 para los adjuntos | Escala a archivos grandes | Componente nuevo (viola NFR-10) sin una métrica que lo justifique | Se reconsidera si se superan los umbrales |
| Antivirus (ClamAV) | Defensa frente a archivos maliciosos | Otro contenedor y firmas que actualizar; los archivos los genera el propio producto | No justificado con productores de confianza |

## Consecuencias

- **Positivas:** Chinamo y Colmena pueden integrarse en la Fase 0; sin infraestructura nueva; datos transitorios.
- **Negativas / trade-offs aceptados:** filas grandes temporales en PostgreSQL (más WAL); límite de 2 MB por correo; las exportaciones grandes requieren que el producto aloje el archivo.

## Criterio de revisión

Más de 10 000 correos con adjunto al mes, adjuntos > 2 MB necesarios, tipos de archivo nuevos, o que algún producto necesite reenviar archivos de usuarios (→ antivirus y almacenamiento de objetos, con un ADR propio).
