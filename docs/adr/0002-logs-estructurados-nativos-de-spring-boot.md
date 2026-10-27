# ADR 0002 — Logs estructurados con el soporte nativo de Spring Boot

- **Estado:** aceptada
- **Fecha:** 2026-10-27
- **Origen:** tarea A11 del backlog; concreta la decisión 6 del ADR 0001

## Contexto

El ADR 0001 decidió logs JSON con identificador de correlación desde el inicio. Faltaba elegir cómo producirlos: el soporte de logging estructurado que trae Spring Boot (`logging.structured.format.console`) o una librería externa como `logstash-logback-encoder`.

## Decisión

- Formato `logstash` nativo de Spring Boot, activado por configuración en `application.yml`. Sin dependencias nuevas.
- Un filtro de servlet (`CorrelationIdFilter`) asigna un identificador por petición, lo pone en el MDC como `correlationId` y lo devuelve en la cabecera `X-Correlation-Id`.
- El identificador del cliente solo se acepta si cumple `[A-Za-z0-9_-]{1,64}`; si no, se genera un UUID. Así nadie inyecta saltos de línea ni texto arbitrario en los logs.
- La única línea de log por petición registra método, estado y duración. **No** registra ruta, query string ni cabeceras.

## Reglas de logging

1. Nunca registrar datos personales, tokens, credenciales ni rutas de archivos de clientes.
2. No volcar objetos de petición, cabeceras ni entidades completas; registrar identificadores opacos (UUID).
3. La prueba `LogsEstructuradosTest` falla si la línea de petición empieza a incluir tokens o query strings. Quien amplíe lo que se registra debe ampliar esa prueba.

## Alternativa descartada

`logstash-logback-encoder`: ofrece enmascarado automático de campos y argumentos estructurados, pero agrega una dependencia y un `logback-spring.xml` que mantener. El MVP solo necesita JSON, correlación y una regla vigilada por prueba. Cambiar a la librería más adelante es pequeño: el filtro y el MDC se conservan.

## Consecuencias

- Ninguna herramienta impide por sí sola `log.info("token: " + token)`: la regla se sostiene con revisión y con la prueba anterior, no con el formato.
- Los campos de MDC que se agreguen después (por ejemplo `workspaceId` en el bloque B) salen en el JSON sin cambios de configuración.
- El enmascarado automático queda como mejora si la regla manual resulta insuficiente.
