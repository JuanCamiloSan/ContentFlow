package com.contentplan.security;

import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.web.client.RestClient;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.PlainJWT;
import com.nimbusds.jwt.SignedJWT;

/**
 * Tokens JWT simulados (ADR 0004): firmados con una llave generada en memoria, nunca con Google real.
 * El decodificador de pruebas usa el mismo validador de contenido que producción, así que emisor,
 * audience y vigencia se prueban de verdad; solo cambia de dónde sale la llave pública.
 */
public final class TokensDePrueba {

	public static final String CLIENT_ID = "cliente-de-prueba.apps.googleusercontent.com";

	public static final String EMISOR = "https://accounts.google.com";

	private static final RSAKey LLAVE = nuevaLlave();

	private static final RSAKey OTRA_LLAVE = nuevaLlave();

	private TokensDePrueba() {
	}

	/** Un token válido para una persona nueva. */
	public static String valido() {
		return valido(UUID.randomUUID().toString());
	}

	public static String valido(String subject) {
		return firmar(LLAVE, claims(subject).build());
	}

	/** Las afirmaciones de un token válido, para que cada prueba rompa solo una. */
	public static JWTClaimsSet.Builder claims(String subject) {
		Instant ahora = Instant.now();
		return new JWTClaimsSet.Builder().issuer(EMISOR).audience(CLIENT_ID).subject(subject)
				.issueTime(Date.from(ahora)).expirationTime(Date.from(ahora.plusSeconds(3600)));
	}

	public static String firmadoConLlaveValida(JWTClaimsSet claims) {
		return firmar(LLAVE, claims);
	}

	/** Mismo contenido que uno válido, pero firmado por quien no es Google. */
	public static String firmadoConOtraLlave(JWTClaimsSet claims) {
		return firmar(OTRA_LLAVE, claims);
	}

	/** Un token sin firma ({@code alg: none}). */
	public static String sinFirma(JWTClaimsSet claims) {
		return new PlainJWT(claims).serialize();
	}

	/** Cliente HTTP que se presenta con un token válido en cada petición. */
	public static RestClient clienteAutenticado(int puerto) {
		return RestClient.builder().baseUrl("http://localhost:" + puerto)
				.defaultHeader("Authorization", "Bearer " + valido()).build();
	}

	public static RestClient clienteSinToken(int puerto) {
		return RestClient.create("http://localhost:" + puerto);
	}

	private static String firmar(RSAKey llave, JWTClaimsSet claims) {
		try {
			SignedJWT jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(llave.getKeyID()).build(),
					claims);
			jwt.sign(new RSASSASigner(llave));
			return jwt.serialize();
		}
		catch (JOSEException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static RSAKey nuevaLlave() {
		try {
			return new RSAKeyGenerator(2048).keyID(UUID.randomUUID().toString()).generate();
		}
		catch (JOSEException ex) {
			throw new IllegalStateException(ex);
		}
	}

	/** Sustituye el decodificador de Google por uno que confía en la llave de pruebas. */
	@TestConfiguration(proxyBeanMethods = false)
	public static class Configuracion {

		@Bean
		@Primary
		JwtDecoder jwtDecoderDePruebas() throws JOSEException {
			NimbusJwtDecoder decodificador = NimbusJwtDecoder.withPublicKey(LLAVE.toRSAPublicKey()).build();
			decodificador.setJwtValidator(SeguridadConfig.validador(CLIENT_ID));
			return decodificador;
		}

	}

}
