package com.contentplan.tenancy;

import org.springframework.core.annotation.AnnotatedElementUtils;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Una persona autenticada sin workspace solo llega a los endpoints marcados con {@link SinWorkspace}.
 * Lo no marcado queda cerrado, igual que en la cadena de seguridad: un endpoint nuevo no se abre por
 * olvido. Las rutas públicas y las peticiones sin token no pasan por aquí con contexto.
 */
public class ExigeWorkspaceInterceptor implements HandlerInterceptor {

	@Override
	public boolean preHandle(HttpServletRequest request, HttpServletResponse response, Object handler)
			throws Exception {
		boolean sinWorkspace = ContextoDeSesionActual.actual().map(c -> c.workspaceId() == null).orElse(false);
		if (sinWorkspace && !permiteSinWorkspace(handler)) {
			response.sendError(HttpServletResponse.SC_FORBIDDEN, "Primero crea un workspace o acepta una invitación");
			return false;
		}
		return true;
	}

	private static boolean permiteSinWorkspace(Object handler) {
		return handler instanceof HandlerMethod metodo
				&& (AnnotatedElementUtils.hasAnnotation(metodo.getMethod(), SinWorkspace.class)
						|| AnnotatedElementUtils.hasAnnotation(metodo.getBeanType(), SinWorkspace.class));
	}

}
