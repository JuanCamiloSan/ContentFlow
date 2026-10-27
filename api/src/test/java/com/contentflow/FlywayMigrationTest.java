package com.contentflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

class FlywayMigrationTest extends PostgresIntegrationTest {

	@Autowired
	private JdbcTemplate jdbc;

	@Test
	void flywayAplicaLaMigracionBaseAlArrancar() {
		List<Map<String, Object>> historial = jdbc.queryForList(
				"SELECT version, success FROM flyway_schema_history WHERE version IS NOT NULL ORDER BY installed_rank");

		// No fija cuántas migraciones hay: crece con cada tabla nueva. Que el proyecto tenga
		// exactamente las esperadas lo comprueba MigracionesDesdeCeroTest.
		assertThat(historial).isNotEmpty()
				.allSatisfy(fila -> assertThat(fila).containsEntry("success", true));
	}

}
