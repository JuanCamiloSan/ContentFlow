# ContentFlow — Backlog

Actualizado: 2026-10-31

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

Siete bloques, 88 tareas. Una flecha significa que el bloque de abajo no puede empezar hasta que el de arriba esté cerrado.

```
           A · Cimientos (11 tareas)
                        │
           B · Aislamiento (11 tareas)
                        │
           C · Identidad (13 tareas)
          ┌─────────────┼─────────────┐
   D · Tenencia   E · Producción   F · Archivos
    (9 tareas)     (15 tareas)      (15 tareas)

   G · Frontend (14 tareas) va encima de D, E y F
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

Aquí se conecta el proveedor OIDC y el workspace deja de venir de una cabecera de prueba.

- [ ] **C1. Tenant de Auth0 y API registrada** — tenant de desarrollo con su API (audience `https://api.contentflow`) y la aplicación del frontend, con las credenciales solo en variables de entorno y sin Keycloak en Docker Compose ([ADR 0004](adr/0004-proveedores-de-identidad-y-correo.md)). El tenant, la API y la aplicación ya se crearon a mano en el panel el 2026-10-31; falta dejar documentadas las variables de entorno *(bloquea)*
- [ ] **C2. Spring Security como resource server** — validar el token (emisor y audience) y rechazar peticiones sin él; las pruebas usan tokens JWT simulados, nunca Auth0 real *(seguridad)*
- [ ] **C3. Tablas `app_user` y `membership`** — migración con sus llaves y las políticas RLS sobre `app.user_id` en ambas (la de `membership` también deja ver las filas del workspace activo)
- [ ] **C4. Conectar el token al contexto** — `app.user_id` sale del token; el workspace activo sale del encabezado `X-Workspace-Id`, validado contra `membership` (uno solo: se usa ese; varios sin encabezado: se rechaza); se elimina la cabecera de prueba *(seguridad)*
- [ ] **C5. Roles y autorización** — los cuatro roles del modelo (propietario, editor, aprobador, colaborador), con verificación en los endpoints. El propietario puede aprobar en nombre del cliente; el editor no aprueba ni rechaza ni mueve una pieza fuera de `En aprobación`; el colaborador solo comenta ([ADR 0005](adr/0005-roles-de-agencia-y-cliente-y-comentarios.md)) *(seguridad)*
- [ ] **C6. Abstracción de notificaciones** — tabla `notification`, canal por tipo de evento, preferencia por usuario *(bloquea C7)*
- [ ] **C7. Adaptador de correo** *(se aplaza hasta comprar el dominio; mientras tanto C8 entrega la invitación por enlace copiable)* — Resend (ADR 0004), plantillas básicas, registro de envíos. Antes de empezar, verificar que el plan gratuito de Auth0 deja usar un proveedor de correo propio. **Necesita un dominio propio comprado y acceso a su DNS** para verificar el remitente (SPF, DKIM); compra pendiente
- [ ] **C8. Tabla `invitation` y emisión** — crear invitación con token de un solo uso y expiración, devuelta como un enlace que el administrador copia y entrega a mano, y enviada además por correo cuando exista el adaptador de C7; sin `FORCE` y con función `SECURITY DEFINER` de consulta por hash, con `search_path` fijo; nunca se busca a la persona por correo. La API de administración de Auth0 se llama con un token M2M guardado en caché (el plan gratuito da 1.000 tokens M2M al mes); aquí se decide si se desactiva el registro abierto (*Disable Sign Ups*), lo que obligaría a crear las cuentas de los invitados desde el backend
- [ ] **C9. Aceptar invitación** — función `SECURITY DEFINER` que consume el token y crea la `membership` de forma atómica, exigiendo `email_verified` y correo igual al de la invitación (normalizado); el formulario lleva dos casillas separadas, términos y privacidad, nunca una sola *(seguridad)*
- [ ] **C10. Pruebas de autorización** — con tokens JWT simulados, verificar que un aprobador no puede editar y que un rol de cliente no ve otros clientes, incluida la segunda frontera por `client_id` dentro de la política; que un editor no puede aprobar ni rechazar, que un colaborador solo comenta, y que una decisión del propietario queda marcada como hecha en nombre del cliente
- [ ] **C11. Escaneo de dependencias** — Dependabot activo y OWASP dependency-check semanal, con llave de la NVD en secrets *(seguridad)*
- [ ] **C12. Registro de aceptación de términos** — tabla con usuario, documento, versión aceptada, fecha e IP; se versiona porque al cambiar los términos hay que saber quién aceptó cuál; la IP no va a los logs *(bloquea el registro de usuarios y C9)*
- [ ] **C13. Rate limit** — en login, aceptar invitación y aprobación; en memoria basta para el MVP *(seguridad)*

La invitación debe invalidarse al usarse, no solo al expirar. Un token reutilizable en un correo reenviado es una puerta abierta. Y no basta con el token: el correo verificado de quien acepta debe coincidir con el de la invitación, o el token pasa a ser un bearer. El límite de intentos vive en C13, no en la función: un hash que no existe no tiene fila donde contar.

