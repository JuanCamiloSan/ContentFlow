# ADR 0001 — Decisiones iniciales de arquitectura

- **Estado:** aceptada
- **Fecha:** 2026-10-26
- **Origen:** tabla "Registro de decisiones" de `docs/modelo-datos.md` y `docs/prompt-proyecto.md`

## Contexto

ContentFlow debe servir primero a una operación real (una agencia con clientes propios) y después poder venderse como SaaS multiempresa. La disponibilidad es de 5 a 8 horas semanales, así que las decisiones favorecen la simplicidad operativa y evitan reconstrucciones posteriores.

Este ADR recoge las decisiones fundacionales ya tomadas. Cada una se puede reemplazar con un ADR posterior que la referencie; mientras tanto, este documento es la fuente de verdad sobre el porqué.

## Decisiones

### Aislamiento y seguridad

| # | Decisión | Alternativa descartada | Razón |
| --- | --- | --- | --- |
| 1 | Row Level Security en PostgreSQL, con `workspace_id` en toda tabla, `FORCE` y políticas `USING` + `WITH CHECK` | Filtros en el código | Un filtro olvidado una sola vez expone datos de otro cliente; con RLS la base rechaza la fila aunque la consulta esté mal escrita |
| 2 | ~~El workspace se fija con `SET LOCAL app.workspace_id` al abrir la transacción, en un único punto~~ **Reemplazada por el [ADR 0003](0003-contexto-de-sesion-y-roles-de-postgres.md)** | `SET` normal o fijarlo en cada consulta | Con un pool de conexiones, `SET` dejaría el workspace anterior activo para la siguiente petición. El ADR 0003 conserva esta razón y agrega `app.user_id`, `current_workspace()` y los roles de PostgreSQL |
| 3 | Proveedor OIDC externo detrás de Spring Security como resource server | Autenticación propia | Recuperación de contraseña, verificación de correo, sesiones y bloqueo ya están resueltos y auditados |
| 4 | `app_user` vive fuera del workspace; la pertenencia se resuelve en `membership` | Usuario por workspace | Una persona puede trabajar con varias agencias |
| 5 | Identificadores UUID | Entero secuencial | Un id expuesto no revela volumen ni permite adivinar otros |
| 6 | Logs JSON estructurados con identificador de correlación desde el inicio | Agregar observabilidad al final | El identificador de correlación se diseña desde el principio o no existe |

### Modelo de dominio

| # | Decisión | Alternativa descartada | Razón |
| --- | --- | --- | --- |
| 7 | Marca como capa propia entre cliente y cuenta social | Perfil creativo en el cliente | Dos de tres clientes actuales tienen marcas con tono distinto |
| 8 | Pieza a cuenta, relación muchos a muchos (`piece_target`) | Una cuenta por pieza | Beraca ya publica la misma pieza en dos cuentas |
| 9 | Versiones inmutables; un rechazo crea una fila nueva con `round_number` incrementado | Sobrescribir el archivo | Sin historial no hay evidencia de qué se entregó ni de qué se aprobó |
| 10 | Estados como máquina de transiciones con un único endpoint de cambio | Campo editable libremente | Evita saltarse la aprobación y deja cada cambio en `audit_log` |
| 11 | `round_number` se registra pero no limita | Limitar a dos rondas desde el MVP | Es una regla comercial y la monetización está aplazada |
| 12 | Propiedad y retención como campos de `client` | Regla fija en el código | Los contratos no son todos iguales y van a cambiar |
| 13 | Borrado manual y selectivo, con `deletion_record` que sobrevive | Borrado automático en cascada | Qué se puede borrar lo decide el contrato de cada cliente |

### Arquitectura técnica

| # | Decisión | Alternativa descartada | Razón |
| --- | --- | --- | --- |
| 14 | Monolito modular (`tenancy`, `identity`, `content`, `files`, `scheduling`) | Microservicios | Con 5 a 8 horas semanales, la complejidad operativa no se sostiene |
| 15 | Archivos fuera de la base; solo la ficha en `asset` | Binarios en PostgreSQL | Respaldos inmanejables y consultas lentas |
| 16 | S3 con URL prefirmadas de expiración corta; el archivo nunca pasa por el backend | Subir a través del backend | Menos ancho de banda y menor superficie de ataque |
| 17 | El NAS es caché de trabajo, nunca la única copia | NAS como respaldo | Dos bahías dan redundancia ante un disco dañado, no respaldo ante robo, incendio o ransomware |
| 18 | Solo miniatura del entregable, sin versión liviana | Transcodificación para revisión | El entregable ya es un video de redes, liviano de por sí |
| 19 | Miniatura generada dentro de la confirmación de subida | Trabajos en segundo plano | ffmpeg lee el inicio del archivo por rango y tarda segundos |
| 20 | Abstracción de notificaciones, con correo como primer canal | Enviar correos directo | WhatsApp y correo tienen reglas distintas y conviven |
| 21 | Testcontainers obligatorio para pruebas de integración | Base en memoria | Las políticas de RLS solo se pueden probar contra un PostgreSQL real |

## Consecuencias

- Toda tabla nueva lleva `workspace_id` y nace con su política de RLS en la misma migración (el patrón se formaliza en el bloque B).
- Ningún endpoint modifica el estado de una pieza fuera del endpoint de transición.
- Cambiar de arquitectura monolítica a servicios, o de RLS a filtros en el código, exige un ADR nuevo que reemplace a este.
- El diseño asume costo de infraestructura mínimo: nada de componentes que el MVP no necesite.

## Decisiones aún abiertas

No están decididas y no se asumen. Cada una tendrá su ADR cuando se resuelva:

- Proveedor de identidad: Keycloak autoalojado o servicio gestionado (se necesita hacia la semana 9)
- Proveedor de correo transaccional (antes de las invitaciones)
- Proveedor de almacenamiento S3 en producción
- Alcance del `audit_log`: solo eventos clave o toda escritura
- Estrategia de respaldo de PostgreSQL (obligatoria antes de datos reales)
- Umbral de registro en el RNBD ante la SIC
- Verificación de empresa ante Meta, para WhatsApp en la Fase 2
