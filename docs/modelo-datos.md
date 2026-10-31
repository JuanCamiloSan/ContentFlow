# ContentFlow — Modelo de datos y seguridad

Actualizado: 2026-10-25

Define cómo ContentFlow guarda la información y cómo impide que los datos de un cliente se mezclen con los de otro.

## Las cinco ideas del modelo

### 1. Cada agencia vive en su propio espacio

Un *workspace* es el espacio de una agencia. Nada cruza de un espacio a otro, nunca. Como un edificio de oficinas donde no existe puerta entre pisos: no es que esté prohibido pasar, es que no hay puerta.

### 2. La marca es distinta del cliente

El **cliente** es con quién facturas. La **marca** es para quién creas. El perfil creativo pertenece a la marca.

### 3. Una pieza de contenido tiene versiones

La pieza es la idea. La versión es cada archivo concreto que se entrega para revisión. Si el cliente pide cambios, no se reemplaza el video: se crea la versión 2.

### 4. Se aprueba una versión, no una pieza

Una aprobación siempre apunta a una versión específica y a una persona específica, con fecha. Esto evita la discusión más común del oficio: "yo aprobé otra cosa".

### 5. Los archivos no viven en la base de datos

La base guarda la ficha del archivo: nombre, tamaño, dónde está, su huella digital. El video vive en el NAS o en la nube. Como el catálogo de una biblioteca.

## Mapa de tablas

```
                    ┌──────────────┐
                    │  workspace   │  la frontera de aislamiento
                    └──────┬───────┘
          ┌────────────────┴────────────────┐
   TENENCIA                           IDENTIDAD
   ┌──────────┐                  ┌──────────┐   ┌──────────────┐
   │  client  │                  │ app_user ├──>│  membership  │
   └────┬─────┘                  └──────────┘   └──────────────┘
        │                                       ┌──────────────┐
   ┌────▼─────┐                                 │  invitation  │
   │  brand   │                                 └──────────────┘
   └────┬─────┘                                 ┌──────────────┐
        │                                       │ notification │
        │                                       └──────────────┘
   PRODUCCIÓN
   ┌─────────────┐   ┌──────────────┐   ┌────────────────┐
   │content_piece├──>│ piece_target ├──>│ social_account │
   └──────┬──────┘   └──────────────┘   └────────────────┘
          │          ┌───────────────┐   ┌─────────┐
          └─────────>│ piece_version ├──>│ review  │
                     └───────┬───────┘   └─────────┘
                             │           ┌─────────┐
                             └──────────>│  asset  │
                                         └─────────┘
```

`audit_log` y `deletion_record` se relacionan con todas y no aparecen en el dibujo.

## Las tablas, una por una

| Tabla | Guarda | Nota de diseño |
| --- | --- | --- |
| `workspace` | El espacio de cada agencia | Única tabla sin `workspace_id`: ella es la frontera |
| `app_user` | Personas con cuenta | Vive fuera del workspace: una persona puede pertenecer a varios |
| `membership` | Qué persona pertenece a qué workspace y con qué rol | También limita a un cliente concreto cuando el rol lo requiere |
| `invitation` | Invitaciones pendientes | Token de un solo uso con expiración. Excepción al aislamiento: se consulta por función `SECURITY DEFINER` (ADR 0003) |
| `notification` | Avisos enviados y por enviar | Canal por tipo de evento, preferencia por usuario |
| `client` | Empresa cliente | Relación comercial, más propiedad y retención pactadas |
| `brand` | Marca dentro de un cliente | Perfil creativo: industria, metas, tono, público |
| `social_account` | Cuenta de Instagram, TikTok o YouTube | Credenciales cifradas, nunca en texto plano |
| `content_piece` | La pieza de contenido | Título, descripción, fecha objetivo, estado actual |
| `piece_target` | Qué pieza va a qué cuenta | Tabla puente: una pieza puede ir a varias cuentas |
| `piece_version` | Cada entrega concreta para revisión | Número de ronda, entregable y estado de disponibilidad |
| `review` | Aprobación o rechazo de una versión | Quién, cuándo, decisión y comentario |
| `asset` | Ficha de cada archivo | Nombre, tipo, tamaño, checksum y su algoritmo, ubicación, miniatura |
| `deletion_record` | Qué se borró, quién y cuándo | Sobrevive al borrado: es la prueba de cumplimiento |
| `audit_log` | Registro de quién hizo qué | Solo inserciones, nunca se edita ni borra. Solo identificadores y enumeraciones, sin llave a `app_user` |

