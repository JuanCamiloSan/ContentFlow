# ADR 0005 — Roles de agencia y de cliente, y comentarios

- **Estado:** propuesta
- **Fecha:** 2026-10-31
- **Origen:** revisión de los roles al construir un prototipo para validar el flujo con el equipo funcional

## Contexto

`prompt-proyecto.md` define cuatro roles con dos ámbitos: Propietario y Editor son de la agencia; Aprobador y Colaborador son del cliente. Al llevarlo a pantallas aparecieron huecos que la documentación no resolvía:

1. "Cambiar estados" no decía si el Editor puede mover una pieza fuera de `En aprobación`, que es lo mismo que aprobarla.
2. No estaba escrito que el Editor lee las observaciones del cliente.
3. El Colaborador "comenta", pero el modelo solo tiene `review.comment`, que es el comentario de una decisión. No hay dónde guardar una opinión que no aprueba ni rechaza.
4. No estaba dicho si el Propietario aprueba.
5. La agencia y el cliente necesitan experiencias distintas: la primera opera un tablero; el segundo revisa videos, muchas veces desde el celular.

## Decisión

### 1. Quién decide qué

| Rol | Ámbito | Aprueba o rechaza | Comenta | Sube versiones | Mueve estados |
| --- | --- | --- | --- | --- | --- |
| Propietario (administrador) | Agencia | Sí, en nombre del cliente | Sí | Sí | Sí |
| Editor | Agencia | **No** | Sí | Sí | Sí, menos fuera de `En aprobación` |
| Aprobador | Cliente | Sí | Sí | No | No |
| Colaborador | Cliente | **No** | Sí | No | No |

- Quien edita no aprueba su propio trabajo. El Editor no puede disparar la transición de `En aprobación` a `Aprobado` ni a `En edición`; esa transición solo sale de una decisión en `review`.
- El Propietario es el administrador de la agencia. No se crea un rol "Administrador" aparte: hasta que haya un empleado de confianza con menos poder que el dueño, serían lo mismo.

### 2. Decidir en nombre del cliente

Cuando el Propietario decide por el cliente, `review` guarda `decided_on_behalf = true`. La bitácora distingue así quién decidió de verdad. Sin esa marca, una aprobación del Propietario se vería igual que la del cliente y se perdería el sentido de "aprobación atribuida".

### 3. Tabla `comment`

`comment` guarda opiniones sueltas sobre una `piece_version`: autor, texto y fecha, con `workspace_id`, RLS y la segunda frontera por `client_id`. No aprueba, no rechaza y no mueve la pieza. El Colaborador opina y el Aprobador decide. El Editor y el Propietario leen los comentarios junto con las observaciones de la decisión.

`text` se puede vaciar, con el mismo mecanismo que `review.comment`: es texto escrito por una persona y debe poder eliminarse si lo pide.

### 4. Dos vistas, una sola aplicación

El frontend tiene dos zonas de rutas, `/agencia` y `/cliente`, sobre el mismo backend y la misma base de datos.

- La agencia ve un tablero, calendario de trabajo, clientes y administración.
- El cliente ve sus videos por revisar y su calendario, pensado para el celular.
- **La vista no es la frontera.** Ocultar un botón mejora la experiencia, pero lo que cada persona puede ver y hacer lo imponen RLS (con la segunda frontera por `client_id`) y los permisos del backend. Un cliente que escribiera la ruta de la agencia a mano no recibiría datos.

## Alternativas descartadas

| Alternativa | Razón |
| --- | --- |
| Que el Editor también apruebe | Quien edita aprobaría su propio trabajo; la aprobación pierde valor ante el cliente |
| Que el Propietario no pueda aprobar | Contradice el "Todo" del rol y obliga a esperar al cliente cuando este delega o no responde |
| Guardar las opiniones en `review` | Mezcla opinar con decidir y obliga a que cada comentario sea una aprobación o un rechazo |
| Rol "Administrador" separado de "Propietario" | No hay hoy un segundo nivel dentro de la agencia; se agrega cuando exista, sin romper nada |
| Dos aplicaciones, una por lado | Duplica despliegue y mantenimiento; con 5 a 8 horas semanales no se sostiene |
| Seguridad por vista | Una ruta olvidada expondría datos; es el mismo error que este proyecto evita con RLS |

## Consecuencias

- `membership` guarda uno de cuatro roles. C5 los implementa con sus pruebas y C10 verifica que el Editor no aprueba y que el Colaborador solo comenta.
- `review` gana la columna `decided_on_behalf`.
- Nueva tarea E15 para `comment`. E5 valida además qué rol dispara cada transición.
- G1 construye las dos zonas de rutas.
- Cambiar el modelo de roles, por ejemplo con roles personalizados por agencia, exige un ADR nuevo.

## Decisiones aún abiertas

El prototipo que se usó para validar el flujo incluye ideas que **no** quedaron aprobadas ni documentadas. Se deciden por separado, antes de tocar el modelo:

- Un estado de "Revisión interna" aparte de "Aprobación cliente". Pasaría la máquina de 8 a 9 estados y cambiaría E3 y E4.
- Calendario de publicación con fecha propuesta por la agencia y confirmada por el cliente. Toca el módulo `scheduling`, que hoy es de la Fase 2.
- Aprobación delegada por cliente: un campo nuevo en `client` que decide si aprueba el cliente o la agencia.
- Plan de servicio por cliente y planes de ContentFlow. Es facturación, aplazada a la Fase 4.
- Publicar en el momento, reservado al Propietario.
