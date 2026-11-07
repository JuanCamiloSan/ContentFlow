package com.contentplan.security;

import java.io.IOException;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.util.matcher.RequestMatcher;
import org.springframework.web.filter.OncePerRequestFilter;

import com.contentplan.tenancy.ContextoDeSesionActual;
import com.contentplan.tenancy.ContextoRechazadoException;
import com.contentplan.tenancy.ResolutorDeContexto;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * C4: lo que une la autenticación con el contexto de tenant. Corre justo después de que Spring Security
 * valida el token, saca de él la identidad (nunca de una cabecera que el cliente pueda inventar) y deja
 * el contexto en {@link ContextoDeSesionActual} para que {@code TransaccionesConContexto} lo fije en cada
 * transacción. Reemplaza al filtro de cabeceras de prueba del bloque B.
 *
 * El único dato que sí manda el cliente es {@code X-Workspace-Id}, y solo se acepta si la persona
 * pertenece a ese workspace ({@link ResolutorDeContexto}).
 *
 * Lo registra {@link SeguridadConfig} dentro de la cadena de seguridad y no es un bean: un filtro-bean
 * se registraría además en el contenedor y correría dos veces, y una de ellas antes de autenticar.
 */
class ContextoDeTenantFilter extends OncePerRequestFilter {

	static final String ENCABEZADO_WORKSPACE = "X-Workspace-Id";

	private final ResolutorDeContexto resolutor;

	private final RequestMatcher rutasPublicas;

	ContextoDeTenantFilter(ResolutorDeContexto resolutor, RequestMatcher rutasPublicas) {
		this.resolutor = resolutor;
		this.rutasPublicas = rutasPublicas;
	}

	@Override
	protected boolean shouldNotFilter(HttpServletRequest request) {
		// Las mismas rutas que SeguridadConfig deja abiertas: ahí una persona con token no necesita
		// contexto, y no debe recibir un 403 por no tener workspace al pedir /health
		return rutasPublicas.matches(request);
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		Authentication autenticacion = SecurityContextHolder.getContext().getAuthentication();
		if (!(autenticacion instanceof JwtAuthenticationToken jwt)) {
			// Sin token válido: la autorización de Spring Security responde 401 más adelante
			chain.doFilter(request, response);
			return;
		}
		Jwt token = jwt.getToken();
		String correo = token.getClaimAsString("email");
		if (esVacio(token.getSubject()) || esVacio(correo) || !correoVerificado(token)) {
			// Mensaje fijo: sin eco del correo ni del sub, que son datos personales (ver Logs en CLAUDE.md)
			response.sendError(HttpServletResponse.SC_FORBIDDEN, "Se requiere un correo verificado");
			return;
		}
		try {
			ContextoDeSesionActual.fijar(
					resolutor.resolver(token.getSubject(), correo, request.getHeader(ENCABEZADO_WORKSPACE)));
		}
		catch (ContextoRechazadoException ex) {
			response.sendError(ex.estado(), ex.getMessage());
			return;
		}
		try {
			chain.doFilter(request, response);
		}
		finally {
			// Los hilos del servidor se reutilizan: un contexto olvidado pasaría a la petición siguiente
			ContextoDeSesionActual.limpiar();
		}
	}

	/** Google manda un booleano; se acepta también el texto "true" y nada más. */
	private static boolean correoVerificado(Jwt token) {
		Object valor = token.getClaim("email_verified");
		return Boolean.TRUE.equals(valor) || "true".equals(valor);
	}

	private static boolean esVacio(String texto) {
		return texto == null || texto.isBlank();
	}

}