### Reglas comunes

Toda tabla menos `workspace` y `app_user` lleva `workspace_id` con llave foránea. Es la columna sobre la que opera Row Level Security.

Toda tabla lleva `id` como UUID, `created_at` y `updated_at`. Se usa UUID en lugar de un número secuencial para que un identificador expuesto no revele cuántos registros existen ni permita adivinar otros.

### Por qué `app_user` está fuera del workspace

Una persona puede trabajar con dos agencias distintas, o ser cliente de una y empleado de otra. La pertenencia se resuelve en `membership`, que sí lleva `workspace_id`. Es, junto con `invitation`, la excepción al aislamiento por workspace: sus políticas se apoyan en `app.user_id`.

## El recorrido de una pieza

```
Solicitado ──> Agendado ──> Grabado ──> En edición
                                            │  ▲
                                            ▼  │ rechazo
Reportado <── Publicado <── Aprobado <── En aprobación
```

Ocho estados. El campo `status` de `content_piece` guarda en cuál está.

El rechazo es la única transición hacia atrás. La pieza vuelve a edición y se crea una fila nueva en `piece_version` con el número de ronda siguiente. La versión anterior no se toca.

### Por qué los estados son una máquina, no un campo libre

El backend solo permite las transiciones del diagrama. No se puede pasar de agendado a publicado saltándose la aprobación, ni retroceder a capricho. Cada cambio deja una fila en `audit_log`.

### Dónde entra cada plan de servicio

| Plan | Entra en | Sale en |
| --- | --- | --- |
| Gratuito | Solicitado | Agendado |
| Básico | En edición, con material del cliente | Aprobado, publica el cliente |
| Premium | Solicitado | Reportado, publica el sistema |

Informativo. Los planes pertenecen a la fase comercial y no se implementan todavía.

## Versiones y aprobaciones

La pieza es la idea y vive todo el proyecto. La versión es cada archivo entregado. La aprobación es la decisión de una persona sobre una versión concreta.

### Por qué no se reemplaza el archivo

Si al rechazar se sobrescribiera el video, se perdería la evidencia de qué se entregó y cuándo. Cada corrección crea una fila nueva en `piece_version` con `round_number` incrementado.

### Qué guarda una aprobación

| Campo | Para qué |
| --- | --- |
| `version_id` | La versión exacta revisada |
| `reviewer_id` | La persona concreta, no "el cliente" |
| `decision` | Aprobado o cambios solicitados |
| `comment` | Qué pidió cambiar |
| `created_at` | Cuándo, sin posibilidad de editarlo después |

Las filas de `review` no se editan ni se borran. Una decisión que cambia de opinión genera una fila nueva.

### El contador de rondas

`round_number` se registra desde el MVP pero no limita nada todavía. Cuando llegue la fase comercial, la regla de "dos rondas incluidas" se activa leyendo ese número.

## Aislamiento: Row Level Security

Es la decisión de seguridad más importante del proyecto.

### En lenguaje llano

En una aplicación normal, el programa le pide datos a la base y agrega la condición "solo de esta agencia". Si el programador olvida esa condición una sola vez, la base entrega todo.

Row Level Security invierte la responsabilidad. La base guarda la regla dentro de sí misma: cada fila sabe a qué espacio pertenece, y la base solo devuelve las filas del espacio de quien pregunta. Es la diferencia entre confiar en que nadie olvide cerrar la puerta y poner una puerta que se cierra sola.

