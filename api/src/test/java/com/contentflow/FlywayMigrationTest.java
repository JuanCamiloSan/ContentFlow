package com.contentflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

class FlywayMigrationTest extends PostgresIntegrationTest {

	@Test
	void flywayAplicaLaMigracionBaseAlArrancar() {
		// El historial solo lo lee el dueño: el rol de aplicación no tiene acceso (ADR 0003)
		JdbcTemplate comoDueno = new JdbcTemplate(new DriverManagerDataSource(
				POSTGRES.getJdbcUrl(), RolesDePrueba.DUENO, RolesDePrueba.CLAVE_DUENO));

		List<Map<String, Object>> historial = comoDueno.queryForList(
				"SELECT version, success FROM flyway_schema_history WHERE version IS NOT NULL ORDER BY installed_rank");

		// No fija cuántas migraciones hay: crece con cada tabla nueva. Que el proyecto tenga
		// exactamente las esperadas lo comprueba MigracionesDesdeCeroTest.
		assertThat(historial).isNotEmpty()
				.allSatisfy(fila -> assertThat(fila).containsEntry("success", true));
	}

}
