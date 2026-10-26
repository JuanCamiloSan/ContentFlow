# ContentFlow — Prompt de proyecto (v2)

Actualizado: 2026-10-25

## Contexto y objetivo

ContentFlow es una aplicación web para gestionar y automatizar la creación de contenido audiovisual para empresas que publican en Instagram, TikTok y YouTube.

Primero debe servir para administrar mi propia operación. Después debe poder convertirse en un producto SaaS vendible a otros creadores y agencias pequeñas.

El objetivo es automatizar progresivamente el flujo completo: planificación, solicitudes de grabación, organización de archivos, edición, revisiones, aprobación del cliente, calendario de publicaciones y, eventualmente, publicación automatizada.

No quiero dedicar tiempo todavía a precios, márgenes, facturación ni planes de suscripción. La arquitectura debe permitir incorporar esas funciones más adelante sin reconstruir la aplicación.

## Perfil técnico y disponibilidad

Ingeniero electrónico, software engineer. Experiencia con Java, Spring Boot, APIs REST, microservicios e integración de sistemas.

Disponibilidad real: 5 a 8 horas semanales. El plan debe estar dividido en tareas pequeñas y verificables.

## Jerarquía del modelo

Cuatro niveles: workspace, cliente, marca y cuenta social.

```
Workspace (mi agencia)
├── Cliente: Iglesia sin Límites
│   ├── Marca: Iglesia general ──> Instagram (cuenta general)
│   └── Marca: Jóvenes         ──> Instagram (cuenta de jóvenes)
└── Cliente: Beraca cafetería
    └── Marca: Beraca          ──> Instagram (primera sede)
                               └─> Instagram (segunda sede)
```

| Nivel | Qué representa | Qué guarda |
| --- | --- | --- |
| Workspace | Mi operación completa | Usuarios internos, configuración, frontera de aislamiento |
| Cliente | La relación comercial | Contacto, contrato, propiedad y retención, usuarios invitados |
| Marca | El perfil creativo | Industria, metas, tono, público, pilares de contenido |
| Cuenta social | El destino de publicación | Plataforma, credenciales, métricas |

### Por qué la marca es su propia capa

Iglesia sin Límites tiene dos cuentas con público y tono distintos: la congregación general y los jóvenes. Si el perfil creativo viviera en el cliente, la IA generaría guiones de jóvenes con el tono de la iglesia general.

Un cliente con una sola marca, como Altocar, simplemente tiene una. El modelo no le estorba.

### Relación entre pieza y cuenta

Una pieza de contenido apunta a una o varias cuentas destino. Es una relación muchos a muchos desde el inicio, para no migrarla después.

Beraca lo usa hoy: la misma pieza sale en las dos sedes. Se prevé también el caso contrario, una pieza adaptada por cuenta.

## Usuarios, roles y accesos

Todo usuario se registra y tiene identidad persistente, incluido el cliente que aprueba. No se usan enlaces anónimos de aprobación.

La razón: el cliente llena un formulario de onboarding con industria, metas y necesidades, que alimenta la generación asistida por IA. Además entra a ver sus videos y a consultar su resumen de métricas. Nada de eso funciona sin cuenta.

### Cómo entra un cliente

Por invitación desde el workspace, nunca por registro abierto. Así ninguna cuenta queda huérfana sin empresa asociada.

### Roles

| Rol | Ámbito | Puede |
| --- | --- | --- |
| Propietario | Workspace | Todo, incluida la gestión de usuarios |
| Editor | Workspace | Crear y editar piezas, subir archivos, cambiar estados |
| Aprobador | Cliente | Ver piezas y aprobar o rechazar versiones |
| Colaborador | Cliente | Ver piezas y comentar, sin aprobar |

### Autenticación

No se escribe el sistema de autenticación a mano. Se usa un proveedor OIDC (Keycloak u otro equivalente) detrás de Spring Security como resource server.

Al tener registro propio se asumen recuperación de contraseña, verificación de correo, manejo de sesiones y bloqueo por intentos fallidos. Un proveedor ya resuelto evita implementar y auditar todo eso.

### Atribución de aprobaciones

Cada aprobación o rechazo registra la persona concreta, la versión exacta y la fecha. No basta con registrar que "el cliente aprobó".

## Funcionalidades por fase

