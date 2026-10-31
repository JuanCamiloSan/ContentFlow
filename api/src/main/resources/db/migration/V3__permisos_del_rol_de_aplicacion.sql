-- Permisos del rol de aplicación (ADR 0003). El rol lo crea infrastructure/postgres/init-roles.sh,
-- no esta migración: un CREATE ROLE con contraseña pondría credenciales en Git.
-- La aplicación solo tiene DML. No es dueña de nada, así que no puede alterar el esquema ni
-- desactivar RLS.

-- Explícito en lugar de ALL TABLES: así el rol de aplicación no ve flyway_schema_history
GRANT SELECT, INSERT, UPDATE, DELETE ON workspace TO contentflow_app;

-- Toda tabla futura creada por el dueño queda accesible sin trabajo manual
ALTER DEFAULT PRIVILEGES IN SCHEMA public
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO contentflow_app;
