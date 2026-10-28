package com.contentflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

class WorkspaceTablaTest extends PostgresIntegrationTest {

	@Autowired
	private JdbcTemplate jdbc;

	@Test
	void laTablaTieneLasColumnasComunesDelModelo() {
		List<Map<String, Object>> columnas = jdbc.queryForList(
				"SELECT column_name, data_type, is_nullable FROM information_schema.columns "
						+ "WHERE table_name = 'workspace' ORDER BY column_name");

		assertThat(columnas).extracting(c -> c.get("column_name"))
				.containsExactly("created_at", "id", "name", "updated_at");
		assertThat(columnas).filteredOn(c -> c.get("column_name").equals("id"))
				.singleElement().satisfies(c -> assertThat(c).containsEntry("data_type", "uuid"));
		assertThat(columnas).allSatisfy(c -> assertThat(c).containsEntry("is_nullable", "NO"));
	}

	@Test
	void unWorkspaceNuevoRecibeIdYFechasPorDefecto() {
		UUID id = jdbc.queryForObject(
				"INSERT INTO workspace (name) VALUES ('Agencia de prueba') RETURNING id", UUID.class);

		assertThat(id).isNotNull();
		assertThat(jdbc.queryForObject(
				"SELECT created_at IS NOT NULL AND updated_at IS NOT NULL FROM workspace WHERE id = ?",
				Boolean.class, id)).isTrue();
	}

	@Test
	void rechazaNombresVaciosONulos() {
		assertThatThrownBy(() -> jdbc.update("INSERT INTO workspace (name) VALUES ('   ')"))
				.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> jdbc.update("INSERT INTO workspace (name) VALUES (NULL)"))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

}
