package com.contentflow.tenancy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import jakarta.servlet.http.HttpServlet;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

class ContextoDePruebaFilterTest {

	private final ContextoDePruebaFilter filtro = new ContextoDePruebaFilter();

	@Test
	void dejaElContextoDeLasCabecerasDuranteLaPeticionYLoLimpiaAlTerminar() throws Exception {
		UUID usuario = UUID.randomUUID();
		UUID workspace = UUID.randomUUID();
		MockHttpServletRequest peticion = new MockHttpServletRequest("GET", "/x");
		peticion.addHeader("X-Test-User-Id", usuario.toString());
		peticion.addHeader("X-Workspace-Id", workspace.toString());
		AtomicReference<ContextoDeSesion> visto = new AtomicReference<>();

		filtro.doFilter(peticion, new MockHttpServletResponse(), cadenaQueLee(visto));

		assertThat(visto.get()).isEqualTo(new ContextoDeSesion(usuario, workspace));
		assertThat(ContextoDeSesionActual.actual()).isEmpty();
	}

	@Test
	void limpiaElContextoAunqueLaPeticionFalle() {
		MockHttpServletRequest peticion = new MockHttpServletRequest("GET", "/x");
		peticion.addHeader("X-Workspace-Id", UUID.randomUUID().toString());

		try {
			filtro.doFilter(peticion, new MockHttpServletResponse(), new MockFilterChain(new HttpServlet() {
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
	void sinCabecerasNoFijaNingunValor() throws Exception {
		AtomicReference<ContextoDeSesion> visto = new AtomicReference<>();

		filtro.doFilter(new MockHttpServletRequest("GET", "/x"), new MockHttpServletResponse(),
				cadenaQueLee(visto));

		assertThat(visto.get()).isEqualTo(new ContextoDeSesion(null, null));
	}

	@Test
	void rechazaUnaCabeceraInvalidaSinLlegarAlControlador() throws Exception {
		MockHttpServletRequest peticion = new MockHttpServletRequest("GET", "/x");
		peticion.addHeader("X-Workspace-Id", "no-es-uuid");
		MockHttpServletResponse respuesta = new MockHttpServletResponse();
		AtomicReference<ContextoDeSesion> visto = new AtomicReference<>();

		filtro.doFilter(peticion, respuesta, cadenaQueLee(visto));

		assertThat(respuesta.getStatus()).isEqualTo(400);
		assertThat(visto.get()).isNull();
		assertThat(ContextoDeSesionActual.actual()).isEmpty();
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
