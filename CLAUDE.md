# ContentFlow

Plataforma de gestión de contenido audiovisual para empresas. Monolito modular multiempresa.

Primero sirve a una operación real; después debe poder venderse como SaaS. Ese orden decide las prioridades.

## Antes de empezar

Lee en este orden, una sola vez por sesión:

1. `docs/prompt-proyecto.md` — alcance, arquitectura y cómo trabajar
2. `docs/modelo-datos.md` — tablas, RLS, propiedad y borrado
3. `docs/backlog.md` — tareas, dependencias y qué sigue

Revisa el estado real del repositorio antes de modificar nada. Distingue siempre entre lo implementado, lo pendiente y lo solo planeado.

## Stack

- Backend: Java 21, Spring Boot, Spring Data JPA, Spring Security
- Base de datos: PostgreSQL con Row Level Security
- Migraciones: Flyway
- Frontend: Next.js con TypeScript
- Pruebas: JUnit, Mockito, Testcontainers
- Local: Docker Compose
- CI: GitHub Actions

## Estructura

```
api/                backend Spring Boot (monolito modular)
apps/web/           frontend Next.js
infrastructure/     Docker Compose y configuración local
docs/               arquitectura, decisiones (ADR), modelo de datos
```

Módulos dentro de `api/`: `tenancy`, `identity`, `content`, `files`, `scheduling`.

## Comandos

```bash
# Levantar dependencias locales
docker compose -f infrastructure/docker-compose.yml up -d

# Backend
cd api && ./mvnw spring-boot:run
cd api && ./mvnw verify          # compila y corre todas las pruebas

# Frontend
cd apps/web && npm run dev
cd apps/web && npm run lint && npx tsc --noEmit
```

## Reglas que no se negocian

**Aislamiento.** Toda tabla nueva lleva `workspace_id` y nace con su política de RLS en la misma migración. Nunca se confía en un filtro en el código. Usa la plantilla de `docs/` o la migración más reciente como referencia.

**Contexto de tenant.** El workspace se fija con `SET LOCAL app.workspace_id` al abrir la transacción, en un único punto. Nunca `SET` sin `LOCAL`: las conexiones del pool se reutilizan.

**Secretos.** Ninguna credencial, contraseña ni clave en Git ni en `application.yml`. Variables de entorno en local.

**Logs.** JSON estructurado con identificador de correlación. Nunca registrar datos personales, tokens ni rutas de archivos de clientes.

**Archivos.** Nunca pasan por el backend. URLs prefirmadas con expiración corta, subida y descarga directas a S3.

**Estados.** Una pieza cambia de estado solo por el endpoint de transición, que valida contra la máquina de estados. Nunca editando el campo directamente.

**Versiones.** Son inmutables. Un rechazo crea una fila nueva con `round_number` incrementado; no se sobrescribe la anterior.

## Cómo trabajar

- Una funcionalidad por vez, con sus pruebas, en un commit con mensaje que explique el porqué
- No generes el proyecto entero de una sola vez
- No reemplaces archivos ni configuración existente sin revisar las implicaciones
- Si falta una decisión importante, pregunta antes de asumirla
- Registra las decisiones arquitectónicas en `docs/adr/`
- No inventes credenciales, configuraciones ni resultados de pruebas
- No introduzcas dependencias ni infraestructura que el MVP no necesite

## Definición de terminado

Una tarea está lista cuando cumple las cuatro:

1. Corre en local sin errores
2. Tiene al menos una prueba que falla si se rompe
3. La documentación afectada quedó actualizada
4. Está en un commit con mensaje explicativo

Nunca marques una tarea como terminada con pruebas fallando o implementación parcial.

## Decisiones pendientes

No las asumas. Si una tarea depende de alguna, pregunta:

- Proveedor de identidad: Keycloak autoalojado o servicio gestionado
- Proveedor de correo transaccional
- Proveedor de almacenamiento S3 en producción
- Alcance del `audit_log`: solo eventos clave o toda escritura
