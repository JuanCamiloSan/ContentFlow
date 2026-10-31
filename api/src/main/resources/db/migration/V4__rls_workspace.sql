-- RLS en workspace (B2, ADR 0003): ensayo del patrón que seguirán todas las tablas.
-- workspace es la raíz y no tiene workspace_id: su propio id es la frontera.

-- Falla si no hay contexto en lugar de devolver cero filas, que se vería igual que "no hay datos".
-- NULLIF cubre la cadena vacía que queda tras un RESET o set_config(..., '', true).
-- STABLE y no VOLATILE para que el planificador no la evalúe fila por fila.
CREATE FUNCTION current_workspace() RETURNS uuid
LANGUAGE plpgsql STABLE AS $$
DECLARE
    v uuid := NULLIF(current_setting('app.workspace_id', true), '')::uuid;
BEGIN
    IF v IS NULL THEN
        RAISE EXCEPTION 'No hay contexto de workspace' USING ERRCODE = 'insufficient_privilege';
    END IF;
    RETURN v;
END $$;

ALTER TABLE workspace ENABLE ROW LEVEL SECURITY;
-- FORCE: la política aplica también al dueño de la tabla
ALTER TABLE workspace FORCE ROW LEVEL SECURITY;

-- USING filtra lo que se lee; WITH CHECK impide escribir una fila de otro workspace.
CREATE POLICY workspace_isolation ON workspace
    USING (id = current_workspace())
    WITH CHECK (id = current_workspace());
