# ContentPlan — Backlog

Actualizado: 2026-11-06

## Cómo usar este backlog

Cada tarea cabe en una sesión de 2 a 3 horas y termina con algo que funciona. Si una tarea no cabe, está mal partida: divídela antes de empezar.

Solo la Fase 1 está descompuesta. Las fases siguientes quedan enunciadas al final.

### Qué cuenta como terminada

1. El código corre en local sin errores
2. Tiene al menos una prueba que falla si se rompe
3. La documentación afectada quedó actualizada
4. Está en un commit con mensaje que explica el porqué, no solo el qué

### Marcas en las tareas

Las tareas marcadas **(seguridad)** no se posponen. Son las que no se pueden agregar después sin rehacer trabajo.

Las marcadas **(bloquea)** tienen otras esperando.

## Dependencias de la Fase 1

Siete bloques, 92 tareas. Una flecha significa que el bloque de abajo no puede empezar hasta que el de arriba esté cerrado.

```
           A · Cimientos (11 tareas)
                        │
           B · Aislamiento (11 tareas)
                        │
           C · Identidad (14 tareas)
          ┌─────────────┼─────────────┐
   D · Tenencia   E · Producción   F · Archivos
    (9 tareas)     (15 tareas)      (15 tareas)

   G · Frontend (17 tareas) va encima de D, E y F
```

Los bloques A y B son los cimientos y no admiten atajos. D, E y F dependen de C pero no entre sí, así que su orden es negociable. El sugerido es D, E, F porque cargar tus clientes reales temprano revela errores del modelo mientras todavía es barato corregirlos.

## Bloque A — Cimientos

Un esqueleto que arranca y al que se le puede agregar todo lo demás. Nada de lógica de negocio todavía.

- [x] **A1. Estructura del monorepo** — crear `api/`, `apps/web/`, `infrastructure/`, `docs/`, con `.gitignore` y README de arranque
- [x] **A2. Docker Compose con PostgreSQL** — levantar la base en local, con volumen persistente y variables de entorno, sin credenciales en Git *(seguridad)*
- [x] **A3. Proyecto Spring Boot** — Java 21, dependencias base, `GET /health` respondiendo *(bloquea)*
- [x] **A4. Flyway conectado** — migración `V1` vacía que corre al arrancar y se verifica en la tabla de historial
- [x] **A5. Testcontainers funcionando** — una prueba que levanta PostgreSQL real y verifica que `/health` responde *(bloquea el bloque B)*
- [x] **A6. OpenAPI expuesto** — Swagger UI accesible en local
- [x] **A7. ADR inicial** — registrar en `docs/adr/` las decisiones del documento de modelo de datos
- [x] **A8. Pipeline de CI en GitHub Actions** — compilar, correr pruebas y guardar el reporte, en cada push y cada pull request
- [x] **A9. Dockerfile de la API** — imagen que arranca en local y se construye también en CI
- [x] **A10. Prueba de migraciones** — Flyway aplica desde cero sobre base vacía, y reiniciar no rompe nada
- [x] **A11. Logs estructurados** — JSON con identificador de correlación por petición, y regla de no registrar datos personales ni tokens *(seguridad, bloquea)*

A5 parece opcional y no lo es: sin Testcontainers no se pueden probar las políticas de aislamiento del bloque siguiente. A8 viene justo después porque necesita pruebas que correr.

## Bloque B — Aislamiento

Todo este bloque es seguridad y va temprano. Agregarlo después obliga a rehacer el acceso a datos completo.

