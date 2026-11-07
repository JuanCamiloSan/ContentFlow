package com.contentplan.tenancy;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * El endpoint lo puede llamar cualquiera de los cuatro roles, con un workspace activo. Es una decisión
 * explícita y no un valor por defecto: ver {@link RequiereRol}. Qué filas ve cada rol dentro del
 * endpoint lo siguen decidiendo las políticas RLS (por ejemplo, un rol de cliente solo ve su cliente).
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ ElementType.METHOD, ElementType.TYPE })
public @interface CualquierMiembro {

}
