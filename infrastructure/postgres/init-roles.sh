#!/bin/bash
# Crea los roles de la aplicación al inicializar un volumen nuevo de PostgreSQL (ADR 0003).
# Los usa Docker Compose y las pruebas con Testcontainers. Las contraseñas llegan por variables
# de entorno: no van en Git ni en las migraciones.
#
#   contentflow_owner  dueño del esquema; solo lo usa Flyway
#   contentflow_app    todo el tráfico de la API; solo DML, sin ser dueño de nada
#
# Ninguno es superusuario ni tiene BYPASSRLS: sin eso, FORCE ROW LEVEL SECURITY no protege.
set -euo pipefail

: "${CONTENTFLOW_OWNER_PASSWORD:?Define CONTENTFLOW_OWNER_PASSWORD}"
: "${CONTENTFLOW_APP_PASSWORD:?Define CONTENTFLOW_APP_PASSWORD}"

# \getenv lee la contraseña dentro de psql: no aparece en la lista de procesos
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<'SQL'
\getenv owner_pw CONTENTFLOW_OWNER_PASSWORD
\getenv app_pw CONTENTFLOW_APP_PASSWORD
SELECT current_database() AS db \gset

CREATE ROLE contentflow_owner LOGIN PASSWORD :'owner_pw'
    NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE;
CREATE ROLE contentflow_app LOGIN PASSWORD :'app_pw'
    NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE;

-- Por defecto cualquiera puede conectarse y usar el esquema public
REVOKE ALL ON DATABASE :"db" FROM PUBLIC;
GRANT CONNECT ON DATABASE :"db" TO contentflow_owner, contentflow_app;

-- El dueño crea las tablas; la aplicación solo las usa. Los permisos sobre las tablas los
-- otorgan las migraciones (GRANT y ALTER DEFAULT PRIVILEGES).
ALTER SCHEMA public OWNER TO contentflow_owner;
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO contentflow_app;
SQL
