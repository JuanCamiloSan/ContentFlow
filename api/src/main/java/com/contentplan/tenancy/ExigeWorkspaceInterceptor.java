package com.contentplan.tenancy;

import java.util.EnumSet;
import java.util.Optional;
import java.util.Set;

import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Lo no declarado queda cerrado, igual que en la cadena de seguridad: un endpoint nuevo no se abre por
 * olvido. Con una persona autenticada:
 * <ul>
 * <li>{@link SinWorkspace}: pasa, tenga o no workspace (crear el suyo o aceptar una invitación).</li>
 * <li>Sin workspace: nada más.</li>
 * <li>Con workspace: el endpoint debe declarar {@link RequiereRol} con su rol o {@link CualquierMiembro}.
 * Sin ninguna de las dos, 403 para todos (C5).</li>
 * </ul>
 * Las rutas públicas y las peticiones sin token no llevan contexto y no se tocan aquí.
 */
public class ExigeWorkspaceInterceptor implements HandlerInterceptor {

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
			throws Exception {
		Optional<ContextoDeSesion> contexto = ContextoDeSesionActual.actual();
		if (contexto.isEmpty() || tiene(handler, SinWorkspace.class)) {
			return true;
		}
		if (contexto.get().workspaceId() == null) {
			response.sendError(HttpServletResponse.SC_FORBIDDEN, "Primero crea un workspace o acepta una invitación");
			return false;
		}
		Rol rol = contexto.get().rol();
		if (rol == null || !rolesPermitidos(handler).contains(rol)) {
			// Mensaje fijo: no dice qué roles sí podrían ni si el endpoint existe
			response.sendError(HttpServletResponse.SC_FORBIDDEN, "Tu rol no permite esta acción");
			return false;
		}
		return true;
	}

	/** Vacío si el endpoint no declaró nada: queda cerrado. El método manda sobre la clase. */
	private static Set<Rol> rolesPermitidos(Object handler) {
		if (!(handler instanceof HandlerMethod metodo)) {
			return Set.of();
		}
		Set<Rol> delMetodo = declarados(metodo.getMethod());
		return delMetodo.isEmpty() ? declarados(metodo.getBeanType()) : delMetodo;
	}

	private static Set<Rol> declarados(java.lang.reflect.AnnotatedElement elemento) {
		RequiereRol requiere = AnnotatedElementUtils.findMergedAnnotation(elemento, RequiereRol.class);
		if (requiere != null) {
			Set<Rol> roles = EnumSet.noneOf(Rol.class);
			roles.addAll(java.util.Arrays.asList(requiere.value()));
			return roles;
		}
		if (AnnotatedElementUtils.hasAnnotation(elemento, CualquierMiembro.class)) {
			return EnumSet.allOf(Rol.class);
		}
		return Set.of();
	}

	private static boolean tiene(Object handler, Class<? extends java.lang.annotation.Annotation> anotacion) {
		return handler instanceof HandlerMethod metodo
				&& (AnnotatedElementUtils.hasAnnotation(metodo.getMethod(), anotacion)
						|| AnnotatedElementUtils.hasAnnotation(metodo.getBeanType(), anotacion));
	}

}
