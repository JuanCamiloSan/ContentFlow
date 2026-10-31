# ADR 0004 — Proveedores de identidad y de correo transaccional

- **Estado:** propuesta
- **Fecha:** 2026-10-31
- **Origen:** decisiones pendientes de `CLAUDE.md` y del ADR 0001 (decisión 3); desbloquea el bloque C del backlog

## Contexto

El ADR 0001 decidió usar un proveedor OIDC externo detrás de Spring Security como resource server, y dejó abierto cuál. Además falta un proveedor de correo para las invitaciones y los avisos (C6 a C9).

Restricciones que pesan en la elección: disponibilidad de 5 a 8 horas semanales, costo de infraestructura mínimo y acceso solo por invitación. Operar un servicio de seguridad propio en producción compite con construir funcionalidades.

## Decisión

- **Identidad: Auth0.** Plan gratuito durante el desarrollo. El backend valida los tokens JWT contra el emisor del tenant y exige el *audience* de la API.
  - Emisor: `https://dev-jaw4x2yafxemfeu4.us.auth0.com/` (la barra final importa; región EE. UU.)
  - Audience: `https://api.contentflow`
  - Los roles viven en `membership`, no en Auth0. Al proveedor solo se le pide identidad.
- **Correo transaccional: Resend.** Integración nombrada de Auth0 y detrás de la abstracción de notificaciones (C6), así que cambiarlo es reescribir un adaptador.
- **Primera etapa a costo cero, hasta unos 10 consumidores.** Sin dominio propio y sin plan de pago:
  - Las invitaciones se entregan como un enlace de un solo uso que el administrador copia y envía por su cuenta (por ejemplo por WhatsApp). El correo por Resend queda como mejora para cuando se compre el dominio.
  - Los correos que envía Auth0 por su cuenta (verificación y recuperación de contraseña) salen por su proveedor incluido: remitente fijo, 10 correos por minuto, sin plantillas propias y no apto para producción según su documentación. Con pocos usuarios funciona; algunos pueden llegar a spam.
  - Se vuelve a evaluar al pasar de esa cifra o cuando el aspecto o la entrega de los correos estorbe.
- **Tenants.** El plan gratuito permite un solo tenant (comprobado el 2026-10-31: "Limit Reached" al crear otro). Un tenant sirve para desarrollo y staging, con aplicaciones separadas por ambiente y sin cuentas de prueba innecesarias.
- **Producción: punto de decisión antes de D8** (cargar clientes reales). Se pasa este mismo tenant a un plan de pago y se etiqueta como producción solo si las condiciones de uso de Auth0 no permiten producción en el plan gratuito, o si sus límites de soporte y de registro de actividad resultan insuficientes. El número de usuarios no obliga a pagar mientras se esté muy por debajo de los 25.000. Se evita crear un tenant nuevo para producción: migrar usuarios a otro tenant puede obligar a restablecer contraseñas.
- **Pruebas.** Usan tokens JWT simulados, no Auth0 real. No consumen cupo y no dependen de la red.
- **Sin Keycloak en Docker Compose.** C1 deja de levantarlo.

## Alternativas descartadas

| Alternativa | Razón |
| --- | --- |
| Keycloak autoalojado | Obliga a desplegar, respaldar, parchear y vigilar el login en producción; el costo en tiempo supera al del plan de pago de un servicio gestionado |
| Keycloak en ambientes previos y Auth0 en producción | Lo aprobado en staging no sería lo que corre en producción: difieren el formato del `sub`, `email_verified` y los claims, y C9 depende del segundo |
| AWS Cognito | Configuración más incómoda; solo tendría sentido si todo el despliegue estuviera en AWS |
| Amazon SES | El más barato a escala, pero con más configuración; se reconsidera si el despliegue acaba en AWS |
| Postmark | Casi sin plan gratuito |

## Riesgos abiertos

No verificados todavía. Hasta cerrarlos, esta decisión queda como propuesta.

1. **Resend en el plan gratuito de Auth0.** La documentación lista a Resend como integración, pero no confirma que el plan gratuito permita un proveedor de correo propio. El proveedor incluido de Auth0 solo sirve para pruebas: remitente fijo, 10 correos por minuto y sin plantillas. Se verifica en el panel antes de C7.
2. **Etiqueta *Development* del tenant.** Se desconoce si impone límites o condiciones a un uso de producción. Se verifica antes de D8.
3. **Costo y alcance del plan de pago.** La cifra de unos US$35 al mes viene de fuentes secundarias. Se verifica en la página oficial de precios antes de D8.
4. **Dominio propio, sin comprar.** Resend necesita un dominio verificado por DNS para enviar desde una dirección propia. Bloquea C7; C8 y C9 siguen con invitación por enlace copiable. `contentflow.com` es un dominio *premium*; no se compra.
5. **Ley 1581 de 2012.** Enviar datos personales a Auth0 (EE. UU.) y a Resend es una transferencia o transmisión internacional. La Circular Externa 005 de 2017 de la SIC incluyó a EE. UU. entre los países con nivel adecuado, pero la lista pudo cambiar y hay que confirmar la vigente. Falta la región de datos de Resend y su DPA (`resend.com/legal/dpa`). Debe revisarlo quien lleve la documentación de tratamiento de datos; esto no es asesoría legal.
6. **Alojamiento de la aplicación.** Para que los consumidores la usen, el backend, PostgreSQL con su respaldo y el almacenamiento S3 tienen que estar desplegados. No está elegido ni presupuestado, y probablemente es el costo mayor de la primera etapa. El backlog lo deja como decisión de fase posterior.

## Consecuencias

- El plan gratuito da 25.000 usuarios activos al mes, muy por encima de la operación propia. Dentro del plan gratuito, el recurso que primero se agotaría son los **1.000 tokens M2M al mes**, que consume la API de administración de Auth0. En C8 se llama con el token en caché y las pruebas no lo usan.
- Si en C8 se desactiva el registro abierto (*Disable Sign Ups*), las cuentas de los invitados se crean desde el backend por la API de administración. Es una decisión de diseño de C8 y C9.
- La aplicación creada en Auth0 para el frontend lleva la confirmación de URI no verificable desactivada, por usar `localhost`. La aplicación de producción debe usar URL `https` y mantenerla activa.
- Ningún secreto va en Git: el *client secret* de Auth0 y la llave de Resend viven en variables de entorno.
- Cambiar de proveedor de identidad más adelante cuesta migrar usuarios, no reescribir el código, porque el backend solo depende de OIDC estándar.
