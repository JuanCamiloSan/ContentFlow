package com.contentflow.openapi;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

import com.contentflow.PostgresIntegrationTest;

class OpenApiIntegrationTest extends PostgresIntegrationTest {

	@LocalServerPort
	private int port;

	@Test
	void ladefinicionOpenApiDescribeLosEndpointsExpuestos() {
		ResponseEntity<String> respuesta = cliente().get().uri("/v3/api-docs").retrieve().toEntity(String.class);

		assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(respuesta.getBody()).contains("\"openapi\"").contains("\"/health\"");
	}

	@Test
	void swaggerUiEstaDisponible() {
		ResponseEntity<String> respuesta = cliente().get().uri("/swagger-ui/index.html").retrieve()
				.toEntity(String.class);

		assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(respuesta.getBody()).containsIgnoringCase("swagger");
	}

	private RestClient cliente() {
		return RestClient.create("http://localhost:" + port);
	}

}
