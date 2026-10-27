# ADR 0003 — Contexto de sesión y roles de PostgreSQL

- **Estado:** aceptada
- **Fecha:** 2026-10-27
- **Origen:** revisión del diseño de aislamiento antes de empezar el bloque B
- **Reemplaza:** la decisión 2 del [ADR 0001](0001-decisiones-iniciales-de-arquitectura.md)

## Contexto

El ADR 0001 decidió fijar el workspace con `SET LOCAL app.workspace_id` en un único punto. Al bajarlo a detalle aparecen cinco problemas que esa decisión no resolvía:

1. **Huevo y gallina.** Para saber a qué workspace pertenece una persona hay que leer `membership`, pero `membership` tiene RLS por workspace y todavía no hay workspace fijado.
2. **La invitación.** Quien acepta una invitación aún no tiene `membership`, así que ninguna política basada en pertenencia le deja ver su propia invitación.
3. **Fallo silencioso.** `current_setting('app.workspace_id', true)` devuelve nulo si no hay contexto, y la política devuelve cero filas. Eso se ve igual que "no hay datos".
4. **Roles.** `FORCE ROW LEVEL SECURITY` no protege contra superusuarios ni contra roles con `BYPASSRLS`. Si la aplicación o las pruebas se conectan con uno de ellos, el aislamiento no se está probando ni aplicando.
5. **`SET LOCAL` no admite parámetros.** Armar la sentencia concatenando el valor es la clase de código que termina en inyección.

## Decisión

### 1. Dos variables de sesión, fijadas en dos pasos

| Variable | Se fija | De dónde sale |
| --- | --- | --- |
| `app.user_id` | Siempre, al abrir la transacción | El identificador del token ya validado |
| `app.workspace_id` | Después, una vez resuelto el workspace | Ver decisión 3 |

`app_user` y `membership` llevan sus políticas sobre `app.user_id`. Así, resolver a qué workspaces pertenece alguien ocurre bajo RLS legítimo, sin saltarse nada. Solo después se fija `app.workspace_id` y el resto de las tablas aplican su política habitual.

Ambas se fijan con `set_config(nombre, valor, true)`, nunca con `SET LOCAL` concatenado. El tercer argumento `true` equivale a `LOCAL`: el valor muere al terminar la transacción. Siguen en un único punto de entrada, y el valor se convierte a `UUID` en Java antes de llegar a la base.

La política de `membership` es la única que usa lectura tolerante (`current_setting(..., true)` con `NULLIF`), porque ahí cero filas es la respuesta correcta antes de que haya workspace. Permite ver las filas propias (`user_id = app.user_id`) **o** las del workspace activo (para listar el equipo). PostgreSQL no garantiza el orden de evaluación de un `OR`, por eso esta política no puede llamar a `current_workspace()`, que lanzaría excepción.

### 2. `invitation` es la excepción

El invitado todavía no tiene `membership`, así que no hay política por pertenencia que le sirva. Se resuelve con funciones `SECURITY DEFINER`:

- **Consulta.** Recibe el hash del token y devuelve solo esa invitación.
- **Aceptación.** Consume el token y crea la `membership` de forma atómica, en la misma función.

Reglas de ambas:

- `SET search_path` fijo. Una función `SECURITY DEFINER` sin eso es un agujero clásico.
- `invitation` y `membership` **no llevan `FORCE`**. Con `FORCE`, el dueño también pasa por RLS y las funciones, que corren con los permisos del dueño, verían cero filas. La protección sigue intacta para la aplicación, que no es dueña de las tablas (decisión 5). Es una excepción documentada a la regla de `FORCE` del ADR 0001.
- **Nunca se busca a una persona por correo.** La invitación se crea contra un correo sin comprobar si existe cuenta. Al aceptar, la función enlaza con la cuenta autenticada. Esto elimina de raíz un endpoint que permitiría enumerar correos.
- **El correo manda.** Para aceptar, el token de identidad debe traer `email_verified = true` y el correo, normalizado a minúsculas, debe coincidir con el de la invitación. Sin esta comprobación el token de invitación sería un bearer: quien reciba el correo reenviado entraría con otra cuenta.
- El límite de intentos no vive en la función: un hash que no existe no tiene fila donde contar intentos. Con tokens de alta entropía la fuerza bruta es inviable, y el límite por IP y por cuenta es la tarea C13 (rate limit).

### 3. El workspace activo viaja en `X-Workspace-Id`