### Cómo funciona

1. Toda tabla lleva `workspace_id`
2. Cada tabla tiene una política que compara ese valor contra la sesión actual
3. La aplicación fija el usuario y el workspace al abrir cada transacción, en un solo punto del código

La justificación completa, con las alternativas descartadas, vive en el [ADR 0003](adr/0003-contexto-de-sesion-y-roles-de-postgres.md).

### Las políticas

```sql
ALTER TABLE content_piece ENABLE ROW LEVEL SECURITY;
ALTER TABLE content_piece FORCE ROW LEVEL SECURITY;

CREATE POLICY workspace_isolation ON content_piece
  USING (workspace_id = current_workspace())
  WITH CHECK (workspace_id = current_workspace());
```

`USING` filtra lo que se puede leer. `WITH CHECK` impide escribir una fila en un workspace ajeno. En una política `ALL`, PostgreSQL reutiliza el `USING` si se omite el `WITH CHECK`, pero se escribe siempre explícito: así la intención es visible, y partir la política por comando más adelante no deja las escrituras abiertas. `PoliticasRlsTest` exige las dos cláusulas en toda tabla con `workspace_id`.

`FORCE` hace que la política aplique también al dueño de la tabla. Pero no protege contra superusuarios ni contra roles con `BYPASSRLS`: por eso la aplicación se conecta con un rol propio (ver "Los tres roles").

Las tablas con `client_id` agregan una segunda condición a la misma política, resuelta por una función `STABLE` que lee `membership`. Un rol limitado a un cliente solo ve ese cliente, y lo impone la base, no el código.

### `current_workspace()`, no `current_setting` crudo

Las políticas llaman a `current_workspace()`, que lanza excepción si no hay workspace fijado. Con `current_setting('app.workspace_id', true)` y nada fijado, la política devolvería cero filas, y eso se ve igual que "no hay datos". La función también trata la cadena vacía como ausencia de contexto.

### Dos variables, dos pasos

| Variable | Qué contiene | Cuándo se fija |
| --- | --- | --- |
| `app.user_id` | La persona autenticada, del identificador del token | Siempre, al abrir la transacción |
| `app.workspace_id` | El workspace activo | Después de resolverlo contra `membership` |

`app_user` y `membership` llevan sus políticas sobre `app.user_id`. Así, saber a qué workspaces pertenece alguien ocurre bajo RLS legítimo, y se evita el huevo y la gallina: no se necesita un workspace para descubrir cuál workspace usar.

El workspace activo viaja en el encabezado `X-Workspace-Id`, no en el token: un claim obligaría a volver a autenticarse para cambiar de workspace. El encabezado se valida contra `membership` antes de fijar la variable. Si la persona pertenece a uno solo y no lo manda, se usa ese; si pertenece a varios, se rechaza.

`invitation` es la excepción: el invitado aún no tiene `membership`. Se accede por funciones `SECURITY DEFINER` que reciben el hash del token y devuelven solo esa invitación. `invitation` y `membership` no llevan `FORCE`, porque esas funciones corren como dueño. Detalles en el ADR 0003.

### El punto único de entrada

Al abrir la transacción, antes de cualquier consulta, con parámetros enlazados:

```sql
SELECT set_config('app.user_id', ?, true);
-- se resuelve el workspace contra membership, luego:
SELECT set_config('app.workspace_id', ?, true);
```

El tercer argumento `true` equivale a `SET LOCAL`: el valor muere al terminar la transacción. Con conexiones reutilizadas de un pool, un `SET` normal dejaría el workspace anterior activo para la siguiente petición. `SET LOCAL` no admite parámetros, por eso se usa la función: concatenar el valor en la sentencia es la puerta a una inyección.

### Los tres roles de PostgreSQL

