package com.contentflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.contentflow.tenancy.ContextoDeSesion;
import com.contentflow.tenancy.ContextoDeSesionActual;

class WorkspaceTablaTest extends PostgresIntegrationTest {

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private PlatformTransactionManager transacciones;

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
	void unWorkspaceNuevoRecibeFechasPorDefecto() {
		UUID id = UUID.randomUUID();

		Boolean conFechas = enWorkspace(id, () -> {
			jdbc.update("INSERT INTO workspace (id, name) VALUES (?, 'Agencia de prueba')", id);
			return jdbc.queryForObject(
					"SELECT created_at IS NOT NULL AND updated_at IS NOT NULL FROM workspace WHERE id = ?",
					Boolean.class, id);
		});

		assertThat(conFechas).isTrue();
	}

	@Test
	void sinIdExplicitoElIdPorDefectoLoGeneraLaBaseYLaPoliticaRechazaLaFila() {
		// Consecuencia de B2: el id de un workspace nuevo debe conocerse antes del INSERT
		UUID contexto = UUID.randomUUID();

		assertThatThrownBy(() -> enWorkspace(contexto,
				() -> jdbc.update("INSERT INTO workspace (name) VALUES ('Sin id')")))
				.rootCause().hasMessageContaining("row-level security");
	}

	@Test
	void rechazaNombresVaciosONulos() {
		UUID id = UUID.randomUUID();

		assertThatThrownBy(() -> enWorkspace(id,
				() -> jdbc.update("INSERT INTO workspace (id, name) VALUES (?, '   ')", id)))
				.isInstanceOf(DataIntegrityViolationException.class);
		assertThatThrownBy(() -> enWorkspace(id,
				() -> jdbc.update("INSERT INTO workspace (id, name) VALUES (?, NULL)", id)))
				.isInstanceOf(DataIntegrityViolationException.class);
	}

	/** Abre una transacción con el workspace fijado por el mismo mecanismo que usa la API (B3). */
	private <T> T enWorkspace(UUID workspace, Supplier<T> accion) {
		ContextoDeSesionActual.fijar(new ContextoDeSesion(null, workspace));
		try {
			return new TransactionTemplate(transacciones).execute(estado -> accion.get());
		}
		finally {
			ContextoDeSesionActual.limpiar();
		}
	}

}
