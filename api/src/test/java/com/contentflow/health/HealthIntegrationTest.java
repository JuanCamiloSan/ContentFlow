package com.contentflow.health;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClient;

import com.contentflow.PostgresIntegrationTest;

class HealthIntegrationTest extends PostgresIntegrationTest {

	@LocalServerPort
	private int port;

	@Test
	void healthRespondePorHttpConLaAplicacionCompletaYPostgresReal() {
		ResponseEntity<Map<String, String>> respuesta = RestClient.create("http://localhost:" + port)
				.get()
				.uri("/health")
				.retrieve()
				.toEntity(new ParameterizedTypeReference<>() {
				});

		assertThat(respuesta.getStatusCode()).isEqualTo(HttpStatus.OK);
		assertThat(respuesta.getBody()).containsEntry("status", "UP");
	}

}
