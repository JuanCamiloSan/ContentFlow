# ContentPlan

Plataforma de gestión de contenido audiovisual para empresas. Monolito modular multiempresa.

Primero sirve a una operación real; después debe poder venderse como SaaS.

## Estructura

```
api/                backend Spring Boot (monolito modular)
apps/web/           frontend Next.js
infrastructure/     Docker Compose y configuración local
docs/               arquitectura, decisiones (ADR), modelo de datos
```

## Estado

Los bloques A (cimientos) y B (aislamiento) están cerrados: monorepo, PostgreSQL local con tres roles y
Row Level Security, un backend con `GET /health`, Flyway, logs estructurados, CI y el contexto de sesión
por transacción, con sus pruebas de aislamiento. El siguiente es el bloque C (identidad, con inicio de
sesión de Google). El frontend y el resto de la infraestructura están pendientes (ver el backlog).

## Documentación

- [`docs/prompt-proyecto.md`](docs/prompt-proyecto.md) — alcance, arquitectura y cómo trabajar
- [`docs/modelo-datos.md`](docs/modelo-datos.md) — tablas, RLS, propiedad y borrado
- [`docs/backlog.md`](docs/backlog.md) — tareas, dependencias y qué sigue
- [`docs/adr/`](docs/adr/) — decisiones de arquitectura (ADR 0001 a 0006)
- [`CLAUDE.md`](CLAUDE.md) — reglas para trabajar con Claude Code

## Ejecución local

### Base de datos (PostgreSQL)

Requiere Docker con Compose.

```bash
cp infrastructure/.env.example infrastructure/.env
# edita infrastructure/.env y define POSTGRES_PASSWORD, CONTENTPLAN_OWNER_PASSWORD
# y CONTENTPLAN_APP_PASSWORD (tres contraseñas distintas)
docker compose -f infrastructure/docker-compose.yml up -d
```

Al crear el volumen, `infrastructure/postgres/init-roles.sh` crea dos roles sin superusuario ni
`BYPASSRLS`: `contentplan_owner` (dueño del esquema, para Flyway) y `contentplan_app` (solo DML,
para la API). Ver [ADR 0003](docs/adr/0003-contexto-de-sesion-y-roles-de-postgres.md). El script solo
corre con un volumen nuevo: si ya tenías uno, recréalo con `down -v` (borra los datos locales).
La API se conecta con `contentplan_app` y Flyway con `contentplan_owner` (B10); `POSTGRES_USER` es solo
administración y la aplicación no lo usa.

Para validar que funciona:

```bash
docker compose -f infrastructure/docker-compose.yml ps    # postgres en estado "healthy"
docker compose -f infrastructure/docker-compose.yml exec postgres \
  psql -U contentplan -d contentplan -c "select 1"
```

Los datos persisten en el volumen `postgres_data`. El puerto solo se publica en `127.0.0.1`.
Para borrar todo, incluidos los datos: `docker compose -f infrastructure/docker-compose.yml down -v`.

### Backend (Spring Boot)

Requiere JDK 21. `JAVA_HOME` debe apuntar a un JDK 21: el wrapper de Maven usa esa variable, no
el `java` del `PATH`.

El backend lee la conexión a la base desde variables de entorno (`CONTENTPLAN_APP_PASSWORD`,
`CONTENTPLAN_OWNER_PASSWORD` y, opcionales, `POSTGRES_HOST`, `POSTGRES_DB` y `POSTGRES_PORT`) y el ID de cliente
de Google (`GOOGLE_CLIENT_ID`), que es el audience que deben llevar los tokens. Sin las dos contraseñas ni el
ID de cliente la app no arranca. Toda ruta exige un token de Google (`Authorization: Bearer ...`), salvo `/health`
y la documentación OpenAPI; un token inválido recibe 401 incluso en esas rutas. En PowerShell, para cargarlas
desde `infrastructure/.env`:

```powershell
Get-Content infrastructure\.env | Where-Object { $_ -match '^\s*[A-Z_]+=' } |
  ForEach-Object { $k,$v = $_ -split '=',2; Set-Item "Env:$k" $v }
```

