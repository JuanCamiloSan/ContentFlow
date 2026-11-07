-- Buzón de notificaciones (C6, ADR 0006). Sigue la plantilla de docs/plantilla-migracion-rls.sql y suma
-- que cada aviso lo ve solo su destinatario.
--
-- Lo escribe el sistema para una persona concreta (por ejemplo, avisar al editor de que rechazaron su
-- versión); la persona solo puede leerlo y marcarlo como leído. WhatsApp se agrega en la Fase 2 como
-- otro canal, solo para los avisos marcados como importantes.

CREATE TABLE notification (
    id           uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    -- Sin ON DELETE CASCADE: el borrado es manual y selectivo (modelo-datos.md)
    workspace_id uuid        NOT NULL REFERENCES workspace (id),
    -- La persona que lo recibe
    user_id      uuid        NOT NULL REFERENCES app_user (id),
    -- Hoy solo el buzón de la aplicación; el CHECK se amplía cuando llegue otro canal
    channel      text        NOT NULL DEFAULT 'app' CHECK (channel IN ('app')),
    important    boolean     NOT NULL DEFAULT false,
    -- Código del aviso (por ejemplo, version_rechazada): lo usa el frontend para iconos y filtros
    kind         text        NOT NULL CHECK (kind ~ '^[a-z][a-z0-9_]*$' AND length(kind) <= 64),
    -- Texto que escribe el sistema, nunca una persona: así no guarda datos personales difíciles de borrar
    message      text        NOT NULL CHECK (btrim(message) <> '' AND length(message) <= 500),
    read_at      timestamptz,
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now()
);

CREATE INDEX notification_workspace_id_idx ON notification (workspace_id);
-- El buzón de una persona, lo más reciente primero
CREATE INDEX notification_user_id_created_at_idx ON notification (user_id, created_at DESC);

ALTER TABLE notification ENABLE ROW LEVEL SECURITY;
-- FORCE: la política aplica también al dueño de la tabla
ALTER TABLE notification FORCE ROW LEVEL SECURITY;

CREATE POLICY workspace_isolation ON notification
    USING (workspace_id = current_workspace())
    WITH CHECK (workspace_id = current_workspace());

-- Cada aviso lo ve solo su destinatario, aunque otra persona del mismo workspace lo pida por su id.
-- RESTRICTIVE se suma con Y a la política de arriba. No cubre INSERT: el sistema inserta avisos para
-- otras personas del workspace. Consecuencia: un INSERT no puede usar RETURNING, porque la fila nueva
-- no es visible para quien la inserta (ver Notificador).
CREATE POLICY solo_destinatario_lee ON notification AS RESTRICTIVE FOR SELECT
    USING (user_id = current_user_id());
CREATE POLICY solo_destinatario_marca ON notification AS RESTRICTIVE FOR UPDATE
    USING (user_id = current_user_id())
    WITH CHECK (user_id = current_user_id());

-- V3 concede DML completo por defecto. Un aviso no se borra desde la API (el borrado es selectivo y
-- deja constancia) y lo único que la persona cambia es la fecha de lectura: así una ruta con un bug no
-- puede reescribir el texto, el destinatario ni el workspace de un aviso.
REVOKE UPDATE, DELETE ON notification FROM contentplan_app;
GRANT UPDATE (read_at, updated_at) ON notification TO contentplan_app;