| Rol | Para qué | Permisos |
| --- | --- | --- |
| Dueño del esquema | Corre Flyway | Dueño de las tablas, sin `BYPASSRLS` |
| Aplicación | Todo el tráfico de la API | Solo DML, no es dueño de las tablas |
| Superusuario | Administración | No lo usa la aplicación ni Flyway |

Los roles se crean fuera de las migraciones (Docker Compose, Testcontainers, producción) para no poner contraseñas en Git. La aplicación tiene dos `DataSource`: el del dueño solo para Flyway.

### Cómo se prueba

Solo contra un PostgreSQL real, con Testcontainers, **conectándose con el rol de aplicación**. Testcontainers arranca por defecto con superusuario, y con él estas pruebas pasarían sin probar nada.

- [x] Verificar que el rol de aplicación no es superusuario ni tiene `BYPASSRLS`
- [x] Crear dos workspaces con datos en cada uno
- [x] Fijar el workspace A y verificar que las consultas no devuelven nada de B
- [x] Intentar insertar una fila con el `workspace_id` de B y verificar que falla
- [x] Intentar leer una fila de B por su `id` directo y verificar que no aparece
- [x] Peticiones simultáneas de dos workspaces, verificando que el contexto no sobrevive entre conexiones del pool
- [x] Una consulta sin contexto de workspace falla con excepción, no con cero filas

La cuarta simula un identificador filtrado; la sexta es el bug que de verdad filtra datos y no aparece con una petición a la vez.

## Archivos

La base guarda la ficha, no el archivo. La tabla `asset` es ese catálogo.

| Campo | Para qué |
| --- | --- |
| `filename` | Nombre original |
| `mime_type` | Tipo de archivo |
| `size_bytes` | Tamaño, para cuotas futuras |
| `checksum` | Huella que calcula S3 (`x-amz-checksum-sha256`), en base64 |
| `checksum_algorithm` | Con qué se calculó y si es del archivo completo o compuesto (multiparte) |
| `location_kind` | Dónde vive: NAS, S3 o solo registrado |
| `location_path` | Ruta o clave dentro de ese lugar |
| `thumbnail_path` | Clave de la miniatura en S3 |
| `uploaded_by` | Quién lo subió |

### Para qué sirve el checksum

Comprobar más adelante que un entregable aprobado no fue alterado, y que la subida llegó íntegra.

El valor lo calcula S3, no el backend: leer el archivo para calcularlo lo haría pasar por la API. Hay que pedir SHA-256 de forma explícita al prefirmar la URL, porque los SDK recientes usan CRC32 por defecto. S3 lo devuelve en base64.

En una subida simple es el SHA-256 del archivo completo. En una subida multiparte **no**: es un checksum compuesto (el hash de los hashes de las partes, con sufijo `-N`). Por eso se guardan `checksum` y `checksum_algorithm` juntos: un compuesto solo se puede comparar con otro calculado con las mismas partes.

Consecuencia: detectar duplicados y verificar copias contra el NAS solo funciona con checksums del archivo completo. Para los archivos multiparte, la comparación con el NAS se resuelve en la Fase 2, cuando se integre el NAS.

### Las tres ubicaciones

| Valor | Significa | Fase |
| --- | --- | --- |
| `registered` | Existe en algún lado, solo anotamos sus datos | MVP |
| `nas` | Guardado en el NAS, ruta conocida | Fase 2 |
| `s3` | En almacenamiento en la nube, accesible en línea | MVP para entregables |

### Subida y descarga

El archivo nunca pasa por el backend. El navegador pide una URL prefirmada, sube directo a S3 y luego avisa a la API para crear la ficha. Las URL expiran en minutos.

El límite para entregables es 500 MB. El tope duro de una subida prefirmada simple es 5 GB; los crudos grandes, que no tienen este límite, usan subida multiparte.

### Miniaturas

Cada entregable guarda una miniatura, tomada con ffmpeg del fotograma al 10% de la duración. El primer fotograma suele salir negro.

