package com.contentplan.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

import com.contentplan.tenancy.ContextoDeSesion;
import com.contentplan.tenancy.ContextoDeSesionActual;
import com.contentplan.tenancy.ContextoRechazadoException;
import com.contentplan.tenancy.ResolutorDeContexto;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/** C4: el filtro que une el token con el contexto, sin base de datos (el resolutor es un doble). */
class ContextoDeTenantFilterTest {

	private final ResolutorDeContexto resolutor = mock(ResolutorDeContexto.class);

	private final ContextoDeTenantFilter filtro = new ContextoDeTenantFilter(resolutor,
			PathPatternRequestMatcher.withDefaults().matcher("/health"));

	@AfterEach
	void limpiar() {
		SecurityContextHolder.clearContext();
		ContextoDeSesionActual.limpiar();
	}

	@Test
	void dejaElContextoResueltoDuranteLaPeticionYLoLimpiaAlTerminar() throws Exception {
		ContextoDeSesion contexto = new ContextoDeSesion(UUID.randomUUID(), UUID.randomUUID());
		when(resolutor.resolver(eq("sub-1"), eq("a@example.com"), any())).thenReturn(contexto);
		autenticarComo("sub-1", "a@example.com", true);
		AtomicReference<ContextoDeSesion> visto = new AtomicReference<>();

		filtro.doFilter(new MockHttpServletRequest("GET", "/x"), new MockHttpServletResponse(), cadenaQueLee(visto));

		assertThat(visto.get()).isEqualTo(contexto);
		assertThat(ContextoDeSesionActual.actual()).isEmpty();
	}

	@Test
	void limpiaElContextoAunqueLaPeticionFalle() {
		when(resolutor.resolver(any(), any(), any()))
				.thenReturn(new ContextoDeSesion(UUID.randomUUID(), UUID.randomUUID()));
		autenticarComo("sub-1", "a@example.com", true);

		try {
			filtro.doFilter(new MockHttpServletRequest("GET", "/x"), new MockHttpServletResponse(),
					new MockFilterChain(new HttpServlet() {
						@Override
						protected void service(HttpServletRequest req, HttpServletResponse res) {
							throw new IllegalStateException("falla a mitad de la petición");
						}
					}));
		}
		catch (Exception esperada) {
			// lo que importa es el estado del hilo
		}

		assertThat(ContextoDeSesionActual.actual()).isEmpty();
	}

	@Test
	void pasaElEncabezadoDeWorkspaceTalCualAlResolutor() throws Exception {
		UUID workspace = UUID.randomUUID();
		when(resolutor.resolver("sub-1", "a@example.com", workspace.toString()))
				.thenReturn(new ContextoDeSesion(UUID.randomUUID(), workspace));
		autenticarComo("sub-1", "a@example.com", true);
		MockHttpServletRequest peticion = new MockHttpServletRequest("GET", "/x");
		peticion.addHeader("X-Workspace-Id", workspace.toString());
		AtomicReference<ContextoDeSesion> visto = new AtomicReference<>();

		filtro.doFilter(peticion, new MockHttpServletResponse(), cadenaQueLee(visto));

		assertThat(visto.get().workspaceId()).isEqualTo(workspace);
	}

	@Test
	void unRechazoDelResolutorCortaLaPeticionConSuEstado() throws Exception {
		when(resolutor.resolver(any(), any(), any())).thenThrow(new ContextoRechazadoException(403, "no"));
		autenticarComo("sub-1", "a@example.com", true);
		MockHttpServletResponse respuesta = new MockHttpServletResponse();
		AtomicReference<ContextoDeSesion> visto = new AtomicReference<>();

		filtro.doFilter(new MockHttpServletRequest("GET", "/x"), respuesta, cadenaQueLee(visto));

		assertThat(respuesta.getStatus()).isEqualTo(403);
		assertThat(visto.get()).isNull();
		assertThat(ContextoDeSesionActual.actual()).isEmpty();
	}

	@Test
	void unCorreoSinVerificarNuncaLlegaAlResolutor() throws Exception {
		autenticarComo("sub-1", "a@example.com", false);
		MockHttpServletResponse respuesta = new MockHttpServletResponse();

		filtro.doFilter(new MockHttpServletRequest("GET", "/x"), respuesta, cadenaQueLee(new AtomicReference<>()));

		assertThat(respuesta.getStatus()).isEqualTo(403);
		verifyNoInteractions(resolutor);
	}

	@Test
	void sinAutenticacionNoHaceNadaYDejaQueSpringSecurityResponda() throws Exception {
		AtomicReference<ContextoDeSesion> visto = new AtomicReference<>();
		MockHttpServletResponse respuesta = new MockHttpServletResponse();

		filtro.doFilter(new MockHttpServletRequest("GET", "/x"), respuesta, cadenaQueLee(visto));

		assertThat(respuesta.getStatus()).isEqualTo(200);
		assertThat(visto.get()).isNull();
		verifyNoInteractions(resolutor);
	}

	@Test
	void enLasRutasPublicasNoResuelveContextoAunqueHayaToken() throws Exception {
		autenticarComo("sub-1", "a@example.com", true);

		filtro.doFilter(new MockHttpServletRequest("GET", "/health"), new MockHttpServletResponse(),
				cadenaQueLee(new AtomicReference<>()));

		verifyNoInteractions(resolutor);
	}

	private static void autenticarComo(String sub, String correo, boolean verificado) {
		Jwt token = Jwt.withTokenValue("t").header("alg", "none").subject(sub).claim("email", correo)
				.claim("email_verified", verificado).issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(60))
				.build();
		SecurityContextHolder.getContext().setAuthentication(new JwtAuthenticationToken(token));
	}

	private static MockFilterChain cadenaQueLee(AtomicReference<ContextoDeSesion> visto) {
		return new MockFilterChain(new HttpServlet() {
			@Override
			protected void service(HttpServletRequest req, HttpServletResponse res) {
				visto.set(ContextoDeSesionActual.actual().orElse(null));
			}
		});
	}

}
