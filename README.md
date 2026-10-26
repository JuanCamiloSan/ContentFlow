# ContentFlow

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

Existen la estructura, PostgreSQL local y un backend con `GET /health`. El frontend y el resto de la
infraestructura están pendientes (ver el backlog).

## Documentación

- [`docs/prompt-proyecto.md`](docs/prompt-proyecto.md) — alcance, arquitectura y cómo trabajar
- [`docs/modelo-datos.md`](docs/modelo-datos.md) — tablas, RLS, propiedad y borrado
- [`docs/backlog.md`](docs/backlog.md) — tareas, dependencias y qué sigue
- [`CLAUDE.md`](CLAUDE.md) — reglas para trabajar con Claude Code

## Ejecución local

### Base de datos (PostgreSQL)

Requiere Docker con Compose.

```bash
cp infrastructure/.env.example infrastructure/.env
# edita infrastructure/.env y define POSTGRES_PASSWORD
docker compose -f infrastructure/docker-compose.yml up -d
```

Para validar que funciona:

```bash
docker compose -f infrastructure/docker-compose.yml ps    # postgres en estado "healthy"
docker compose -f infrastructure/docker-compose.yml exec postgres \
  psql -U contentflow -d contentflow -c "select 1"
```

Los datos persisten en el volumen `postgres_data`. El puerto solo se publica en `127.0.0.1`.
Para borrar todo, incluidos los datos: `docker compose -f infrastructure/docker-compose.yml down -v`.

### Backend (Spring Boot)

Requiere JDK 21. `JAVA_HOME` debe apuntar a un JDK 21: el wrapper de Maven usa esa variable, no
el `java` del `PATH`.

El backend lee la conexión a la base desde variables de entorno (`POSTGRES_USER`,
`POSTGRES_PASSWORD` y, opcionales, `POSTGRES_DB` y `POSTGRES_PORT`). Sin usuario y contraseña la
app no arranca. En PowerShell, para cargarlas desde `infrastructure/.env`:

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

Al arrancar, Flyway aplica las migraciones de `api/src/main/resources/db/migration`. Para ver el
historial:

```bash
docker compose -f infrastructure/docker-compose.yml exec postgres \
  psql -U contentflow -d contentflow -c "select * from flyway_schema_history"
```