No se construye todo desde el principio. El MVP debe permitir gestionar un flujo completo a mano antes de integrar servicios externos.

### Fase 1 — MVP

- Gestión de workspace, clientes, marcas y cuentas sociales
- Propiedad y retención de archivos pactadas por cliente
- Piezas de contenido con descripción, plataforma y fecha objetivo
- Registro y carga de archivos audiovisuales, con miniatura del entregable
- Estados de producción con transiciones controladas
- Versiones, comentarios y aprobación atribuida
- Notificaciones por correo, con la abstracción lista para otros canales
- Borrado manual y selectivo de los datos de un cliente, con registro
- Vista de lista o tablero del contenido

### Fase 2 — Operación

- Calendario editorial con fechas de publicación
- Formularios de solicitud de grabación por parte del cliente
- Gestión de sesiones de grabación
- Panel de tareas y contenido pendiente
- Organización de ideas y guiones
- Integración del NAS con sincronización y verificación por checksum
- Adaptador de WhatsApp para avisos de aprobación

### Fase 3 — Asistencia con IA

- Generación de ideas, guiones, copies y descripciones
- El perfil creativo de cada marca alimenta la generación

### Fase 4 — Integración y comercialización

- Integración con APIs de Instagram, TikTok y YouTube
- Publicación automatizada y lectura de métricas
- Suscripciones, límites de uso y pagos
- Límite configurable de rondas de revisión por pieza

El número de ronda se registra desde la fase 1. Limitarlo es una regla comercial y se aplaza.

## Arquitectura tecnológica

Monolito modular, no microservicios. Sencillo de desarrollar y mantener, pero preparado para evolucionar a SaaS multiempresa.

| Capa | Tecnología |
| --- | --- |
| Frontend | Next.js + TypeScript |
| Backend | Java 21 + Spring Boot |
| Base de datos | PostgreSQL con Row Level Security |
| Migraciones | Flyway |
| Persistencia | Spring Data JPA |
| Seguridad | Spring Security como resource server OIDC |
| Documentación API | OpenAPI / Swagger |
| Pruebas | JUnit, Mockito, Testcontainers |
| Entorno local | Docker Compose |
| CI | GitHub Actions |

### Estructura del repositorio

```
apps/web            frontend Next.js
api/                backend Spring Boot (monolito modular)
infrastructure/     Docker Compose y configuración local
docs/               arquitectura, decisiones (ADR), modelo de datos
```

Se usa `api/` en singular en lugar de `services/api`. El plural sugiere varios servicios y los nombres arrastran la arquitectura.

### Módulos internos del backend

- `tenancy` — workspaces, clientes, marcas, cuentas sociales
- `identity` — usuarios, roles, invitaciones, notificaciones
- `content` — piezas, versiones, estados, aprobaciones
- `files` — registro de archivos, ubicaciones, checksums
- `scheduling` — calendario y fechas de publicación

### Sobre Testcontainers

No es opcional. Las políticas de Row Level Security solo se pueden probar contra un PostgreSQL real, no contra una base en memoria.

No agregar tecnologías ni infraestructura adicionales para el MVP sin una razón técnica clara.

## Almacenamiento de archivos

| Lugar | Guarda | No guarda |
| --- | --- | --- |
| NAS UGREEN DH2300 | Originales y archivos de trabajo pesados | Nada que deba estar disponible en línea |
| Almacenamiento S3 | Entregables finales y archivos que el cliente ve | Material crudo completo |
| PostgreSQL | Metadatos, relaciones, estados, ubicaciones, checksums | Los archivos en sí |

### Reglas del NAS

No exponer el NAS a Internet. La aplicación nunca debe depender de que el NAS esté disponible para funcionar.

La integración es gradual: primero un registro sencillo de archivos con su ubicación y checksum, la sincronización automatizada en una fase posterior.

### El NAS no es respaldo

Un DH2300 tiene dos bahías. Eso da redundancia ante un disco dañado, que no es lo mismo que respaldo. Robo, incendio, ransomware o un borrado accidental se llevan ambas copias.

**Regla:** el NAS es caché de trabajo, nunca la única copia de un archivo.

### Respaldo de la base de datos

Desde el momento en que entren datos reales de clientes, PostgreSQL necesita respaldo automático y verificado. No es opcional ni aplazable.

## Requisitos de seguridad y diseño

### Aislamiento entre workspaces