- [x] **B1. Tabla `workspace` y migración** — la tabla raíz; sin fila de prueba en la migración (los datos de prueba los inserta cada prueba) y sin RLS hasta B2 *(bloquea)*
- [x] **B2. Activar RLS en `workspace`** — política `USING` y `WITH CHECK` con `FORCE`, como ensayo del patrón *(seguridad)*
- [x] **B3. Filtro de contexto de sesión** — fijar `app.user_id` y `app.workspace_id` con `set_config(..., true)` y parámetros enlazados al abrir la transacción, en un único punto; las políticas usan `current_workspace()` *(seguridad, bloquea)*
- [x] **B4. Prueba de fuga entre workspaces** — dos workspaces con datos, verificar que A no ve nada de B *(seguridad)*
- [x] **B5. Prueba de escritura cruzada** — intentar insertar con el `workspace_id` ajeno y verificar que falla *(seguridad)*
- [x] **B6. Prueba de lectura por id directo** — pedir una fila de B por su UUID desde A y verificar que no aparece *(seguridad)*
- [x] **B7. Plantilla de migración con RLS** — un fragmento reutilizable, para que toda tabla nueva nazca con su política
- [x] **B8. Prueba de concurrencia** — peticiones simultáneas de dos workspaces, verificando que el contexto no sobrevive entre conexiones del pool *(seguridad)*
- [x] **B9. Tres roles de PostgreSQL** — dueño del esquema, aplicación y superusuario, creados en el script de inicio de Docker Compose y de Testcontainers (no en migraciones, para no poner contraseñas en Git); las migraciones solo hacen `GRANT` y `ALTER DEFAULT PRIVILEGES`. **Se hace justo después de B1**, antes de B2 *(seguridad, bloquea)*
- [x] **B10. Dos `DataSource`** — el del dueño solo para Flyway, el de aplicación para todo lo demás; las pruebas de aislamiento se conectan con el de aplicación y verifican que no es superusuario ni `BYPASSRLS`. **Se hace antes de B3** *(seguridad, bloquea)*
- [x] **B11. Prueba de consulta sin contexto** — una consulta sin `app.workspace_id` fijado falla con excepción, no con cero filas; incluye el caso de cadena vacía *(seguridad)*

B9 y B10 llevan números altos para no renumerar las referencias, pero se ejecutan en este orden: B1, B9, B2, B10, B3 y el resto. Si llegaran después, B4 a B8 pasarían en falso: un superusuario se salta RLS y las pruebas no probarían nada. Ver [ADR 0003](adr/0003-contexto-de-sesion-y-roles-de-postgres.md).

En esta etapa el usuario y el workspace se pueden tomar de cabeceras fijas de prueba. Lo importante es que el mecanismo exista y esté probado antes de que haya tablas que proteger. En el bloque C solo cambia de dónde sale el valor.

B8 es la prueba menos obvia y la más importante: el bug que de verdad filtra datos entre clientes no aparece con una petición a la vez, sino cuando el pool reutiliza una conexión y el workspace anterior sobrevive.

## Bloque C — Identidad

Aquí se conecta el proveedor OIDC (Google, directo) y el workspace deja de venir de una cabecera de prueba. El registro es abierto y las invitaciones son por enlace ([ADR 0004](adr/0004-proveedores-de-identidad-y-correo.md), [ADR 0006](adr/0006-registro-abierto-administrador-de-plataforma-y-buzon.md)).

