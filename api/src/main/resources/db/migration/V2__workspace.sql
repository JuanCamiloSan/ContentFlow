-- Tabla raíz: el workspace es la frontera de aislamiento y la única tabla sin workspace_id.
-- Sin RLS a propósito: la política llega en B2, después de los roles de B9 (ADR 0003).
-- Hasta entonces la tabla no debe recibir datos reales.
CREATE TABLE workspace (
    id         uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    name       text        NOT NULL CHECK (btrim(name) <> ''),
    created_at timestamptz NOT NULL DEFAULT now(),
    updated_at timestamptz NOT NULL DEFAULT now()
);
