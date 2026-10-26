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

		assertThat(historial).hasSize(1);
		assertThat(historial.get(0)).containsEntry("version", "1").containsEntry("success", true);
	}

}