- [x] **C1. Cliente OAuth de Google** — proyecto en Google Cloud con un cliente OAuth web, la pantalla de consentimiento en modo de pruebas y el ID de cliente documentado como variable de entorno; cualquier secreto de cliente solo en variables de entorno, y sin Keycloak ni otro servicio de identidad en Docker Compose. Al crearlo, verificar qué pide la pantalla de consentimiento para publicarla (enlaces a términos y privacidad, dominio). El tenant de Auth0 creado el 2026-10-31 ya fue eliminado *(bloquea)*
- [x] **C2. Spring Security como resource server** — validar el token de Google (emisor `https://accounts.google.com` y audience igual al ID de cliente) y rechazar peticiones sin él; confirmar que Spring valida ese token con esos parámetros; las pruebas usan tokens JWT simulados, nunca Google real *(seguridad)*. Solo autentica: el contexto de tenant sigue saliendo de la cabecera de prueba hasta C4, y las pruebas HTTP del bloque B ahora mandan también un token simulado. Sin probar contra Google real hasta G1 (ADR 0004)
- [x] **C3. Tablas `app_user` y `membership`** — migración con sus llaves y las políticas RLS sobre `app.user_id` en ambas (la de `membership` también deja ver las filas del workspace activo). `app_user` incluye `max_workspaces` (1 por defecto) y la marca de administrador de la plataforma. La aplicación solo las lee: se escriben por funciones `SECURITY DEFINER` de C4, C9, C14 y C15. Incluye `puede_ver_cliente()`, la segunda frontera por cliente. `membership.client_id` no tiene llave foránea hasta D1, y un `CHECK` exige que los roles de agencia no lo lleven y los de cliente sí
- [x] **C4. Conectar el token al contexto** — `app.user_id` sale del token; el workspace activo sale del encabezado `X-Workspace-Id`, validado contra `membership` (uno solo: se usa ese; varios sin encabezado: se rechaza); una persona autenticada sin `membership` solo puede llamar a crear workspace (C14) y a aceptar invitación (C9); se elimina la cabecera de prueba *(seguridad)*. Hecha: `V6__resolver_usuario.sql` traduce el `sub` al `uuid` y registra a la persona; el administrador de la plataforma no entra a un workspace ajeno hasta E13 (anotación `TODO(E13)` en `ResolutorDeContexto`)
- [ ] **C5. Roles y autorización** — los cuatro roles del modelo (propietario, editor, aprobador, colaborador), con verificación en los endpoints. El propietario puede aprobar en nombre del cliente; el editor no aprueba ni rechaza ni mueve una pieza fuera de `En aprobación`; el colaborador solo comenta ([ADR 0005](adr/0005-roles-de-agencia-y-cliente-y-comentarios.md)) *(seguridad)*
- [ ] **C6. Buzón de notificaciones** — tabla `notification` con canal (hoy solo la aplicación), marca de importante y fecha de lectura; endpoints para listar y marcar como leídas. La abstracción de canales queda lista para WhatsApp en la Fase 2. No hay correo en el MVP
- [ ] **C8. Tabla `invitation` y emisión** — el propietario escribe el correo de la persona, el rol y, para roles de cliente, el cliente; se devuelve un enlace de un solo uso con expiración que se muestra una vez y se comparte a mano, sin enviar nada. Se guarda el hash del token, con revocación; sin `FORCE` y con función `SECURITY DEFINER` de consulta por hash, con `search_path` fijo; nunca se busca a la persona por correo
- [ ] **C9. Aceptar invitación** — función `SECURITY DEFINER` que consume el token y crea la `membership` de forma atómica, exigiendo `email_verified` (lo garantiza Google) y correo igual al de la invitación (normalizado); un enlace usado, vencido o revocado se rechaza; el formulario lleva dos casillas separadas, términos y privacidad, nunca una sola *(seguridad)*
- [ ] **C10. Pruebas de autorización** — con tokens JWT simulados, verificar que un aprobador no puede editar y que un rol de cliente no ve otros clientes, incluida la segunda frontera por `client_id` dentro de la política; que un editor no puede aprobar ni rechazar, que un colaborador solo comenta, y que una decisión del propietario queda marcada como hecha en nombre del cliente. **Alcance real en el bloque C:** `puede_ver_cliente()` ya está probada en C3, pero la prueba completa de que un aprobador no ve datos de otro cliente necesita una tabla con `client_id` y su política, y esas llegan en D1 y D4. Esa parte se agrega entonces, no aquí
- [ ] **C11. Escaneo de dependencias** — Dependabot activo y OWASP dependency-check semanal, con llave de la NVD en secrets *(seguridad)*
- [ ] **C12. Registro de aceptación de términos** — tabla con usuario, documento, versión aceptada, fecha e IP; se versiona porque al cambiar los términos hay que saber quién aceptó cuál; la IP no va a los logs *(bloquea el registro de usuarios y C9)*
- [ ] **C13. Rate limit** — en login, aceptar invitación, aprobación y crear workspace; en memoria basta para el MVP *(seguridad)*
- [ ] **C14. Primer ingreso y límite de workspaces** — una función `SECURITY DEFINER`, con `search_path` fijo, crea el `workspace` y la `membership` de propietario de forma atómica y verifica `max_workspaces` bloqueando la fila de `app_user`; el perfil (nombre, apellido, industria) se guarda con ella. Pruebas: el segundo workspace se rechaza, también con dos peticiones simultáneas; una persona sin `membership` no llega a ningún otro endpoint *(seguridad)*
- [ ] **C15. Administrador de la plataforma, primera parte** — la marca de `app_user` se asigna a mano con SQL y nunca por la API; funciones `SECURITY DEFINER` acotadas para listar workspaces y cambiar `max_workspaces`, que verifican la marca en la base. Pruebas: un usuario normal no puede ejecutarlas por ningún medio, y un claim falso en el token no basta. La entrada a un workspace ajeno se completa en E13, que crea `audit_log` *(seguridad)*

