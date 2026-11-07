package com.contentplan.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.Date;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

import com.contentplan.PostgresIntegrationTest;
import com.contentplan.tenancy.SinWorkspace;
import com.nimbusds.jwt.JWTClaimsSet;

/**
 * C2: la API rechaza toda petición sin un token de Google válido. Los tokens son simulados y pasan por el
 * mismo validador de contenido que producción; cada prueba rompe una sola afirmación.
 */
@Import({ TokensDePrueba.Configuracion.class, SeguridadTest.Sondas.class })
class SeguridadTest extends PostgresIntegrationTest {

	@LocalServerPort
	private int port;

	@Test
	void unTokenValidoPasaYLaApiConoceASuPropietario() {
		String respuesta = cliente().get().uri("/sonda/yo").header("Authorization", bearer(TokensDePrueba.valido("persona-1")))
				.retrieve().body(String.class);

		assertThat(respuesta).isEqualTo("persona-1");
	}

	@Test
	void sinTokenSeRechazaConElError401() {
		assertThat(estado(null)).isEqualTo(HttpStatus.UNAUTHORIZED);
	}

	@Test
	void unaRutaQueNoExisteTambienExigeToken() {
		// Sin token no se distingue una ruta real de una inventada: no hay 404 que delate nada
		HttpStatus estado = (HttpStatus) cliente().get().uri("/no-existe").exchange((req, res) -> res.getStatusCode());

		assertThat(estado).isEqualTo(HttpStatus.UNAUTHORIZED);
	}

	@Test
	void laSaludYLaDocumentacionNoPidenToken() {
		HttpStatus salud = (HttpStatus) cliente().get().uri("/health").exchange((req, res) -> res.getStatusCode());
		HttpStatus docs = (HttpStatus) cliente().get().uri("/v3/api-docs").exchange((req, res) -> res.getStatusCode());

		assertThat(salud).isEqualTo(HttpStatus.OK);
		assertThat(docs).isEqualTo(HttpStatus.OK);
	}

	@Test
	void unTokenParaOtraAplicacionSeRechaza() {
		JWTClaimsSet claims = TokensDePrueba.claims("persona-1").audience("otra-aplicacion.apps.googleusercontent.com").build();

		assertThat(estado(TokensDePrueba.firmadoConLlaveValida(claims))).isEqualTo(HttpStatus.UNAUTHORIZED);
	}

	@Test
	void unTokenSinAudienceSeRechaza() {
		JWTClaimsSet claims = new JWTClaimsSet.Builder().issuer(TokensDePrueba.EMISOR).subject("persona-1")
				.expirationTime(Date.from(Instant.now().plusSeconds(3600))).build();

		assertThat(estado(TokensDePrueba.firmadoConLlaveValida(claims))).isEqualTo(HttpStatus.UNAUTHORIZED);
	}

	@Test
	void unTokenDeOtroEmisorSeRechaza() {
		JWTClaimsSet claims = TokensDePrueba.claims("persona-1").issuer("https://emisor-falso.example").build();

		assertThat(estado(TokensDePrueba.firmadoConLlaveValida(claims))).isEqualTo(HttpStatus.UNAUTHORIZED);
	}

	@Test
	void googleEmiteTambienElEmisorSinEsquemaYSeAcepta() {
		JWTClaimsSet claims = TokensDePrueba.claims("persona-1").issuer("accounts.google.com").build();

		assertThat(estado(TokensDePrueba.firmadoConLlaveValida(claims))).isEqualTo(HttpStatus.OK);
	}

	@Test
	void unTokenVencidoSeRechaza() {
		JWTClaimsSet claims = TokensDePrueba.claims("persona-1")
				.expirationTime(Date.from(Instant.now().minusSeconds(3600))).build();

		assertThat(estado(TokensDePrueba.firmadoConLlaveValida(claims))).isEqualTo(HttpStatus.UNAUTHORIZED);
	}

	@Test
	void unTokenFirmadoPorQuienNoEsGoogleSeRechaza() {
		JWTClaimsSet claims = TokensDePrueba.claims("persona-1").build();

		assertThat(estado(TokensDePrueba.firmadoConOtraLlave(claims))).isEqualTo(HttpStatus.UNAUTHORIZED);
	}

	@Test
	void unTokenSinFirmaSeRechaza() {
		assertThat(estado(TokensDePrueba.sinFirma(TokensDePrueba.claims("persona-1").build())))
				.isEqualTo(HttpStatus.UNAUTHORIZED);
	}

	@Test
	void unTokenMalformadoSeRechaza() {
		assertThat(estado("esto-no-es-un-jwt")).isEqualTo(HttpStatus.UNAUTHORIZED);
	}

	@Test
	void elDecodificadorDeProduccionSeCreaSinDependerDeLaRed() {
		// Nimbus descarga las claves al primer uso: crear el bean no debe depender de que Google responda
		assertThat(new SeguridadConfig().jwtDecoder(TokensDePrueba.CLIENT_ID)).isNotNull();
		assertThat(SeguridadConfig.JWKS_DE_GOOGLE).isEqualTo("https://www.googleapis.com/oauth2/v3/certs");
	}

	private HttpStatus estado(String token) {
		RestClient.RequestHeadersSpec<?> peticion = cliente().get().uri("/sonda/yo");
		if (token != null) {
			peticion = peticion.header("Authorization", bearer(token));
		}
		return (HttpStatus) peticion.exchange((req, res) -> res.getStatusCode());
	}

	private static String bearer(String token) {
		return "Bearer " + token;
	}

	private RestClient cliente() {
		return TokensDePrueba.clienteSinToken(port);
	}

	/** Un endpoint que solo existe en pruebas y devuelve a quién cree la API que le habla. */
	@TestConfiguration(proxyBeanMethods = false)
	static class Sondas {

		@RestController
		static class SondaController {

			// Esta prueba es de autenticación, no de tenant: la persona del token no tiene workspace
			@SinWorkspace
			@GetMapping("/sonda/yo")
			String yo(@AuthenticationPrincipal Jwt jwt) {
				return jwt.getSubject();
			}

		}

	}

}
