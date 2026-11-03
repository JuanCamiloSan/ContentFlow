# ADR 0006 — Registro abierto, administrador de la plataforma y buzón de notificaciones

- **Estado:** aceptada
- **Fecha:** 2026-11-03
- **Origen:** revisión del acceso al ver el bloque C con el dueño del producto; complementa el [ADR 0004](0004-proveedores-de-identidad-y-correo.md) y el [ADR 0005](0005-roles-de-agencia-y-cliente-y-comentarios.md)

## Contexto

Los documentos decían que los clientes entran "por invitación, nunca por registro abierto". Pero el producto debe poder venderse como SaaS: una agencia nueva tiene que poder registrarse sola, crear su espacio y, más adelante, estar sujeta a los límites de su plan. Además, el dueño del producto necesita administrar todos los workspaces. Y sin dominio propio ni proveedor de correo, los avisos y las invitaciones no pueden salir por correo.

## Decisión

### 1. Registro abierto, invitación solo para unirse

Cualquier cuenta de Google puede iniciar sesión. Una persona autenticada sin `membership` no tiene workspace y solo puede hacer dos cosas: crear el suyo (primer ingreso) o aceptar una invitación. Cualquier otra ruta la rechaza.

- **La agencia** se registra sola y queda como Propietario de su workspace.
- **Las demás personas** (Editor, Aprobador, Colaborador) entran únicamente con una invitación que genera el Propietario.
- Ver un video también exige iniciar sesión. No hay enlaces de vista anónima: abrirían una segunda excepción al aislamiento y expondrían a quienes salen en video.

### 2. Un workspace por usuario, ampliable por el administrador

`app_user.max_workspaces` vale 1 por defecto y se cuenta contra los workspaces de los que la persona es Propietaria. Vive en el usuario y no en el workspace, para que no dependa de RLS.

Crear el workspace es una función `SECURITY DEFINER` que crea el `workspace` y la `membership` de Propietario de forma atómica, con `search_path` fijo. Bloquea la fila de `app_user` antes de contar, para que dos peticiones simultáneas no superen el límite. Es necesaria porque quien aún no tiene workspace no puede insertar bajo RLS.

Cuando existan los planes (Fase 4), este límite se calcula desde el plan en lugar de ser un número fijo.

### 3. Administrador de la plataforma

Es el dueño del producto. No es un rol del workspace ni tiene fila en `membership`: no es un sexto rol de los del [ADR 0005](0005-roles-de-agencia-y-cliente-y-comentarios.md) y no se confunde con el Propietario de una agencia.

- **Alcance:** acceso total a todos los workspaces, incluido su contenido. Se eligió así porque quien administra la plataforma es el dueño del producto y es responsable de ese acceso. La contrapartida es transparencia: va en la política de privacidad (decisión 6).
- **Dónde vive:** una marca en `app_user` (`is_platform_admin`), asignada a mano con SQL y nunca por la API. No se lee de un claim del token, que no es la fuente de verdad.
- **Cómo accede, sin tocar las políticas RLS ni usar `BYPASSRLS`:**
  - Para listar workspaces y cambiar límites, funciones `SECURITY DEFINER` acotadas que verifican la marca en la base.
  - Para trabajar dentro de un workspace ajeno, el paso que resuelve la pertenencia ([ADR 0003](0003-contexto-de-sesion-y-roles-de-postgres.md), decisión 3) acepta a este usuario para cualquier workspace y fija `app.workspace_id` como siempre. Las políticas normales aplican igual y ven todo lo del workspace, como un Propietario. La rama nueva está en un único lugar del código.
- **Registro:** cada petición del administrador a un workspace ajeno deja una fila en `audit_log` (decisión 4). Si el volumen molesta, se pasa a una fila por sesión de entrada con un ADR nuevo.

### 4. Alcance de `audit_log`

Registra eventos clave, no toda escritura:

- cambios de estado de una pieza;
- decisiones en `review` (incluidas las tomadas en nombre del cliente);
- creación de workspace y aceptación de invitaciones;
- cambios de límite y accesos del administrador;
- borrados selectivos.

Mantiene la regla de solo identificadores y enumeraciones, sin texto libre y sin llave a `app_user`. Si más adelante hace falta ampliarlo, se agrega sin migrar lo existente.

### 5. Buzón de notificaciones

Los avisos van a un buzón dentro de la aplicación. No hay correo en el MVP.

- La tabla `notification` lleva el canal (hoy solo la aplicación), una marca de importante y la fecha de lectura.
- Un aviso importante es el candidato a salir también por WhatsApp en la Fase 2. Cuáles son importantes se decide entonces; el ejemplo vigente es "pieza en aprobación, aprobada o rechazada".
- La abstracción de canales ya existe desde el principio, para que añadir WhatsApp sea un adaptador y no una migración.
- El adaptador de correo vuelve en una fase posterior, con un dominio propio.

### 6. Invitación por enlace

El Propietario escribe el correo de la persona, el rol y, para roles de cliente, el cliente. La aplicación genera un enlace de un solo uso con expiración y lo muestra una vez; el Propietario lo copia y lo comparte por su cuenta. No se envía ningún correo. El token se guarda como hash.

Al aceptar, la persona inicia sesión con Google. Su correo verificado debe coincidir con el de la invitación, o se rechaza: sin esa comprobación el enlace sería un bearer y quien lo reciba reenviado entraría con el rol. Un enlace usado, vencido o revocado se rechaza.

### 7. Privacidad

La política de privacidad debe decir que el administrador de la plataforma puede acceder al contenido de los workspaces. Está en la lista de trabajo continuo del backlog.

## Alternativas descartadas

| Alternativa | Razón |
| --- | --- |
| Desactivar el registro y crear las cuentas desde el backend | Complica el flujo y contradice la meta de que una agencia se registre sola |
| Rol de administrador como claim del token | El token no es fuente de verdad sobre privilegios; la base sí |
| `BYPASSRLS` o rol especial para el administrador | Rompe la regla de que la aplicación nunca se salta RLS |
| Políticas RLS con una rama "o es administrador" en cada tabla | Una tabla olvidada cambia el aislamiento; una sola rama en el paso de resolver pertenencia no |
| Administrador sin acceso al contenido (solo límites y estado) | Descartado por el dueño: como administrador del producto, la privacidad frente a él no es una restricción buscada |
| Enlaces de vista sin sesión | Segunda excepción al aislamiento y exposición de quienes aparecen en video |
| Correo como canal del MVP | Exige dominio propio y proveedor, y el buzón cubre la necesidad |
| Registrar toda escritura en `audit_log` | Volumen y datos que se quieren poder borrar |

## Consecuencias

- `app_user` gana `max_workspaces` y `is_platform_admin`. `notification` gana canal, importancia y lectura.
- El filtro que abre la transacción tiene una rama más: el administrador puede fijar cualquier workspace, y eso escribe en `audit_log`.
- Tareas nuevas en el backlog: onboarding con límite de workspaces (C14), administrador de la plataforma (C15) y sus pantallas, más el buzón en el frontend. La entrada del administrador a un workspace ajeno se completa con E13, porque necesita `audit_log`.
- C7 sale del MVP. C6 pasa a ser el buzón. C8 y C9 trabajan solo con enlace.
- Pruebas obligatorias: un usuario normal no puede ser administrador por ningún medio; el segundo workspace se rechaza, incluso con peticiones simultáneas; el administrador deja una fila por cada acceso.
