-- Plantilla de migración para una tabla de negocio (B7). Toda tabla nueva con workspace_id nace
-- con su política de RLS en la misma migración (CLAUDE.md, ADR 0003).
--
-- Cómo usarla: copia este archivo a api/src/main/resources/db/migration/V<n>__<nombre>.sql,
-- reemplaza {{tabla}} y agrega las columnas propias donde indica. Si la tabla además depende de un
-- cliente (client_id), la política suma la segunda condición del ADR 0003, decisión 7.
--
-- Esta plantilla la ejecuta tal cual TablaDeNegocioRlsTest: si deja de crear una tabla aislada,
-- la prueba falla. El rol de aplicación recibe DML por ALTER DEFAULT PRIVILEGES (V3), así que no
-- hace falta un GRANT por tabla.

CREATE TABLE {{tabla}} (
    id           uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    -- Sin ON DELETE CASCADE: el borrado es manual y selectivo (modelo-datos.md)
    workspace_id uuid        NOT NULL REFERENCES workspace (id),
    -- columnas propias de la tabla aquí
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now()
);

-- La política filtra por esta columna en cada consulta
CREATE INDEX {{tabla}}_workspace_id_idx ON {{tabla}} (workspace_id);

ALTER TABLE {{tabla}} ENABLE ROW LEVEL SECURITY;
-- FORCE: la política aplica también al dueño de la tabla
ALTER TABLE {{tabla}} FORCE ROW LEVEL SECURITY;

-- USING filtra lo que se lee; WITH CHECK impide escribir una fila de otro workspace.
-- Se escriben las dos aunque PostgreSQL reutilice USING si falta WITH CHECK: queda explícito y
-- partir la política por comando no deja las escrituras abiertas. PoliticasRlsTest las exige.
CREATE POLICY workspace_isolation ON {{tabla}}
    USING (workspace_id = current_workspace())
    WITH CHECK (workspace_id = current_workspace());
