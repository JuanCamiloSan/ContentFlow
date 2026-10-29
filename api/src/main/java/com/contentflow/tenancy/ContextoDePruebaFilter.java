package com.contentflow.tenancy;

import java.io.IOException;
import java.util.UUID;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Andamiaje del bloque B: toma el usuario y el workspace de cabeceras, sin autenticar a nadie.
 * Cualquiera que pueda mandar una cabecera elige su workspace, así que NO existe salvo que se active
 * {@code contentflow.contexto-de-prueba.habilitado=true}, y esa propiedad nunca va en
 * {@code application.yml}. Con la propiedad apagada, ninguna petición trae contexto y toda consulta a
 * una tabla con RLS falla. C4 lo reemplaza por el token y {@code membership}, y este archivo se borra.
 */
@Component
@ConditionalOnProperty("contentflow.contexto-de-prueba.habilitado")
class ContextoDePruebaFilter extends OncePerRequestFilter {

	static final String USUARIO = "X-Test-User-Id";
	static final String WORKSPACE = "X-Workspace-Id";

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {
		UUID usuario;
		UUID workspace;
		try {
			usuario = uuidODefecto(request.getHeader(USUARIO));
			workspace = uuidODefecto(request.getHeader(WORKSPACE));
		}
		catch (IllegalArgumentException ex) {
			// Sin eco del valor recibido: no se registra ni se devuelve lo que mandó el cliente
			response.sendError(HttpServletResponse.SC_BAD_REQUEST, "Cabecera de contexto inválida");
			return;
		}
		ContextoDeSesionActual.fijar(new ContextoDeSesion(usuario, workspace));
		try {
			chain.doFilter(request, response);
		}
		finally {
			ContextoDeSesionActual.limpiar();
		}
	}

	private static UUID uuidODefecto(String valor) {
		return valor == null || valor.isBlank() ? null : UUID.fromString(valor.trim());
	}

}