Toda tabla lleva `workspace_id`. El aislamiento lo obliga Row Level Security en PostgreSQL, no los filtros en el código.

Un filtro en una consulta se puede olvidar, y basta una vez para que un cliente vea datos de otro. Con RLS, la base rechaza la fila aunque la consulta esté mal escrita.

La API fija el workspace del token al abrir cada transacción, en un solo punto de entrada.

### Archivos

Bucket S3 privado siempre. Subida y descarga con URLs prefirmadas de pocos minutos. El archivo viaja del navegador a S3 directamente, nunca a través del backend.

### Secretos

Ninguna credencial, contraseña ni clave privada en Git, ni en `application.yml`. Variables de entorno en local y un gestor de secretos en producción.

### Dependencias

Dependabot y OWASP dependency-check en integración continua. Las acciones de GitHub se fijan por SHA, no por etiqueta.

### Bitácora de auditoría

Registro inmutable de quién hizo qué y cuándo, en especial las aprobaciones. Sirve para resolver disputas con clientes y es un requisito en cualquier revisión de compra.

### Datos personales

La plataforma tratará datos personales de terceros: empleados y clientes de las empresas que salen en video. En Colombia aplica la Ley 1581 de 2012.

Se documenta desde ahora en `docs/`: política de tratamiento, autorizaciones de uso de imagen firmadas por cada persona que sale en video, retención de archivos y qué pasa cuando un cliente se va. Conviene verificar el umbral vigente de registro en el RNBD ante la SIC.

### Diseño funcional

- Los permisos y operaciones sensibles se validan en el backend
- Las transiciones de estado son controladas, no cambios libres de campo
- Cada aprobación queda asociada a una versión concreta de la pieza
- Los archivos llevan metadatos, ubicación y checksum
- Logs estructurados en JSON con identificador de correlación, sin datos personales ni tokens
- El borrado es manual y selectivo, y deja constancia que sobrevive a la operación
- Las operaciones futuras de cuotas se diseñan para evitar consumos duplicados
- Pruebas y validaciones desde el principio, no al final

## Alcance del MVP

1. Crear un workspace
2. Crear y gestionar clientes
3. Crear marcas dentro de un cliente y registrar sus cuentas sociales
4. Crear una pieza de contenido asociada a una marca, con descripción, plataforma y fecha objetivo
5. Asociar la pieza a una o varias cuentas destino
6. Adjuntar o registrar archivos audiovisuales
7. Cambiar el estado de producción
8. Registrar versiones, comentarios y aprobación atribuida
9. Consultar el contenido en vista de lista o tablero

No empezar implementando todas las funcionalidades. Construir y validar una parte funcional completa antes de ampliar el alcance.

## Cómo quiero que trabajes conmigo

Actúa como arquitecto de software y desarrollador senior que acompaña la construcción del proyecto.

- Revisa el repositorio y su estado actual antes de modificar archivos
- Define una hoja de ruta por fases y tareas pequeñas
- Explica brevemente las decisiones técnicas importantes
- Prioriza código mantenible, seguridad y simplicidad
- No generes todo el proyecto de una sola vez
- Implementa una funcionalidad por vez y ejecuta sus pruebas
- No reemplaces archivos ni configuraciones existentes sin revisar sus implicaciones
- Si falta una decisión importante, pregúntame antes de asumirla
- Mantén la documentación actualizada y registra las decisiones arquitectónicas en `docs/adr/`
- Distingue siempre entre lo implementado, lo pendiente y lo solo planeado
- No inventes credenciales, configuraciones ni resultados de pruebas
- No introduzcas costos de infraestructura innecesarios

## Primera tarea

Antes de programar, analiza el estado actual del repositorio.

Si está vacío, propón y construye la estructura mínima del monorepo:

- [ ] Docker Compose con PostgreSQL
- [ ] Backend Spring Boot con un endpoint de salud funcionando
- [ ] Primera migración Flyway, incluida la activación de Row Level Security
- [ ] Instrucciones de ejecución local y cómo validar que funciona
- [ ] Un ADR inicial que registre las decisiones de este documento

Entrega primero un plan breve de ejecución. Luego trabaja en pasos pequeños, verificables y documentados.

**Principio rector:** construir primero una herramienta útil para mi operación real, mantener el alcance controlado y dejar la monetización para una fase posterior.