C11 espera hasta aquí a propósito: escanear dependencias en la semana 2, con cuatro librerías, no dice nada. Va en un workflow aparte del CI, porque tarda y falla por CVE publicadas anoche, no por tu commit. C6 y C7 entran antes que las invitaciones porque una invitación sin forma de enviarla no sirve; mientras C7 espere al dominio, el enlace copiable de C8 cubre esa forma de entrega.

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
- [ ] **D9. Respaldo automático de PostgreSQL** — con prueba de restauración; entra en el momento en que cargues tus clientes reales *(seguridad, bloquea G12)*

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
- [ ] **E13. Tabla `audit_log`** — solo inserciones, alimentada por los cambios de estado y las decisiones *(seguridad)*
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
- [ ] **F14. Reporte semanal de retención vencida** — lista de archivos cuyo plazo de retención ya venció, enviada por el canal de notificaciones; necesita `asset` (F1), los campos de retención de D3 y las notificaciones de C6 y C7
- [ ] **F15. Probar Cloudflare R2 con SHA-256** — con un bucket real (activarlo puede pedir tarjeta; se queda dentro del plan gratuito), verificar: URL prefirmada de subida con `x-amz-checksum-sha256` firmado, que el checksum se pueda leer después (`ChecksumMode`), lectura por rango (para F12) y subida multiparte con su checksum compuesto. La documentación oficial de R2 solo lista `Content-MD5` y no documenta SHA-256. **Se hace justo después de F3 y antes de F4 y F6** *(bloquea F4 y F6)*. Si falla: o se adapta el diseño a `Content-MD5` (cambia la sección "Archivos" de `modelo-datos.md` y el ADR) o se elige otro proveedor compatible con S3. El resultado va en un ADR

MinIO habla el mismo protocolo que S3 y corre en tu máquina sin costo. Al pasar a producción solo cambia la configuración.

F9 cierra el círculo: RLS protege la base, pero una URL prefirmada vive fuera de ella. F12 no necesita worker: el entregable es un video de redes, de decenas de megas. Es además la única excepción a "los archivos nunca pasan por el backend": ffmpeg lee los rangos desde la API. F13 y F14 dependen de D3: qué se puede borrar y cuándo vence la retención lo decide el contrato de cada cliente.

## Bloque G — Frontend

Las pantallas mínimas para operar el flujo. Funcionalidad antes que estética.

- [ ] **G0. Prototipo para validar con los funcionales** — pantallas navegables con datos de ejemplo, sin backend, para que el equipo funcional pruebe el flujo y los roles antes del bloque G. Hecho como artefacto de claude.ai el 2026-10-31; falta decidir si se guarda una copia en el repositorio. No sustituye a G1 a G13 *(no bloquea)*
- [ ] **G1. Proyecto Next.js y autenticación** — login contra el proveedor OIDC y sesión persistente, con dos zonas de rutas, `/agencia` y `/cliente` ([ADR 0005](adr/0005-roles-de-agencia-y-cliente-y-comentarios.md))
- [ ] **G2. Cliente de API tipado** — generado desde OpenAPI, para no escribir tipos a mano
- [ ] **G3. Pantalla de clientes y marcas** — listar, crear, editar, incluidos propiedad y retención
- [ ] **G4. Pantalla de cuentas sociales** — asociadas a su marca
- [ ] **G5. Tablero de piezas** — columnas por estado, con filtro por marca y miniatura *(bloquea)*
- [ ] **G6. Detalle de pieza** — datos, destinos, historial de versiones
- [ ] **G7. Subir versión** — subida directa a S3 con la URL prefirmada, con barra de progreso
- [ ] **G8. Vista de revisión del cliente** — ver el video y aprobar o pedir cambios con comentario
- [ ] **G9. Pantalla de invitaciones** — invitar a una persona a un cliente con su rol
- [ ] **G10. Pantalla de borrado selectivo** — ver todo lo de un cliente y escoger qué eliminar, con confirmación *(seguridad)*
- [ ] **G11. Monitoreo y logs centralizados** — un servicio alojado donde mirar los logs ya estructurados de A11
- [ ] **G12. Despliegue continuo** — staging automático desde `main`, producción con aprobación manual *(seguridad)*
- [ ] **G13. Prueba de humo de extremo a extremo** — Playwright: login, subir versión, aprobar. Una sola, no una suite

G8 es la pantalla que más importa: es la única que ven tus clientes y la que reemplaza los audios de WhatsApp. Debe funcionar bien en celular.

G12 no se hace antes de tener respaldo de Postgres probado (D9): desplegar automático a producción sin respaldo es cómo se pierden datos de clientes.

## Orden sugerido

No se proyectan semanas. La estimación inicial (dos a tres tareas por semana) quedó desmentida: los bloques A y B se cerraron entre el 2026-10-26 y el 2026-10-29. El ritmo se recalibrará al cerrar el bloque C, que sí depende de terceros (proveedor OIDC, correo) y es más representativo que B.

| Bloque | Al final tienes |
| --- | --- |
| A | Un backend que arranca, migraciones, CI en verde y logs |
| B | Aislamiento probado, el cimiento de seguridad |
| C | Login real, roles, invitaciones por correo y escaneo |
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

## Fases siguientes

Enunciadas, no descompuestas.

### Fase 2 — Operación

Calendario editorial, formularios de solicitud de grabación, sesiones de grabación, panel de pendientes, ideas y guiones. Integración del NAS con sincronización y verificación por checksum.

Adaptador de WhatsApp para los avisos de aprobación: requiere cuenta de empresa verificada ante Meta y plantillas aprobadas una por una, así que el trámite empieza antes que el código.

### Fase 3 — Asistencia con IA

Generación de ideas, guiones, copies y descripciones a partir del perfil creativo de cada marca. Depende de que el bloque D tenga campos de perfil bien poblados.

### Fase 4 — Integración y comercialización

APIs de Instagram, TikTok y YouTube, publicación automatizada, lectura de métricas, suscripciones, cuotas y pagos. Límite configurable de rondas de revisión.

### Trabajo continuo, no es una fase

- [ ] Documentación de tratamiento de datos personales en `docs/`
- [ ] Contrato de servicio con propiedad de archivos y retención, y autorizaciones de uso de imagen firmadas por quienes aparecen en video
- [ ] Redactar los términos y condiciones, la política de privacidad y la política de cookies (C12 necesita el texto de las dos primeras antes del registro de usuarios)
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
