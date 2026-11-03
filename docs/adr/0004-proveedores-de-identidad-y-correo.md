# ADR 0004 — Proveedor de identidad: inicio de sesión con Google

- **Estado:** aceptada
- **Fecha:** 2026-11-03 (reemplaza la propuesta del 2026-10-31, que elegía Auth0 y Resend y nunca llegó a aceptarse)
- **Origen:** decisiones pendientes de `CLAUDE.md` y del ADR 0001 (decisión 3); desbloquea el bloque C del backlog

El nombre del archivo conserva "y-correo" porque otros documentos lo enlazan. La propuesta original incluía un proveedor de correo; esta versión lo descarta.

## Contexto

El ADR 0001 decidió usar un proveedor OIDC externo detrás de Spring Security como resource server, y dejó abierto cuál. La propuesta inicial era Auth0 para la identidad y Resend para el correo.

Al revisarla pesaron cuatro cosas:

- La disponibilidad es de 5 a 8 horas semanales y el costo de infraestructura debe ser mínimo.
- Cada correo que envía el sistema exige un dominio propio, que no está comprado, y un proveedor de correo con su configuración, sus límites y su revisión de la Ley 1581.
- Con contraseñas propias hay que enviar correos de verificación y de recuperación. Auth0 usa para eso un remitente de pruebas con límites y no apto para producción, según su documentación.
- Quien se registra necesita un correo asociado para recuperar el acceso y administrar su cuenta. Una cuenta de Google ya lo es: Google verifica el correo y resuelve la recuperación.

## Decisión

- **Identidad: solo Google, validado directamente por el backend.** Sin contraseñas propias, sin verificación de correo y sin recuperación de contraseña implementadas por nosotros.
  - Emisor: `https://accounts.google.com`.
  - Audience: el ID de cliente OAuth del proyecto de Google Cloud.
  - Se usan los permisos básicos (`openid`, `email`, `profile`).
  - El identificador estable de la persona es el `sub`. Se exige `email_verified = true`.
  - Los roles viven en `membership`, no en Google. Al proveedor solo se le pide identidad.
- **Sin Auth0.** Spring Security valida el token de Google como cualquier otro resource server OIDC. El tenant de Auth0 creado a mano el 2026-10-31 queda sin uso y puede eliminarse.
- **Sin correo transaccional en el MVP.** No hay Resend ni adaptador de correo, y no hace falta comprar el dominio para avanzar con el bloque C. Los avisos van al buzón de la aplicación ([ADR 0006](0006-registro-abierto-administrador-de-plataforma-y-buzon.md)) y las invitaciones se entregan como enlace que el propietario copia y comparte por su cuenta.
- **Frontend.** La sesión y la renovación del token en Next.js las maneja una librería con el proveedor de Google (Auth.js). Los tokens de identidad de Google duran más o menos una hora, así que la renovación es trabajo del bloque G, no del backend.
- **Pruebas.** Usan tokens JWT simulados, nunca Google real. No dependen de la red.
- **Sin Keycloak ni otro servicio de identidad en Docker Compose.**

## Alternativas descartadas

| Alternativa | Razón |
| --- | --- |
| Auth0 con Google como conexión social | Agrega un intermediario, un tenant, un límite de tokens M2M y un plan de pago posible, sin aportar nada mientras el único método de entrada sea Google |
| Usuario y contraseña propios, con un Gmail obligatorio en el formulario | Es autenticación escrita a mano, prohibida por `CLAUDE.md`. Un correo escrito en un formulario tampoco prueba que sea de quien se registra: verificarlo exige enviar un correo |
| Usuario y contraseña a través de un proveedor | Exige enviar correos de verificación y de recuperación, y por tanto un remitente fiable y un dominio |
| Keycloak autoalojado | Obliga a desplegar, respaldar, parchear y vigilar el login en producción |
| AWS Cognito | Configuración más incómoda; solo tendría sentido si todo el despliegue estuviera en AWS |

## Riesgos abiertos

No verificados todavía.

1. **Pantalla de consentimiento en producción.** En modo de pruebas, Google limita los usuarios que pueden entrar. Para clientes reales hay que publicar la aplicación, y la pantalla suele pedir enlaces a la política de privacidad y a los términos, y puede pedir un dominio. Se verifica al crear el cliente OAuth (C1) y de nuevo antes de D8.
2. **El token de identidad como credencial de la API.** Es válido mientras frontend y backend sean del mismo producto. Si algún día la API se abre a terceros, habrá que emitir tokens propios o pasar a un proveedor que los emita. Se confirma en C2 que Spring Security valida el token de Google con el emisor y el audience indicados.
3. **Ley 1581 de 2012.** Enviar datos personales a Google es una transmisión internacional. La Circular Externa 005 de 2017 de la SIC incluyó a EE. UU. entre los países con nivel adecuado, pero la lista pudo cambiar y hay que confirmar la vigente. Debe revisarlo quien lleve la documentación de tratamiento de datos; esto no es asesoría legal.
4. **Dependencia de Google.** Quien pierda o cierre su cuenta de Google pierde el acceso, y no hay otro método de entrada. Es una limitación aceptada para el MVP. Añadir contraseñas o más proveedores exige un proveedor de identidad intermedio, que se reconsidera con un ADR nuevo y no obliga a reescribir el backend, porque este solo depende de OIDC estándar.
5. **Alojamiento de la aplicación.** Para que los consumidores la usen, el backend, PostgreSQL con su respaldo y el almacenamiento S3 tienen que estar desplegados. No está elegido ni presupuestado, y probablemente es el costo mayor de la primera etapa. El backlog lo deja como decisión de fase posterior.

## Consecuencias

- `email_verified` lo garantiza Google, así que la aceptación de invitaciones (C9, [ADR 0003](0003-contexto-de-sesion-y-roles-de-postgres.md)) puede exigir que el correo verificado coincida con el de la invitación.
- No se necesita la API de administración de Auth0, ni su token M2M, ni la decisión de desactivar el registro. El registro es abierto ([ADR 0006](0006-registro-abierto-administrador-de-plataforma-y-buzon.md)).
- El ID de cliente de Google no es un secreto, pero el secreto de cliente, si el frontend lo usa, vive solo en variables de entorno. Ninguna credencial va en Git ni en `application.yml`.
- Se retira la tarea C7 (adaptador de correo) del MVP. Vuelve en una fase posterior, con un dominio propio.
- Cambiar de proveedor de identidad más adelante cuesta migrar usuarios, no reescribir el código del backend.
