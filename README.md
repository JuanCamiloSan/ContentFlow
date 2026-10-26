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

```bash
cd api
./mvnw spring-boot:run      # arranca en http://localhost:8080
curl http://localhost:8080/health    # {"status":"UP"}
./mvnw verify               # compila y corre las pruebas
```

Por ahora el backend no se conecta a la base de datos (Flyway llega con A4).
