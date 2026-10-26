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

Solo existe la estructura del repositorio y la documentación. El backend, el frontend y la
infraestructura local están pendientes (ver el backlog).

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

El backend (A3) todavía no existe.
