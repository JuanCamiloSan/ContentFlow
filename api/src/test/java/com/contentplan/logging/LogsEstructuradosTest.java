package com.contentplan.logging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.client.RestClient;

import com.contentplan.PostgresIntegrationTest;
import com.contentplan.security.TokensDePrueba;

/**
 * Con la aplicación completa: los logs salen como JSON, llevan el identificador de correlación
 * y no contienen tokens ni query strings de la petición.
 */
@ExtendWith(OutputCaptureExtension.class)
class LogsEstructuradosTest extends PostgresIntegrationTest {

	@LocalServerPort
	private int port;

	@Test
	void laLineaDeLogEsJsonConElIdentificadorDeCorrelacion(CapturedOutput salida) {
		peticion("id-de-prueba-1", "Bearer " + TokensDePrueba.valido(), "x=1");

		assertThat(salida.getOut()).contains("\"correlationId\":\"id-de-prueba-1\"")
				.contains("\"message\":\"Petición atendida method=GET status=200");
	}

	@Test
	void nuncaRegistraTokensNiQueryStrings(CapturedOutput salida) {
		String valido = TokensDePrueba.valido();
		peticion("id-de-prueba-2", "Bearer " + valido, "api_key=clave-secreta-xyz");

		assertThat(salida.getOut()).contains("id-de-prueba-2")
				.doesNotContain(valido)
				.doesNotContain("clave-secreta-xyz")
				.doesNotContain("Authorization");
	}

	@Test
	void unTokenRechazadoTampocoSeRegistraYLaPeticionLlevaSuCorrelacion(CapturedOutput salida) {
		// Con token inválido la API responde 401 incluso en rutas públicas: es el camino que más
		// tentaría a registrar el valor recibido
		peticion("id-de-prueba-3", "Bearer token-secreto-abc", "x=1");

		assertThat(salida.getOut()).contains("\"correlationId\":\"id-de-prueba-3\"")
				.contains("status=401")
				.doesNotContain("token-secreto-abc");
	}

	private void peticion(String correlationId, String authorization, String query) {
		// El estado no importa aquí: se inspecciona lo que quedó en el log
		RestClient.create("http://localhost:" + port)
				.get()
				.uri("/health?" + query)
				.header("X-Correlation-Id", correlationId)
				.header("Authorization", authorization)
				.exchange((req, res) -> res.getStatusCode());
	}

}