C7 (adaptador de correo) salió del MVP y se conserva el número para no renumerar: vive en la Fase 2.

La invitación debe invalidarse al usarse, no solo al expirar. Un enlace reutilizable y reenviado es una puerta abierta. Y no basta con el token: el correo verificado de quien acepta debe coincidir con el de la invitación, o el enlace pasa a ser un bearer. El límite de intentos vive en C13, no en la función: un hash que no existe no tiene fila donde contar.

C11 espera hasta aquí a propósito: escanear dependencias en la semana 2, con cuatro librerías, no dice nada. Va en un workflow aparte del CI, porque tarda y falla por CVE publicadas anoche, no por tu commit. C6 entra antes que las invitaciones porque el buzón es donde llegan los avisos; sin correo, el enlace copiable de C8 es la forma de entrega.

## Bloque D — Tenencia

Clientes, marcas y cuentas. El primero que vas a poder usar con datos reales.

- [ ] **D1. Tabla `client` con RLS** — migración con la plantilla de `docs/plantilla-migracion-rls.sql`, entidad JPA, repositorio; incluye las pruebas de aislamiento (fuga, escritura cruzada, lectura por id directo y consulta sin contexto) sobre `client` *(seguridad)*
- [ ] **D2. CRUD de clientes** — endpoints con validación y pruebas
- [ ] **D3. Propiedad y retención por cliente** — campos que reflejan lo pactado en el contrato: dueño de crudos, dueño de entregables, meses de retención *(bloquea el borrado de F13)*
- [ ] **D4. Tabla `brand` con RLS** — colgada de `client`, con los campos del perfil creativo
- [ ] **D5. CRUD de marcas** — incluida la validación de que una marca pertenece a un cliente del mismo workspace *(seguridad)*
- [ ] **D6. Tabla `social_account` con RLS** — plataforma, identificador de cuenta, estado
- [ ] **D7. CRUD de cuentas sociales** — sin credenciales todavía, solo el registro
- [ ] **D8. Cargar tus tres clientes reales** — Iglesia sin Límites con dos marcas, Beraca con una marca y dos cuentas, Altocar
- [ ] **D9. Respaldo automático de PostgreSQL** — con prueba de restauración; entra en el momento en que cargues tus clientes reales. **La estrategia (frecuencia, destino, prueba de restauración) se define al empezar el bloque D, antes de D8** *(seguridad, bloquea G12)*

