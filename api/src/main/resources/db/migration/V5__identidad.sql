-- Identidad (C3): las personas y a qué workspace pertenecen. ADR 0003, 0004, 0005 y 0006.
--
-- app_user y membership son las dos excepciones al aislamiento por workspace_id: sus políticas se
-- apoyan en app.user_id, para poder descubrir a qué workspaces pertenece alguien antes de que haya
-- uno activo (ADR 0003, decisión 1).
--
-- La aplicación solo LEE estas tablas. Crear personas y pertenencias, y cambiar max_workspaces o la
-- marca de administrador, lo harán funciones SECURITY DEFINER (C4, C9, C14, C15). Así ninguna ruta
-- de la API puede darse privilegios a sí misma, ni aunque el código falle.

-- Análoga a current_workspace(): sin contexto falla en lugar de devolver cero filas.
CREATE FUNCTION current_user_id() RETURNS uuid
LANGUAGE plpgsql STABLE AS $$
DECLARE
    v uuid := NULLIF(current_setting('app.user_id', true), '')::uuid;
BEGIN
    IF v IS NULL THEN
        RAISE EXCEPTION 'No hay contexto de usuario' USING ERRCODE = 'insufficient_privilege';
    END IF;
    RETURN v;
END $$;

CREATE TABLE app_user (
    id                uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    -- El "sub" del token de Google: identificador estable de la persona (ADR 0004). El id propio
    -- existe porque app.user_id es un uuid y el sub de Google no lo es.
    google_sub        text        NOT NULL UNIQUE CHECK (btrim(google_sub) <> ''),
    -- Normalizado a minúsculas: la invitación se compara contra este valor (ADR 0003). No es único
    -- a propósito: la identidad es el sub, y una persona puede cambiar su correo en Google.
    email             text        NOT NULL CHECK (btrim(email) <> '' AND email = lower(email)),
    -- Cuántos workspaces puede crear. Lo amplía el administrador de la plataforma (ADR 0006)
    max_workspaces    integer     NOT NULL DEFAULT 1 CHECK (max_workspaces >= 0),
    -- Se asigna a mano con SQL, nunca por la API (ADR 0006)
    is_platform_admin boolean     NOT NULL DEFAULT false,
    created_at        timestamptz NOT NULL DEFAULT now(),
    updated_at        timestamptz NOT NULL DEFAULT now()
);

CREATE TABLE membership (
    id           uuid        PRIMARY KEY DEFAULT gen_random_uuid(),
    -- Sin ON DELETE CASCADE: el borrado es manual y selectivo (modelo-datos.md)
    workspace_id uuid        NOT NULL REFERENCES workspace (id),
    user_id      uuid        NOT NULL REFERENCES app_user (id),
    role         text        NOT NULL CHECK (role IN ('propietario', 'editor', 'aprobador', 'colaborador')),
    -- Para los roles de cliente, el cliente al que se limitan. Sin llave foránea todavía: la tabla
    -- client llega en D1, que la agrega. Mientras tanto la restricción de abajo vigila la coherencia.
    client_id    uuid,
    created_at   timestamptz NOT NULL DEFAULT now(),
    updated_at   timestamptz NOT NULL DEFAULT now(),
    -- Una pertenencia por persona y workspace. Aflojarlo después es barato; apretarlo, no
    CONSTRAINT membership_una_por_persona UNIQUE (workspace_id, user_id),
    -- Los roles de agencia ven todo el workspace; los de cliente, solo su cliente (ADR 0005)
    CONSTRAINT membership_rol_y_cliente CHECK (
        (role IN ('propietario', 'editor') AND client_id IS NULL)
        OR (role IN ('aprobador', 'colaborador') AND client_id IS NOT NULL))
);

CREATE INDEX membership_user_id_idx ON membership (user_id);
CREATE INDEX membership_workspace_id_idx ON membership (workspace_id);

-- Sin FORCE a propósito, como invitation (ADR 0003, decisión 2): las funciones SECURITY DEFINER
-- corren como dueño y con FORCE verían cero filas. La protección sigue intacta para la aplicación,
-- que no es dueña de las tablas y además no tiene permisos de escritura sobre ellas.
ALTER TABLE app_user ENABLE ROW LEVEL SECURITY;
ALTER TABLE membership ENABLE ROW LEVEL SECURITY;

-- Cada persona ve solo su fila. No existe búsqueda de personas por correo (ADR 0003)
CREATE POLICY propia_fila ON app_user FOR SELECT
    USING (id = current_user_id());

-- Lectura tolerante a propósito (ADR 0003, decisión 1): antes de que haya workspace activo, cero
-- filas es la respuesta correcta. Por eso no llama a current_workspace(), que lanzaría excepción, y
-- PostgreSQL no garantiza el orden de evaluación de un OR.
-- Ve las filas propias, o las del workspace activo para listar el equipo.
-- Solo SELECT: sin política de escritura, INSERT, UPDATE y DELETE quedan denegados.
CREATE POLICY pertenencia ON membership FOR SELECT
    USING (user_id = NULLIF(current_setting('app.user_id', true), '')::uuid
        OR workspace_id = NULLIF(current_setting('app.workspace_id', true), '')::uuid);

-- Doble cierre: V3 concede DML por defecto sobre toda tabla nueva. Se retira aquí.
REVOKE INSERT, UPDATE, DELETE ON app_user, membership FROM contentplan_app;

-- Segunda frontera (ADR 0003, decisión 7): las tablas con client_id llamarán a esta función desde
-- su política, y así un rol de cliente solo ve su cliente sin depender del código. Los roles de
-- agencia (client_id nulo) ven todos. El administrador de la plataforma no tiene membership, así que
-- se reconoce aquí, en un solo lugar y no en cada política (ADR 0006).
-- No es SECURITY DEFINER: lee membership y app_user bajo las políticas de arriba, que dejan ver las
-- filas propias. Ambas variables se exigen siempre, para que olvidar el contexto sea un error.
CREATE FUNCTION puede_ver_cliente(p_client_id uuid) RETURNS boolean
LANGUAGE plpgsql STABLE AS $$
DECLARE
    v_usuario   uuid := current_user_id();
    v_workspace uuid := current_workspace();
BEGIN
    RETURN EXISTS (SELECT 1 FROM membership m
                   WHERE m.user_id = v_usuario
                     AND m.workspace_id = v_workspace
                     AND (m.client_id IS NULL OR m.client_id = p_client_id))
        OR EXISTS (SELECT 1 FROM app_user u
                   WHERE u.id = v_usuario AND u.is_platform_admin);
END $$;
