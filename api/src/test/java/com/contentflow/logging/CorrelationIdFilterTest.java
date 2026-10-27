package com.contentflow.logging;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

class CorrelationIdFilterTest {

	private final CorrelationIdFilter filtro = new CorrelationIdFilter();

	@Test
	void generaUnIdentificadorSiLaPeticionNoTraeUno() throws Exception {
		MockHttpServletResponse respuesta = ejecutar(null).respuesta();

		assertThat(respuesta.getHeader("X-Correlation-Id")).isNotBlank();
	}

	@Test
	void reutilizaElIdentificadorValidoQueMandaElCliente() throws Exception {
		Resultado resultado = ejecutar("abc-123_XYZ");

		assertThat(resultado.respuesta().getHeader("X-Correlation-Id")).isEqualTo("abc-123_XYZ");
		assertThat(resultado.mdcDuranteLaPeticion().get()).isEqualTo("abc-123_XYZ");
	}

	@Test
	void descartaIdentificadoresConCaracteresPeligrososOMuyLargos() throws Exception {
		for (String malo : new String[] { "a\nb", "{\"level\":\"FATAL\"}", "x".repeat(65), "con espacio" }) {
			String devuelto = ejecutar(malo).respuesta().getHeader("X-Correlation-Id");

			assertThat(devuelto).isNotEqualTo(malo).matches("[0-9a-f-]{36}");
		}
	}

	@Test
	void limpiaElMdcAlTerminarParaNoFiltrarseALaSiguientePeticionDelHilo() throws Exception {
		ejecutar("abc-123");

		assertThat(MDC.get("correlationId")).isNull();
	}

	private Resultado ejecutar(String idRecibido) throws Exception {
		MockHttpServletRequest peticion = new MockHttpServletRequest("GET", "/health");
		if (idRecibido != null) {
			peticion.addHeader("X-Correlation-Id", idRecibido);
		}
		MockHttpServletResponse respuesta = new MockHttpServletResponse();
		AtomicReference<String> mdc = new AtomicReference<>();
		filtro.doFilter(peticion, respuesta,
				new MockFilterChain(new jakarta.servlet.http.HttpServlet() {
					@Override
					protected void service(jakarta.servlet.http.HttpServletRequest req,
							jakarta.servlet.http.HttpServletResponse res) {
						mdc.set(MDC.get("correlationId"));
					}
				}));
		return new Resultado(respuesta, mdc);
	}

	private record Resultado(MockHttpServletResponse respuesta, AtomicReference<String> mdcDuranteLaPeticion) {
	}

}
