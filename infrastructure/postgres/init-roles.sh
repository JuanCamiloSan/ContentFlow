#!/bin/bash
# Crea los roles de la aplicación al inicializar un volumen nuevo de PostgreSQL (ADR 0003).
# Los usa Docker Compose y las pruebas con Testcontainers. Las contraseñas llegan por variables
# de entorno: no van en Git ni en las migraciones.
#
#   contentplan_owner  dueño del esquema; solo lo usa Flyway
#   contentplan_app    todo el tráfico de la API; solo DML, sin ser dueño de nada
#
# Ninguno es superusuario ni tiene BYPASSRLS: sin eso, FORCE ROW LEVEL SECURITY no protege.
set -euo pipefail

: "${CONTENTPLAN_OWNER_PASSWORD:?Define CONTENTPLAN_OWNER_PASSWORD}"
: "${CONTENTPLAN_APP_PASSWORD:?Define CONTENTPLAN_APP_PASSWORD}"

# \getenv lee la contraseña dentro de psql: no aparece en la lista de procesos
psql -v ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" <<'SQL'
\getenv owner_pw CONTENTPLAN_OWNER_PASSWORD
\getenv app_pw CONTENTPLAN_APP_PASSWORD
SELECT current_database() AS db \gset

CREATE ROLE contentplan_owner LOGIN PASSWORD :'owner_pw'
    NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE;
CREATE ROLE contentplan_app LOGIN PASSWORD :'app_pw'
    NOSUPERUSER NOBYPASSRLS NOCREATEDB NOCREATEROLE;

-- Por defecto cualquiera puede conectarse y usar el esquema public
REVOKE ALL ON DATABASE :"db" FROM PUBLIC;
GRANT CONNECT ON DATABASE :"db" TO contentplan_owner, contentplan_app;

-- El dueño crea las tablas; la aplicación solo las usa. Los permisos sobre las tablas los
-- otorgan las migraciones (GRANT y ALTER DEFAULT PRIVILEGES).
ALTER SCHEMA public OWNER TO contentplan_owner;
REVOKE ALL ON SCHEMA public FROM PUBLIC;
GRANT USAGE ON SCHEMA public TO contentplan_app;
SQL