Las pruebas de aislamiento de D1 repiten las de `TablaDeNegocioRlsTest` (B4 a B6 y B11), que hoy corren sobre una tabla de prueba. Cuando exista `client`, conviene convertir esa clase en una base parametrizada por tabla, para que cada tabla de negocio nueva la reutilice con pocas líneas. `PoliticasRlsTest` ya vigila por sí sola que toda tabla con `workspace_id` tenga RLS activa, forzada y con `USING` y `WITH CHECK`; no prueba el comportamiento.

D8 no es relleno. Cargar datos reales revela de inmediato si el modelo sirve: campos que faltan, campos que sobran, nombres que no encajan. Hazla antes de construir el frontend, mientras cambiar el esquema todavía es barato.

Las credenciales de las APIs de redes llegan en la Fase 4, cifradas con una llave gestionada, nunca en texto plano.

## Bloque E — Producción

El corazón del producto: piezas, estados, versiones y aprobaciones.

- [ ] **E1. Tabla `content_piece` con RLS** — título, descripción, fecha objetivo, estado, marca
- [ ] **E2. Crear y listar piezas** — endpoints con filtro por marca y por estado
- [ ] **E3. Enum de estados** — los ocho estados del modelo, como tipo en base y en código
- [ ] **E4. Máquina de transiciones** — tabla de transiciones permitidas y rechazo de las inválidas *(bloquea)*
- [ ] **E5. Endpoint de cambio de estado** — una sola puerta para mover una pieza, nunca edición libre del campo; además valida qué rol puede disparar cada transición (el editor no mueve una pieza fuera de `En aprobación`) *(seguridad)*
- [ ] **E6. Pruebas de transiciones** — verificar que agendado a publicado falla y que el camino válido pasa
- [ ] **E7. Tabla `piece_target`** — la relación muchos a muchos entre pieza y cuentas destino
- [ ] **E8. Asignar destinos a una pieza** — incluida la validación de que la cuenta pertenece a la misma marca *(seguridad)*
- [ ] **E9. Tabla `piece_version`** — con `round_number` y referencia al entregable
- [ ] **E10. Crear versión** — el número de ronda se calcula solo, nunca lo manda el cliente
- [ ] **E11. Tabla `review`** — decisión, revisor, comentario, fecha
- [ ] **E12. Aprobar o rechazar** — el rechazo mueve la pieza a edición y habilita la ronda siguiente; el propietario puede decidir en nombre del cliente y la decisión queda marcada con `decided_on_behalf`
- [ ] **E13. Tabla `audit_log`** — solo inserciones, alimentada por los eventos clave (cambios de estado, decisiones, creación de workspace, aceptación de invitaciones, cambios de límite y borrados). Aquí se completa C15: el administrador de la plataforma puede entrar a un workspace ajeno en el paso que valida `X-Workspace-Id`, y cada petición suya deja una fila ([ADR 0006](adr/0006-registro-abierto-administrador-de-plataforma-y-buzon.md)). Prueba: sin fila no hay acceso, y un usuario normal no puede entrar a un workspace ajeno. Al hacerla, cambia a 200 con su fila la prueba `elAdministradorDeLaPlataformaNoEntraAUnWorkspaceAjeno...` de `ContextoDeTokenTest`, y se agrega la rama en `ResolutorDeContexto.elegir` *(seguridad)*
- [ ] **E14. Pruebas del ciclo completo** — crear pieza, versión, rechazo, versión 2, aprobación
- [ ] **E15. Tabla `comment` y comentar** — opinión suelta sobre una versión, con `workspace_id`, RLS y la segunda frontera por `client_id`. El colaborador la escribe, el editor y el propietario la leen, y no cambia el estado de la pieza. `text` se puede vaciar como `review.comment`. Prueba: un colaborador comenta y no puede aprobar. Se hace después de E11 y antes de E14, como B9 y B10 (necesita C5)

E4 es la más importante del bloque. Si las transiciones no están centralizadas, cada endpoint nuevo va a poder mover el estado a su manera.

Cuando E14 pase, el flujo del MVP funciona de extremo a extremo por API. Ese es el hito del backend.

## Bloque F — Archivos