No hace falta procesamiento en segundo plano: ffmpeg lee por rango desde S3 y la lectura cabe dentro de la confirmación de subida.

**Excepción a "el archivo nunca pasa por el backend".** Para generar la miniatura, los bytes que ffmpeg lee sí pasan por el backend. Es una excepción acotada: solo entregables (hasta 500 MB) y solo los rangos necesarios.

- Leer por rango funciona a cualquier tamaño si el índice del MP4 (`moov`) está al principio. Si está al final, también hay que leer la cola del archivo.
- La lectura tiene un tiempo máximo. Si no se puede generar la miniatura dentro de ese tiempo, la pieza queda sin miniatura; la subida no falla.

No se genera versión liviana para revisión. El entregable ya es un video de redes, de decenas de megas, que el navegador reproduce directo desde S3. Los crudos grandes son material que el cliente nunca revisa.

### Estado de disponibilidad

`piece_version` lleva un estado propio: `subiendo`, `procesando`, `lista`. Hoy pasa casi directo de la primera a la tercera.

Existe para que agregar cualquier procesamiento futuro no obligue a cambiar API, frontend y notificaciones a la vez.

### Borrado selectivo

Un administrador ve todo lo de un cliente y escoge qué eliminar. No hay borrado automático: qué se puede borrar depende del contrato de ese cliente.

Cada borrado deja una fila en `deletion_record` que sobrevive a la operación. Esa fila es la prueba de que se cumplió con una solicitud.

Buscar en qué videos aparece una persona concreta es manual. El modelo no etiqueta personas en las piezas.

### Regla del NAS

El NAS es caché de trabajo, nunca la única copia. La aplicación debe seguir funcionando con el NAS apagado: si un archivo no está disponible, la ficha sigue ahí y la interfaz lo indica.

## Propiedad y datos personales

Son dos cosas distintas y se resuelven por caminos distintos.

**La propiedad de los archivos** se pacta en el contrato con cada cliente: quién es dueño de los crudos, quién de los entregables, cuántos meses se guardan. Eso se guarda como campos en `client`, no como supuesto en el código.

**Los derechos de quienes aparecen en video** no los resuelve ese contrato. Un miembro de la iglesia o un cliente de la cafetería no firmó nada con nadie. Esa autorización se recoge persona por persona antes de grabar, y en el caso de menores la firma el acudiente.

Por eso el sistema debe poder eliminar un video aunque la agencia sea dueña del archivo: si alguien retira su autorización, la propiedad del archivo no basta para conservarlo.

### Qué sobrevive a un borrado

`audit_log` y `deletion_record` se conservan. Para que no contengan datos personales desde el origen, `audit_log` guarda solo identificadores y valores de enumeración, nunca texto libre, y **no tiene llave foránea a `app_user`**. Así, borrar a una persona deja en el registro un UUID opaco y no bloquea el borrado. Queda constancia de quién aprobó qué y cuándo, sin conservar los datos que se pidió eliminar.

**Excepción documentada: `review.comment`.** Es texto escrito por una persona y debe poder vaciarse si esa persona pide su eliminación. Un disparador permite actualizar únicamente `comment` y `redacted_at`; cualquier otro cambio en `review` se rechaza.

Esto no es asesoría legal: define qué hace el sistema, no si un contrato es válido.

## Registro de decisiones

La versión con contexto y consecuencias vive en [`adr/0001-decisiones-iniciales-de-arquitectura.md`](adr/0001-decisiones-iniciales-de-arquitectura.md). El contexto de sesión y los roles de PostgreSQL, que reemplazan la decisión 2 del 0001, están en [`adr/0003-contexto-de-sesion-y-roles-de-postgres.md`](adr/0003-contexto-de-sesion-y-roles-de-postgres.md). Las decisiones nuevas van como ADR nuevo, no aquí.

