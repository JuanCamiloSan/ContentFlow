package com.contentplan.security;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;

/**
 * La API es un resource server: toda petición lleva el token de identidad de Google y el backend lo
 * valida solo (ADR 0004). Este paso autentica; de dónde sale el contexto de tenant lo decide C4.
 */
@Configuration(proxyBeanMethods = false)
class SeguridadConfig {

	/** Claves públicas con las que Google firma sus tokens. Nimbus las descarga al primer uso, no al arrancar. */
	static final String JWKS_DE_GOOGLE = "https://www.googleapis.com/oauth2/v3/certs";

	/** Google documenta que el emisor puede venir con o sin esquema. */
	private static final List<String> EMISORES_DE_GOOGLE = List.of("https://accounts.google.com",
			"accounts.google.com");

	@Bean
	SecurityFilterChain cadenaDeSeguridad(HttpSecurity http) throws Exception {
		http
				// API sin sesión ni cookies: el token viaja en la cabecera Authorization, no hay nada
				// que un sitio ajeno pueda hacer valer por el navegador
				.csrf(csrf -> csrf.disable())
				.sessionManagement(sesion -> sesion.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				.authorizeHttpRequests(rutas -> rutas
						.requestMatchers("/health", "/v3/api-docs/**", "/swagger-ui/**", "/swagger-ui.html")
						.permitAll()
						// Lo que no se nombra aquí queda cerrado
						.anyRequest().authenticated())
				.oauth2ResourceServer(servidor -> servidor.jwt(Customizer.withDefaults()));
		return http.build();
	}

	@Bean
	JwtDecoder jwtDecoder(@Value("${contentplan.seguridad.google-client-id}") String clientId) {
		if (clientId == null || clientId.isBlank()) {
			// Sin audience esperado, cualquier token firmado por Google valdría
			throw new IllegalStateException("contentplan.seguridad.google-client-id no puede estar vacío");
		}
		NimbusJwtDecoder decodificador = NimbusJwtDecoder.withJwkSetUri(JWKS_DE_GOOGLE).build();
		decodificador.setJwtValidator(validador(clientId));
		return decodificador;
	}

	/**
	 * Lo que se exige al contenido del token, además de su firma (que comprueba el decodificador):
	 * vigencia, emisor de Google y audience igual al ID de cliente.
	 */
	static OAuth2TokenValidator<Jwt> validador(String clientId) {
		OAuth2TokenValidator<Jwt> emisor = jwt -> EMISORES_DE_GOOGLE.contains(jwt.getClaimAsString("iss"))
				? OAuth2TokenValidatorResult.success()
				: fallo("Emisor no permitido");
		OAuth2TokenValidator<Jwt> audience = jwt -> jwt.getAudience() != null
						&& jwt.getAudience().contains(clientId)
				? OAuth2TokenValidatorResult.success()
				: fallo("Audience no permitido");
		return new DelegatingOAuth2TokenValidator<>(new JwtTimestampValidator(), emisor, audience);
	}

	private static OAuth2TokenValidatorResult fallo(String descripcion) {
		return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", descripcion, null));
	}

}
