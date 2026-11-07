-- Resolver a la persona del token (C4, ADR 0003 y 0004).
--
-- El token de Google trae el "sub", que es texto; app.user_id es el uuid de app_user. La aplicación
-- no puede escribir en app_user ni leerla sin app.user_id, así que esta función SECURITY DEFINER es
-- la única puerta para traducir una identidad y, la primera vez, crear a la persona (registro abierto,
-- ADR 0006). Corre como dueño de la tabla, que no tiene FORCE, y por eso ve y escribe sin contexto.
--
-- Solo devuelve el uuid de la persona que se presentó con ese sub: no permite buscar a nadie por
-- correo ni leer sus datos. El sub llega de un token ya validado (firma, emisor y audience) en
-- SeguridadConfig; esta función no vuelve a verificarlo.
--
-- search_path fijo: sin él, un objeto con el mismo nombre en otro esquema se ejecutaría con los
-- privilegios del dueño. pg_temp va al final para que ninguna tabla temporal tape a las reales.
CREATE FUNCTION resolver_usuario(p_google_sub text, p_email text) RETURNS uuid
LANGUAGE plpgsql SECURITY DEFINER
SET search_path = pg_catalog, public, pg_temp AS $$
DECLARE
    v_sub   text := btrim(p_google_sub);
    v_email text := lower(btrim(p_email));
    v_id    uuid;
BEGIN
    IF v_sub IS NULL OR v_sub = '' OR v_email IS NULL OR v_email = '' THEN
        RAISE EXCEPTION 'Identidad incompleta' USING ERRCODE = 'invalid_parameter_value';
    END IF;

    SELECT id INTO v_id FROM public.app_user WHERE google_sub = v_sub;
    IF v_id IS NOT NULL THEN
        RETURN v_id;
    END IF;

    -- Dos primeras peticiones simultáneas de la misma persona: una inserta y la otra pierde la
    -- carrera, la relee. Nunca quedan dos filas ni falla la petición.
    INSERT INTO public.app_user (google_sub, email) VALUES (v_sub, v_email)
    ON CONFLICT (google_sub) DO NOTHING
    RETURNING id INTO v_id;
    IF v_id IS NULL THEN
        SELECT id INTO v_id FROM public.app_user WHERE google_sub = v_sub;
    END IF;
    RETURN v_id;
END $$;

-- Por defecto PostgreSQL concede EXECUTE a todos los roles. Solo la aplicación la necesita.
REVOKE ALL ON FUNCTION resolver_usuario(text, text) FROM PUBLIC;
GRANT EXECUTE ON FUNCTION resolver_usuario(text, text) TO contentplan_app;