El registro de archivos y la subida a S3. El NAS queda para la Fase 2.

- [ ] **F1. Tabla `asset` con RLS** — nombre, tipo, tamaño, `checksum` y `checksum_algorithm`, ubicación, quién subió
- [ ] **F2. Registro manual de archivo** — crear la ficha sin subir nada, con `location_kind` en `registered`
- [ ] **F3. Almacenamiento S3 en local** — MinIO en Docker Compose, compatible con S3, sin costo
- [ ] **F4. Emitir URL prefirmada de subida** — expiración corta, alcance limitado a una clave *(seguridad)*
- [ ] **F5. Confirmar subida** — el cliente avisa, el backend verifica y crea la ficha
- [ ] **F6. Guardar el checksum de S3** — pedir SHA-256 al prefirmar (los SDK usan CRC32 por defecto) y leer `x-amz-checksum-sha256`, sin leer el archivo en el backend; en multiparte es compuesto, así que se guarda con su algoritmo
- [ ] **F7. Emitir URL prefirmada de descarga** — solo para quien tiene acceso a esa pieza *(seguridad)*
- [ ] **F8. Asociar entregable a versión** — enlazar `asset` con `piece_version`, con estado `subiendo` / `procesando` / `lista`
- [ ] **F9. Prueba de acceso cruzado** — verificar que un usuario de otro workspace no obtiene URL de descarga *(seguridad)*
- [ ] **F10. Subida multiparte** — para crudos grandes, con reanudación si se corta la conexión
- [ ] **F11. Límite y validación de tamaño** — 500 MB para entregables; los crudos usan multiparte, y el tope duro de una subida simple es 5 GB
- [ ] **F12. Miniatura del entregable** — ffmpeg sobre el fotograma al 10% de la duración, leyendo por rango desde S3, dentro de la confirmación de subida; con tiempo máximo (si no se puede, la pieza queda sin miniatura y la subida no falla) y lectura de la cola si el índice del MP4 está al final
- [ ] **F13. Borrado selectivo de un cliente** — API que lista todo lo suyo y elimina lo que el administrador escoja, dejando fila de registro *(seguridad)*
- [ ] **F14. Reporte semanal de retención vencida** — lista de archivos cuyo plazo de retención ya venció, enviada al buzón de notificaciones; necesita `asset` (F1), los campos de retención de D3 y el buzón de C6
- [ ] **F15. Probar Cloudflare R2 con SHA-256** — con un bucket real (activarlo puede pedir tarjeta; se queda dentro del plan gratuito), verificar: URL prefirmada de subida con `x-amz-checksum-sha256` firmado, que el checksum se pueda leer después (`ChecksumMode`), lectura por rango (para F12) y subida multiparte con su checksum compuesto. La documentación oficial de R2 solo lista `Content-MD5` y no documenta SHA-256. **Se hace justo después de F3 y antes de F4 y F6, y es el momento de validar el proveedor de S3 que se había aplazado** *(bloquea F4 y F6)*. Si falla: o se adapta el diseño a `Content-MD5` (cambia la sección "Archivos" de `modelo-datos.md` y el ADR) o se elige otro proveedor compatible con S3. El resultado va en un ADR

MinIO habla el mismo protocolo que S3 y corre en tu máquina sin costo. Al pasar a producción solo cambia la configuración.

F9 cierra el círculo: RLS protege la base, pero una URL prefirmada vive fuera de ella. F12 no necesita worker: el entregable es un video de redes, de decenas de megas. Es además la única excepción a "los archivos nunca pasan por el backend": ffmpeg lee los rangos desde la API. F13 y F14 dependen de D3: qué se puede borrar y cuándo vence la retención lo decide el contrato de cada cliente.

## Bloque G — Frontend

Las pantallas mínimas para operar el flujo. Funcionalidad antes que estética.

