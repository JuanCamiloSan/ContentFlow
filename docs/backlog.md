# ContentFlow — Backlog

Actualizado: 2026-10-25

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

Siete bloques, 78 tareas. Una flecha significa que el bloque de abajo no puede empezar hasta que el de arriba esté cerrado.

```
           A · Cimientos (11 tareas · semanas 1 a 4)
                        │
           B · Aislamiento (8 tareas · semanas 5 a 8)
                        │
           C · Identidad (11 tareas · semanas 9 a 13)
          ┌─────────────┼─────────────┐
   D · Tenencia   E · Producción   F · Archivos
    (8 tareas)     (14 tareas)      (13 tareas)

   G · Frontend (13 tareas) va encima de D, E y F
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
- [ ] **A8. Pipeline de CI en GitHub Actions** — compilar, correr pruebas y guardar el reporte, en cada push y cada pull request
- [ ] **A9. Dockerfile de la API** — imagen que arranca en local y se construye también en CI
- [ ] **A10. Prueba de migraciones** — Flyway aplica desde cero sobre base vacía, y reiniciar no rompe nada
- [ ] **A11. Logs estructurados** — JSON con identificador de correlación por petición, y regla de no registrar datos personales ni tokens *(seguridad, bloquea)*

A5 parece opcional y no lo es: sin Testcontainers no se pueden probar las políticas de aislamiento del bloque siguiente. A8 viene justo después porque necesita pruebas que correr.

## Bloque B — Aislamiento

Todo este bloque es seguridad y va temprano. Agregarlo después obliga a rehacer el acceso a datos completo.

- [ ] **B1. Tabla `workspace` y migración** — la tabla raíz, con una fila de prueba *(bloquea)*
- [ ] **B2. Activar RLS en `workspace`** — política `USING` y `WITH CHECK` con `FORCE`, como ensayo del patrón *(seguridad)*
- [ ] **B3. Filtro de contexto de workspace** — leer el workspace y emitir `SET LOCAL app.workspace_id` al abrir la transacción *(seguridad, bloquea)*
- [ ] **B4. Prueba de fuga entre workspaces** — dos workspaces con datos, verificar que A no ve nada de B *(seguridad)*
- [ ] **B5. Prueba de escritura cruzada** — intentar insertar con el `workspace_id` ajeno y verificar que falla *(seguridad)*
- [ ] **B6. Prueba de lectura por id directo** — pedir una fila de B por su UUID desde A y verificar que no aparece *(seguridad)*
- [ ] **B7. Plantilla de migración con RLS** — un fragmento reutilizable, para que toda tabla nueva nazca con su política
- [ ] **B8. Prueba de concurrencia** — peticiones simultáneas de dos workspaces, verificando que el contexto no sobrevive entre conexiones del pool *(seguridad)*

En esta etapa el workspace se puede tomar de una cabecera fija de prueba. Lo importante es que el mecanismo exista y esté probado antes de que haya tablas que proteger. En el bloque C solo cambia de dónde sale el valor.

B8 es la prueba menos obvia y la más importante: el bug que de verdad filtra datos entre clientes no aparece con una petición a la vez, sino cuando el pool reutiliza una conexión y el workspace anterior sobrevive.

## Bloque C — Identidad

Aquí se conecta el proveedor OIDC y el workspace deja de venir de una cabecera de prueba.

- [ ] **C1. Proveedor OIDC en Docker Compose** — levantar Keycloak u otro en local, con un realm de prueba *(bloquea)*
- [ ] **C2. Spring Security como resource server** — validar el token y rechazar peticiones sin él *(seguridad)*
- [ ] **C3. Tablas `app_user` y `membership`** — migración con sus llaves y la política RLS en `membership`
- [ ] **C4. Conectar el token al contexto** — el workspace sale del token, se elimina la cabecera de prueba *(seguridad)*
- [ ] **C5. Roles y autorización** — los cuatro roles del modelo, con verificación en los endpoints *(seguridad)*
- [ ] **C6. Abstracción de notificaciones** — tabla `notification`, canal por tipo de evento, preferencia por usuario *(bloquea C7)*
- [ ] **C7. Adaptador de correo** — proveedor transaccional, plantillas básicas, registro de envíos
- [ ] **C8. Tabla `invitation` y emisión** — crear invitación con token de un solo uso y expiración, enviada por correo
- [ ] **C9. Aceptar invitación** — el flujo que convierte una invitación en `membership`, con el token invalidado *(seguridad)*
- [ ] **C10. Pruebas de autorización** — verificar que un aprobador no puede editar y que un rol de cliente no ve otros clientes
- [ ] **C11. Escaneo de dependencias** — Dependabot activo y OWASP dependency-check semanal, con llave de la NVD en secrets *(seguridad)*

La invitación debe invalidarse al usarse, no solo al expirar. Un token reutilizable en un correo reenviado es una puerta abierta.

C11 espera hasta aquí a propósito: escanear dependencias en la semana 2, con cuatro librerías, no dice nada. Va en un workflow aparte del CI, porque tarda y falla por CVE publicadas anoche, no por tu commit. C6 y C7 entran antes que las invitaciones porque una invitación sin forma de enviarla no sirve.

## Bloque D — Tenencia

Clientes, marcas y cuentas. El primero que vas a poder usar con datos reales.

- [ ] **D1. Tabla `client` con RLS** — migración, entidad JPA, repositorio
- [ ] **D2. CRUD de clientes** — endpoints con validación y pruebas
- [ ] **D3. Propiedad y retención por cliente** — campos que reflejan lo pactado en el contrato: dueño de crudos, dueño de entregables, meses de retención *(bloquea el borrado de F13)*
- [ ] **D4. Tabla `brand` con RLS** — colgada de `client`, con los campos del perfil creativo
- [ ] **D5. CRUD de marcas** — incluida la validación de que una marca pertenece a un cliente del mismo workspace *(seguridad)*
- [ ] **D6. Tabla `social_account` con RLS** — plataforma, identificador de cuenta, estado
- [ ] **D7. CRUD de cuentas sociales** — sin credenciales todavía, solo el registro
- [ ] **D8. Cargar tus tres clientes reales** — Iglesia sin Límites con dos marcas, Beraca con una marca y dos cuentas, Altocar

D8 no es relleno. Cargar datos reales revela de inmediato si el modelo sirve: campos que faltan, campos que sobran, nombres que no encajan. Hazla antes de construir el frontend, mientras cambiar el esquema todavía es barato.

Las credenciales de las APIs de redes llegan en la Fase 4, cifradas con una llave gestionada, nunca en texto plano.

## Bloque E — Producción

El corazón del producto: piezas, estados, versiones y aprobaciones.

- [ ] **E1. Tabla `content_piece` con RLS** — título, descripción, fecha objetivo, estado, marca
- [ ] **E2. Crear y listar piezas** — endpoints con filtro por marca y por estado
- [ ] **E3. Enum de estados** — los ocho estados del modelo, como tipo en base y en código
- [ ] **E4. Máquina de transiciones** — tabla de transiciones permitidas y rechazo de las inválidas *(bloquea)*
- [ ] **E5. Endpoint de cambio de estado** — una sola puerta para mover una pieza, nunca edición libre del campo *(seguridad)*
- [ ] **E6. Pruebas de transiciones** — verificar que agendado a publicado falla y que el camino válido pasa
- [ ] **E7. Tabla `piece_target`** — la relación muchos a muchos entre pieza y cuentas destino
- [ ] **E8. Asignar destinos a una pieza** — incluida la validación de que la cuenta pertenece a la misma marca *(seguridad)*
- [ ] **E9. Tabla `piece_version`** — con `round_number` y referencia al entregable
- [ ] **E10. Crear versión** — el número de ronda se calcula solo, nunca lo manda el cliente
- [ ] **E11. Tabla `review`** — decisión, revisor, comentario, fecha
- [ ] **E12. Aprobar o rechazar** — el rechazo mueve la pieza a edición y habilita la ronda siguiente
- [ ] **E13. Tabla `audit_log`** — solo inserciones, alimentada por los cambios de estado y las decisiones *(seguridad)*
- [ ] **E14. Pruebas del ciclo completo** — crear pieza, versión, rechazo, versión 2, aprobación

E4 es la más importante del bloque. Si las transiciones no están centralizadas, cada endpoint nuevo va a poder mover el estado a su manera.

Cuando E14 pase, el flujo del MVP funciona de extremo a extremo por API. Ese es el hito del backend.

## Bloque F — Archivos

El registro de archivos y la subida a S3. El NAS queda para la Fase 2.

- [ ] **F1. Tabla `asset` con RLS** — nombre, tipo, tamaño, checksum, ubicación, quién subió
- [ ] **F2. Registro manual de archivo** — crear la ficha sin subir nada, con `location_kind` en `registered`
- [ ] **F3. Almacenamiento S3 en local** — MinIO en Docker Compose, compatible con S3, sin costo
- [ ] **F4. Emitir URL prefirmada de subida** — expiración corta, alcance limitado a una clave *(seguridad)*
- [ ] **F5. Confirmar subida** — el cliente avisa, el backend verifica y crea la ficha
- [ ] **F6. Calcular y guardar checksum** — SHA-256 del contenido
- [ ] **F7. Emitir URL prefirmada de descarga** — solo para quien tiene acceso a esa pieza *(seguridad)*
- [ ] **F8. Asociar entregable a versión** — enlazar `asset` con `piece_version`, con estado `subiendo` / `procesando` / `lista`
- [ ] **F9. Prueba de acceso cruzado** — verificar que un usuario de otro workspace no obtiene URL de descarga *(seguridad)*
- [ ] **F10. Subida multiparte** — para crudos grandes, con reanudación si se corta la conexión
- [ ] **F11. Límite y validación de tamaño** — 2 GB para entregables, multiparte por encima; el tope duro de una subida simple es 5 GB
- [ ] **F12. Miniatura del entregable** — ffmpeg sobre el fotograma al 10% de la duración, leyendo por rango desde S3, dentro de la confirmación de subida
- [ ] **F13. Borrado selectivo de un cliente** — API que lista todo lo suyo y elimina lo que el administrador escoja, dejando fila de registro *(seguridad)*

MinIO habla el mismo protocolo que S3 y corre en tu máquina sin costo. Al pasar a producción solo cambia la configuración.

F9 cierra el círculo: RLS protege la base, pero una URL prefirmada vive fuera de ella. F12 no necesita worker: el entregable es un video de redes, de decenas de megas. F13 depende de D3: qué se puede borrar lo decide el contrato de cada cliente.

## Bloque G — Frontend

Las pantallas mínimas para operar el flujo. Funcionalidad antes que estética.

- [ ] **G1. Proyecto Next.js y autenticación** — login contra el proveedor OIDC y sesión persistente
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

G12 no se hace antes de tener respaldo de Postgres probado: desplegar automático a producción sin respaldo es cómo se pierden datos de clientes.

## Orden sugerido

A 5 a 8 horas semanales, cada semana rinde entre dos y tres tareas. Son 78 tareas, lo que da unas 26 a 39 semanas si todo sale derecho. No sale derecho.

| Semanas | Bloques | Al final tienes |
| --- | --- | --- |
| 1 a 4 | A | Un backend que arranca, migraciones, CI en verde y logs |
| 5 a 8 | B | Aislamiento probado, el cimiento de seguridad |
| 9 a 13 | C | Login real, roles, invitaciones por correo y escaneo |
| 14 a 17 | D | Tus tres clientes cargados de verdad |
| 18 a 23 | E | El flujo completo funcionando por API |
| 24 a 29 | F | Subida, descarga, miniaturas y borrado selectivo |
| 30 a 39 | G | La aplicación usable por ti y por tus clientes, desplegada |

### Tres hitos para no perder el ánimo

1. **Semana 8** — el aislamiento probado. No se ve, pero es lo que hace vendible el producto
2. **Semana 17** — tus clientes reales cargados. Por primera vez el sistema habla de tu operación
3. **Semana 23** — el ciclo completo por API. El producto existe, aunque todavía sin pantallas

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

- [ ] Respaldo automático de PostgreSQL con prueba de restauración *(seguridad, bloquea G12)*
- [ ] Documentación de tratamiento de datos personales en `docs/`
- [ ] Contrato de servicio con propiedad de archivos y retención, y autorizaciones de uso de imagen firmadas por quienes aparecen en video
- [ ] Mantener las acciones de GitHub fijadas por SHA, no por etiqueta *(seguridad)*

El respaldo entra en el momento en que cargues tus clientes reales, en la semana 14, y bloquea el despliegue continuo del bloque G.
