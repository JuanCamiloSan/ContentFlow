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
| `invitation` | Invitaciones pendientes | Token de un solo uso con expiración |
| `notification` | Avisos enviados y por enviar | Canal por tipo de evento, preferencia por usuario |
| `client` | Empresa cliente | Relación comercial, más propiedad y retención pactadas |
| `brand` | Marca dentro de un cliente | Perfil creativo: industria, metas, tono, público |
| `social_account` | Cuenta de Instagram, TikTok o YouTube | Credenciales cifradas, nunca en texto plano |
| `content_piece` | La pieza de contenido | Título, descripción, fecha objetivo, estado actual |
| `piece_target` | Qué pieza va a qué cuenta | Tabla puente: una pieza puede ir a varias cuentas |
| `piece_version` | Cada entrega concreta para revisión | Número de ronda, entregable y estado de disponibilidad |
| `review` | Aprobación o rechazo de una versión | Quién, cuándo, decisión y comentario |
| `asset` | Ficha de cada archivo | Nombre, tipo, tamaño, checksum, ubicación, miniatura |
| `deletion_record` | Qué se borró, quién y cuándo | Sobrevive al borrado: es la prueba de cumplimiento |
| `audit_log` | Registro de quién hizo qué | Solo inserciones, nunca se edita ni borra |

### Reglas comunes

Toda tabla menos `workspace` y `app_user` lleva `workspace_id` con llave foránea. Es la columna sobre la que opera Row Level Security.

Toda tabla lleva `id` como UUID, `created_at` y `updated_at`. Se usa UUID en lugar de un número secuencial para que un identificador expuesto no revele cuántos registros existen ni permita adivinar otros.

### Por qué `app_user` está fuera del workspace

Una persona puede trabajar con dos agencias distintas, o ser cliente de una y empleado de otra. La pertenencia se resuelve en `membership`, que sí lleva `workspace_id`. Es la única excepción al aislamiento.

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
3. La aplicación fija el workspace al abrir cada transacción, en un solo punto del código

### Las políticas

```sql
ALTER TABLE content_piece ENABLE ROW LEVEL SECURITY;
ALTER TABLE content_piece FORCE ROW LEVEL SECURITY;

CREATE POLICY workspace_isolation ON content_piece
  USING (workspace_id = current_setting('app.workspace_id')::uuid)
  WITH CHECK (workspace_id = current_setting('app.workspace_id')::uuid);
```

`USING` filtra lo que se puede leer. `WITH CHECK` impide escribir una fila en un workspace ajeno. Se necesitan las dos: sin `WITH CHECK`, alguien podría insertar datos en otro espacio aunque no pueda leerlo.

`FORCE` hace que la política aplique también al dueño de la tabla. Sin eso, la cuenta que usa la aplicación podría saltarse la regla.

### El punto único de entrada

Al abrir la transacción, antes de cualquier consulta:

```sql
SET LOCAL app.workspace_id = '<uuid del token>'
```

Se usa `SET LOCAL`, no `SET`: el valor muere al terminar la transacción. Con conexiones reutilizadas de un pool, un `SET` normal dejaría el workspace anterior activo para la siguiente petición.

### Cómo se prueba

Solo contra un PostgreSQL real, con Testcontainers:

- [ ] Crear dos workspaces con datos en cada uno
- [ ] Fijar el workspace A y verificar que las consultas no devuelven nada de B
- [ ] Intentar insertar una fila con el `workspace_id` de B y verificar que falla
- [ ] Intentar leer una fila de B por su `id` directo y verificar que no aparece
- [ ] Peticiones simultáneas de dos workspaces, verificando que el contexto no sobrevive entre conexiones del pool

Las dos últimas son las que más importan. La cuarta simula un identificador filtrado; la quinta es el bug que de verdad filtra datos y no aparece con una petición a la vez.

## Archivos

La base guarda la ficha, no el archivo. La tabla `asset` es ese catálogo.

| Campo | Para qué |
| --- | --- |
| `filename` | Nombre original |
| `mime_type` | Tipo de archivo |
| `size_bytes` | Tamaño, para cuotas futuras |
| `checksum` | Huella digital SHA-256 del contenido |
| `location_kind` | Dónde vive: NAS, S3 o solo registrado |
| `location_path` | Ruta o clave dentro de ese lugar |
| `thumbnail_path` | Clave de la miniatura en S3 |
| `uploaded_by` | Quién lo subió |

### Para qué sirve el checksum

Verificar que un archivo copiado al NAS llegó íntegro, detectar duplicados sin volver a subirlos, y comprobar más adelante que un entregable aprobado no fue alterado.

### Las tres ubicaciones

| Valor | Significa | Fase |
| --- | --- | --- |
| `registered` | Existe en algún lado, solo anotamos sus datos | MVP |
| `nas` | Guardado en el NAS, ruta conocida | Fase 2 |
| `s3` | En almacenamiento en la nube, accesible en línea | MVP para entregables |

### Subida y descarga

El archivo nunca pasa por el backend. El navegador pide una URL prefirmada, sube directo a S3 y luego avisa a la API para crear la ficha. Las URL expiran en minutos.

El límite para entregables es 2 GB. El tope duro de una subida prefirmada simple es 5 GB; por encima del límite se usa subida multiparte, que es también la que soporta los crudos grandes.

### Miniaturas

Cada entregable guarda una miniatura, tomada con ffmpeg del fotograma al 10% de la duración. El primer fotograma suele salir negro.

No hace falta procesamiento en segundo plano: ffmpeg lee solo el inicio del archivo desde S3 con peticiones de rango, así que cabe dentro de la confirmación de subida.

No se genera versión liviana para revisión. El entregable ya es un video de redes, de decenas de megas, que el navegador reproduce directo desde S3. Los 2 GB son material crudo, que el cliente nunca revisa.

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

`audit_log` y `deletion_record` se conservan, pero los datos personales que contengan se reemplazan por identificadores sin significado. Así queda constancia de quién aprobó qué y cuándo, sin conservar los datos que se pidió eliminar.

Esto no es asesoría legal: define qué hace el sistema, no si un contrato es válido.

## Registro de decisiones

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

Ninguna bloquea el inicio del MVP. Las dos primeras se necesitan en la semana 9.

- [ ] **Proveedor de identidad.** Keycloak autoalojado o un servicio gestionado
- [ ] **Proveedor de correo transaccional.** Antes de las invitaciones
- [ ] **Proveedor de almacenamiento S3.** Afecta costo y latencia, no el modelo
- [ ] **Alcance del `audit_log`.** Solo eventos clave o toda escritura
- [ ] **Umbral de registro en el RNBD.** Verificar el vigente ante la SIC
- [ ] **Estrategia de respaldo de Postgres.** Frecuencia, destino y prueba de restauración
- [ ] **Verificación de empresa ante Meta.** Para el adaptador de WhatsApp de la Fase 2

El respaldo de Postgres no es opcional desde que entren datos reales de clientes, y bloquea el despliegue continuo.

También falta, y no es técnico: el contrato de servicio con propiedad y retención, y el formato de autorización de uso de imagen para quienes salen en video.