- [ ] **G0. Prototipo para validar con los funcionales** — pantallas navegables con datos de ejemplo, sin backend, para que el equipo funcional pruebe el flujo y los roles antes del bloque G. Hecho como artefacto de claude.ai el 2026-10-31; falta decidir si se guarda una copia en el repositorio. El 2026-11-03 se hizo aparte una maqueta del acceso (ingreso con Google, primer ingreso, invitaciones por enlace, buzón y administración de la plataforma). No sustituye a G1 a G16 *(no bloquea)*
- [ ] **G1. Proyecto Next.js y autenticación** — inicio de sesión con Google (Auth.js con el proveedor de Google), sesión persistente y renovación del token, que dura más o menos una hora; con dos zonas de rutas, `/agencia` y `/cliente` ([ADR 0004](adr/0004-proveedores-de-identidad-y-correo.md), [ADR 0005](adr/0005-roles-de-agencia-y-cliente-y-comentarios.md))
- [ ] **G2. Cliente de API tipado** — generado desde OpenAPI, para no escribir tipos a mano
- [ ] **G3. Pantalla de clientes y marcas** — listar, crear, editar, incluidos propiedad y retención
- [ ] **G4. Pantalla de cuentas sociales** — asociadas a su marca
- [ ] **G5. Tablero de piezas** — columnas por estado, con filtro por marca y miniatura *(bloquea)*
- [ ] **G6. Detalle de pieza** — datos, destinos, historial de versiones
- [ ] **G7. Subir versión** — subida directa a S3 con la URL prefirmada, con barra de progreso
- [ ] **G8. Vista de revisión del cliente** — ver el video y aprobar o pedir cambios con comentario
- [ ] **G9. Pantalla de invitaciones** — generar el enlace para una persona, con su rol y su cliente; copiarlo, ver su estado y revocarlo
- [ ] **G10. Pantalla de borrado selectivo** — ver todo lo de un cliente y escoger qué eliminar, con confirmación *(seguridad)*
- [ ] **G11. Monitoreo y logs centralizados** — un servicio alojado donde mirar los logs ya estructurados de A11
- [ ] **G12. Despliegue continuo** — staging automático desde `main`, producción con aprobación manual *(seguridad)*
- [ ] **G13. Prueba de humo de extremo a extremo** — Playwright: login, subir versión, aprobar. Una sola, no una suite
- [ ] **G14. Primer ingreso** — formulario de perfil (nombre y apellido que vienen de Google, industria) y creación del workspace (C14), con el aviso del límite de un workspace por usuario
- [ ] **G15. Buzón de notificaciones** — lista con filtros de todos, sin leer e importantes, marcar como leída y contador en el menú (C6)
- [ ] **G16. Administración de la plataforma** — solo para el administrador: workspaces, límite por usuario editable y entrada a un workspace ajeno con su registro (C15 y E13)

G8 es la pantalla que más importa: es la única que ven tus clientes y la que reemplaza los audios de WhatsApp. Debe funcionar bien en celular.

G12 no se hace antes de tener respaldo de Postgres probado (D9): desplegar automático a producción sin respaldo es cómo se pierden datos de clientes.

## Orden sugerido

No se proyectan semanas. La estimación inicial (dos a tres tareas por semana) quedó desmentida: los bloques A y B se cerraron entre el 2026-10-26 y el 2026-10-29. El ritmo se recalibrará al cerrar el bloque C, que sí depende de terceros (proveedor OIDC, correo) y es más representativo que B.

| Bloque | Al final tienes |
| --- | --- |
| A | Un backend que arranca, migraciones, CI en verde y logs |
| B | Aislamiento probado, el cimiento de seguridad |
| C | Login con Google, registro con su workspace, roles, invitaciones por enlace, buzón, administrador y escaneo |
| D | Tus tres clientes cargados de verdad |
| E | El flujo completo funcionando por API |
| F | Subida, descarga, miniaturas y borrado selectivo |
| G | La aplicación usable por ti y por tus clientes, desplegada |