En bash: `set -a; source infrastructure/.env; set +a`.

```bash
cd api
./mvnw spring-boot:run      # arranca en http://localhost:8080, con PostgreSQL levantado
curl http://localhost:8080/health    # {"status":"UP"}
./mvnw verify               # compila y corre las pruebas (necesita Docker, no las variables)
```

Con la API arriba, la documentación interactiva está en http://localhost:8080/swagger-ui/index.html
y la definición OpenAPI en http://localhost:8080/v3/api-docs. Estas rutas no piden token (`/health`, `/v3/api-docs` y `/swagger-ui`),
así que antes de desplegar hay que restringir o desactivar la documentación.

Al arrancar, Flyway aplica las migraciones de `api/src/main/resources/db/migration`. Para ver el
historial:

```bash
docker compose -f infrastructure/docker-compose.yml exec postgres \
  psql -U contentplan -d contentplan -c "select * from flyway_schema_history"
```

#### Contexto de sesión

Cada transacción que abre la API fija `app.user_id` y `app.workspace_id` con `set_config(..., true)`,
en un único punto: `TransaccionesConContexto` (B3, [ADR 0003](docs/adr/0003-contexto-de-sesion-y-roles-de-postgres.md)).
Sin contexto, toda consulta a una tabla con RLS falla con "No hay contexto de workspace".

El contexto sale del token de Google (C4): `app.user_id` es la persona del `sub` del token, que
`resolver_usuario` traduce a su `uuid` y registra la primera vez, y `app.workspace_id` es el workspace
de su `membership`. Si pertenece a uno solo, se usa ese; si pertenece a varios, hay que mandar el
encabezado `X-Workspace-Id`, que solo se acepta si es suyo (403 si no, 400 si falta o no es un UUID).
Una persona sin workspace solo llega a los endpoints marcados con `@SinWorkspace`. Ya no existen las
cabeceras de prueba del bloque B. El administrador de la plataforma no puede entrar a un workspace
ajeno hasta E13, que crea `audit_log`.

## Integración continua

`.github/workflows/ci.yml` corre `./mvnw verify` de `api/` en cada push y cada pull request, con
JDK 21 y el Docker del runner para Testcontainers. El reporte de pruebas (`surefire-reports`) se
guarda como artefacto `reporte-pruebas`, incluso si las pruebas fallan. Las acciones de GitHub están
fijadas por SHA de commit; para actualizarlas, cambia el SHA y deja la versión en el comentario.

## Imagen Docker de la API

`api/Dockerfile` construye la imagen en dos etapas (JDK 21 para compilar, JRE 21 para ejecutar) y
corre como usuario sin privilegios. No lleva credenciales: la conexión llega por variables de entorno
(`CONTENTPLAN_APP_PASSWORD`, `CONTENTPLAN_OWNER_PASSWORD`, `GOOGLE_CLIENT_ID`, y opcionales `POSTGRES_DB` y `POSTGRES_PORT`).
`POSTGRES_HOST` vale `localhost` por defecto; dentro de un contenedor hay que apuntarlo al servicio
de la base.

```bash
cd api
docker build -t contentplan-api:local .
docker run --rm -p 8080:8080 -e POSTGRES_HOST=<host-de-la-base> \
  -e CONTENTPLAN_APP_PASSWORD=... -e CONTENTPLAN_OWNER_PASSWORD=... \
  -e GOOGLE_CLIENT_ID=... contentplan-api:local
```

Las pruebas no corren dentro del build de la imagen: las ejecuta CI con `./mvnw verify`.

## Logs

La API escribe un JSON por línea en consola (formato `logstash` de Spring Boot). Cada petición lleva
un `correlationId`, que se devuelve en la cabecera `X-Correlation-Id`; si el cliente manda uno válido
se reutiliza. Nunca se registran datos personales, tokens ni rutas de archivos de clientes. Decisión
y reglas en [`docs/adr/0002-logs-estructurados-nativos-de-spring-boot.md`](docs/adr/0002-logs-estructurados-nativos-de-spring-boot.md).