| Decisión | Alternativa descartada | Razón |
| --- | --- | --- |
| Row Level Security en la base | Filtros en el código | Un filtro olvidado una vez expone datos de otro cliente |
| Monolito modular | Microservicios | Con 5 a 8 horas semanales, la complejidad operativa no se sostiene |
| Capa de marca separada del cliente | Perfil creativo en el cliente | Dos de tres clientes actuales tienen marcas con tono distinto |
| Pieza a cuenta, muchos a muchos | Una cuenta por pieza | Beraca ya publica la misma pieza en dos cuentas |
| Proveedor OIDC externo | Autenticación propia | Recuperación, verificación y sesiones ya resueltas y auditadas |
| UUID como identificador | Entero secuencial | Un id expuesto no revela volumen ni permite adivinar otros |
| Versiones inmutables | Sobrescribir el archivo | Sin historial no hay evidencia de qué se aprobó |
| `app_user` fuera del workspace | Usuario por workspace | Una persona puede trabajar con varias agencias |
| Dos variables de sesión (`app.user_id`, `app.workspace_id`) y `current_workspace()` que falla sin contexto | Una sola variable y `current_setting` crudo | Resolver la pertenencia ocurre bajo RLS, y olvidar el contexto es un error, no cero filas ([ADR 0003](adr/0003-contexto-de-sesion-y-roles-de-postgres.md)) |
| Tres roles de PostgreSQL; pruebas con el rol de aplicación | Conectarse como dueño o superusuario | `FORCE` no protege contra superusuarios ni `BYPASSRLS` |
| `audit_log` sin texto libre ni llave a `app_user` | Guardar el nombre o el correo | Borrar a la persona no bloquea ni deja datos personales |
| Archivos fuera de la base | Guardar binarios en Postgres | Respaldos inmanejables y consultas lentas |
| S3 con URL prefirmadas | Archivos a través del backend | Menos ancho de banda y menor superficie de ataque |
| `round_number` sin límite | Limitar a dos rondas ya | Es regla comercial y la monetización está aplazada |
| Propiedad y retención por cliente | Regla fija en el código | Los contratos no son todos iguales y van a cambiar |
| Borrado manual y selectivo | Borrado automático en cascada | Qué se puede borrar lo decide el contrato, no una regla |
| Solo miniatura, sin versión liviana | Transcodificación para revisión | El entregable ya es un video de redes, liviano de por sí |
| Miniatura dentro de la petición | Trabajos en segundo plano | ffmpeg lee el inicio del archivo por rango, tarda segundos |
| Abstracción de notificaciones | Enviar correos directo | WhatsApp y correo tienen reglas distintas y conviven |
| Logs estructurados desde el inicio | Agregar observabilidad al final | El identificador de correlación se diseña o no existe |

## Preguntas abiertas

Ninguna bloquea el inicio del MVP. Las dos primeras se necesitan antes del bloque C.

- [ ] **Proveedor de identidad.** Propuesto: Auth0 ([ADR 0004](adr/0004-proveedores-de-identidad-y-correo.md)); faltan por verificar sus riesgos abiertos
- [ ] **Proveedor de correo transaccional.** Propuesto: Resend (ADR 0004). Necesita un dominio propio antes de C7
- [ ] **Proveedor de almacenamiento S3.** Candidato: Cloudflare R2. Afecta costo y latencia, y puede afectar el modelo: su documentación no lista el checksum SHA-256 que pide `asset`. Se resuelve en la tarea F15 del backlog
- [ ] **Alcance del `audit_log`.** Solo eventos clave o toda escritura
- [ ] **Umbral de registro en el RNBD.** Verificar el vigente ante la SIC
- [ ] **Estrategia de respaldo de Postgres.** Frecuencia, destino y prueba de restauración
- [ ] **Verificación de empresa ante Meta.** Para el adaptador de WhatsApp de la Fase 2

El respaldo de Postgres no es opcional desde que entren datos reales de clientes, y bloquea el despliegue continuo.

También falta, y no es técnico: el contrato de servicio con propiedad y retención, y el formato de autorización de uso de imagen para quienes salen en video.