### Tres hitos para no perder el ánimo

1. **Aislamiento probado** — cumplido el 2026-10-29 (cierre del bloque B). No se ve, pero es lo que hace vendible el producto
2. **Clientes reales cargados** (cierre de D) — por primera vez el sistema habla de tu operación
3. **Ciclo completo por API** (cierre de E) — el producto existe, aunque todavía sin pantallas

### Si el ritmo se cae

El orden importa más que la velocidad. Los bloques A y B no se saltan ni se recortan. Si hay que recortar, recorta del bloque G: una pantalla fea que funciona sirve; un aislamiento a medias, no.

## Decisiones aplazadas y cuándo retomarlas

Ninguna se asume. Cada una tiene su momento para volver a sentarse a decidirla:

| Qué | Cuándo |
| --- | --- |
| Textos legales (términos, privacidad, cookies) | Antes de C12 |
| Comprar el dominio | Al cerrar el bloque C, antes del G. Lo piden la pantalla de consentimiento de Google en producción, el despliegue y la verificación ante Meta |
| Estrategia de respaldo de PostgreSQL | Al empezar el bloque D, antes de D8 |
| Proveedor de almacenamiento S3 (probar R2 con SHA-256) | Al empezar el bloque F, en F15 |
| Verificar los riesgos de la pantalla de consentimiento de Google y de la Ley 1581 | En C1 y antes de D8 |
| Verificación de empresa ante Meta | Antes de la Fase 2 |
| Umbral de registro en el RNBD | Antes de tener datos reales de terceros |
| Proveedor de alojamiento | Antes de G12 |

## Fases siguientes

Enunciadas, no descompuestas.

### Fase 2 — Operación

Calendario editorial, formularios de solicitud de grabación, sesiones de grabación, panel de pendientes, ideas y guiones. Integración del NAS con sincronización y verificación por checksum.

Adaptador de WhatsApp solo para los avisos importantes, que ya están marcados en `notification` desde C6; el resto se queda en el buzón. Requiere cuenta de empresa verificada ante Meta y plantillas aprobadas una por una, así que el trámite empieza antes que el código.

Adaptador de correo (antes C7): Resend u otro proveedor, con plantillas y registro de envíos. Necesita un dominio propio comprado y acceso a su DNS para verificar el remitente (SPF, DKIM). Se retoma cuando exista el dominio y haya una razón para enviar correo.

### Fase 3 — Asistencia con IA

Generación de ideas, guiones, copies y descripciones a partir del perfil creativo de cada marca. Depende de que el bloque D tenga campos de perfil bien poblados.

### Fase 4 — Integración y comercialización

APIs de Instagram, TikTok y YouTube, publicación automatizada, lectura de métricas, suscripciones, cuotas y pagos. Límite configurable de rondas de revisión.

### Trabajo continuo, no es una fase

- [ ] Documentación de tratamiento de datos personales en `docs/`
- [ ] Contrato de servicio con propiedad de archivos y retención, y autorizaciones de uso de imagen firmadas por quienes aparecen en video
- [ ] Redactar los términos y condiciones, la política de privacidad y la política de cookies (C12 necesita el texto de las dos primeras antes del registro de usuarios). La política de privacidad debe decir que el administrador de la plataforma puede acceder al contenido de los workspaces y que la identidad pasa por Google
- [ ] Verificar los derechos de las imágenes que usa el frontend
- [ ] Mantener las acciones de GitHub fijadas por SHA, no por etiqueta *(seguridad)*

El respaldo de PostgreSQL ya no es trabajo continuo: es la tarea D9 y bloquea el despliegue continuo del bloque G.

### Decisiones de fase posterior

No se toman ahora y no bloquean el MVP:

- Compresión y source maps del frontend. Los source maps no se publican en producción
- Caché
- Escalabilidad
- Política de reembolso
- Elegir el proveedor de hosting
