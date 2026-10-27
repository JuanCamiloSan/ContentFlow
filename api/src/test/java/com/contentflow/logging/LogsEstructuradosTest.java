package com.contentflow.logging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.web.client.RestClient;

import com.contentflow.PostgresIntegrationTest;

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
		peticion("id-de-prueba-1", "Bearer no-importa", "x=1");

		assertThat(salida.getOut()).contains("\"correlationId\":\"id-de-prueba-1\"")
				.contains("\"message\":\"Petición atendida method=GET status=200");
	}

	@Test
	void nuncaRegistraTokensNiQueryStrings(CapturedOutput salida) {
		peticion("id-de-prueba-2", "Bearer token-secreto-abc", "api_key=clave-secreta-xyz");

		assertThat(salida.getOut()).contains("id-de-prueba-2")
				.doesNotContain("token-secreto-abc")
				.doesNotContain("clave-secreta-xyz")
				.doesNotContain("Authorization");
	}

	private void peticion(String correlationId, String authorization, String query) {
		RestClient.create("http://localhost:" + port)
				.get()
				.uri("/health?" + query)
				.header("X-Correlation-Id", correlationId)
				.header("Authorization", authorization)
				.retrieve()
				.toBodilessEntity();
	}

}