No va en el token. Un claim obligaría a volver a autenticarse para cambiar de workspace.

- El encabezado se valida contra `membership` (bajo `app.user_id`) antes de fijar `app.workspace_id`.
- Si la persona pertenece a un solo workspace y no manda el encabezado, se usa ese.
- Si pertenece a varios y no lo manda, se rechaza con 400.
- Si el encabezado nombra un workspace al que no pertenece, se rechaza con 403 y sin distinguir si el workspace existe.

### 4. `current_workspace()` en lugar de `current_setting` crudo

```sql
CREATE FUNCTION current_workspace() RETURNS uuid
LANGUAGE plpgsql STABLE AS $$
DECLARE
  v uuid := NULLIF(current_setting('app.workspace_id', true), '')::uuid;
BEGIN
  IF v IS NULL THEN
    RAISE EXCEPTION 'No hay contexto de workspace' USING ERRCODE = 'insufficient_privilege';
  END IF;
  RETURN v;
END $$;
```

Con `current_setting(..., true)` y nada fijado, la política devuelve cero filas, y eso se ve igual que "no hay datos". Con la función, olvidarse del contexto es un error ruidoso. `NULLIF` cubre también la cadena vacía, que es lo que queda tras un `RESET` o tras `set_config(..., '', true)`. Es `STABLE`, no `VOLATILE`, para que el planificador no la evalúe fila por fila.

Todas las políticas de las tablas de negocio usan `current_workspace()`. Existe una función análoga `current_user_id()` para `app_user`.

### 5. Tres roles de PostgreSQL

| Rol | Para qué | Permisos |
| --- | --- | --- |
| Dueño del esquema | Corre Flyway | Dueño de las tablas. Sin `BYPASSRLS`, sin superusuario |
| Aplicación | Todo el tráfico de la API | Solo DML (`SELECT`, `INSERT`, `UPDATE`, `DELETE`). No es dueño de nada |
| Superusuario | Administración | No lo usa ni la aplicación ni Flyway |

- Los roles se crean **fuera de las migraciones**: en el script de inicio de Docker Compose, en el de Testcontainers y en el aprovisionamiento de producción. `CREATE ROLE ... PASSWORD` dentro de una migración pondría credenciales en Git. Las contraseñas vienen de variables de entorno.
- Las migraciones solo hacen `GRANT` y `ALTER DEFAULT PRIVILEGES`, para que toda tabla nueva sea accesible al rol de aplicación sin trabajo manual.
- La aplicación tiene dos `DataSource`: el del dueño, solo para Flyway, y el de aplicación, para todo lo demás.

### 6. Las pruebas de aislamiento se conectan con el rol de aplicación

Testcontainers arranca por defecto con un superusuario. Con él, las pruebas de fuga, de escritura cruzada y de concurrencia pasarían sin probar nada, porque el superusuario se salta RLS. Las pruebas de aislamiento abren su conexión con el rol de aplicación, y una de ellas verifica que ese rol no es superusuario ni tiene `BYPASSRLS`.

### 7. Segunda frontera: el cliente

Algunos roles solo ven un cliente concreto de un workspace. Las tablas con `client_id` llevan esa condición **dentro de la política**, resuelta por una función `STABLE` que lee `membership`. Dejarlo solo en el código es el mismo error que este proyecto criticó en los filtros de las consultas: basta olvidarlo una vez.

## Consecuencias

- Las tareas de roles y de dos `DataSource` entran al bloque B, antes de las pruebas de aislamiento. Si llegaran después, B4 a B8 pasarían en falso.
- Una consulta sin contexto de workspace falla con excepción, y hay una prueba que lo exige.
- `app_user` tiene RLS: la aplicación solo ve su propia fila. Cualquier búsqueda de personas por correo queda descartada por diseño.
- El filtro que abre la transacción tiene dos fases (resolver pertenencia, luego fijar workspace) dentro de la misma transacción.
- La regla "Contexto de tenant" de `CLAUDE.md` y la sección de aislamiento de `docs/modelo-datos.md` se actualizan para reflejar esto.
- Cambiar de modelo de roles (por ejemplo, un esquema por workspace) exige un ADR nuevo que reemplace a este.

## Decisiones aún abiertas

- Si la validación de `email_verified` es posible depende del proveedor de identidad, que aún no está elegido. Si el proveedor no la garantiza, la aceptación de invitaciones necesita otro mecanismo de confirmación.
