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

Todavía no hay nada que ejecutar. Esta sección se completa con A2 (Docker Compose con
PostgreSQL) y A3 (backend con `GET /health`).
