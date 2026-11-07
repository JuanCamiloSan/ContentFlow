package com.contentplan.tenancy;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Los roles que pueden llamar a un endpoint, comparados con el rol de la persona en el workspace activo
 * (el de su {@code membership}, nunca uno que mande el cliente). Una anotación en el método manda sobre
 * la de la clase.
 *
 * Un endpoint sin {@code RequiereRol}, {@link CualquierMiembro} ni {@link SinWorkspace} queda cerrado
 * para todos, también para el propietario: olvidar la anotación falla al probarlo, no en producción.
 * Esto decide qué acciones puede hacer cada rol; qué datos ve lo impone la base con RLS.
 */
@Documented
@Retention(RetentionPolicy.RUNTIME)
@Target({ ElementType.METHOD, ElementType.TYPE })
public @interface RequiereRol {

	Rol[] value();

}
