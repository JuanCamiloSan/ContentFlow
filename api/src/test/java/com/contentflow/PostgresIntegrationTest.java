package com.contentflow;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Base de las pruebas de integración: arranca la aplicación completa contra un PostgreSQL real.
 * Las políticas de RLS solo se pueden probar así, no con una base en memoria.
 * El contenedor es único para toda la ejecución; Testcontainers lo elimina al terminar.
 * Los {@code DataSource} de la aplicación usan el rol de aplicación, no el superusuario.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
public abstract class PostgresIntegrationTest {

	static final PostgreSQLContainer POSTGRES = RolesDePrueba.nuevaBase();

	static {
		POSTGRES.start();
	}

	@DynamicPropertySource
	static void datasource(DynamicPropertyRegistry registry) {
		registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
		// Como en producción: la API con el rol de aplicación, Flyway con el dueño. Con el
		// superusuario del contenedor las pruebas de aislamiento no probarían nada (ADR 0003).
		registry.add("spring.datasource.username", () -> RolesDePrueba.APLICACION);
		registry.add("spring.datasource.password", () -> RolesDePrueba.CLAVE_APLICACION);
		registry.add("spring.flyway.user", () -> RolesDePrueba.DUENO);
		registry.add("spring.flyway.password", () -> RolesDePrueba.CLAVE_DUENO);
	}

}
