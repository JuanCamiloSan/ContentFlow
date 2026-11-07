package com.contentplan.tenancy;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marca un endpoint al que puede llamar una persona autenticada que todavía no pertenece a ningún
 * workspace. Hoy solo corresponde a crear el workspace (C14) y aceptar una invitación (C9). Todo lo que
 * no lleve esta marca queda cerrado para ella: {@link ExigeWorkspaceInterceptor}.
 *
 * Un endpoint marcado no puede tocar tablas con RLS por workspace: no hay workspace activo, y la base
 * lo hace fallar. Lo que necesite hacer lo hace por una función {@code SECURITY DEFINER}.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ ElementType.METHOD, ElementType.TYPE })
public @interface SinWorkspace {

}
