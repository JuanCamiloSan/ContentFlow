package com.contentflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * El aislamiento entre workspaces (B4, B5, B6 y B11) sobre una tabla de negocio, con la plantilla
 * de {@code docs/plantilla-migracion-rls.sql} (B7) ejecutada tal cual. La tabla solo existe en esta
 * base de prueba, no en el esquema real. Todas las comprobaciones usan el rol de aplicación: con un
 * superusuario pasarían sin probar nada (ADR 0003).
 */
class TablaDeNegocioRlsTest {

	private static final String TABLA = "prueba_aislamiento";

	private static final Path PLANTILLA = Path.of("..", "docs", "plantilla-migracion-rls.sql")
			.toAbsolutePath().normalize();

	private static PostgreSQLContainer base;

	@BeforeAll
	static void migrarYCrearLaTablaConLaPlantilla() throws Exception {
		base = RolesDePrueba.nuevaBase();
		base.start();
		Flyway.configure()
				.dataSource(base.getJdbcUrl(), RolesDePrueba.DUENO, RolesDePrueba.CLAVE_DUENO)
				.locations("classpath:db/migration")
				.load()
				.migrate();
		String sql = Files.readString(PLANTILLA).replace("{{tabla}}", TABLA);
		try (Connection c = conectar(RolesDePrueba.DUENO, RolesDePrueba.CLAVE_DUENO);
				Statement s = c.createStatement()) {
			s.execute(sql);
		}
	}

	@AfterAll
	static void detenerBase() {
		base.stop();
	}

	@Test
	void laPlantillaDejaRlsActivaForzadaYUnaPoliticaConLecturaYEscritura() throws SQLException {
		try (Connection c = conectar(base.getUsername(), base.getPassword());
				PreparedStatement ps = c.prepareStatement(
						"SELECT c.relrowsecurity, c.relforcerowsecurity, p.qual, p.with_check "
								+ "FROM pg_class c JOIN pg_policies p ON p.tablename = c.relname "
								+ "WHERE c.relname = ?")) {
			ps.setString(1, TABLA);
			try (ResultSet rs = ps.executeQuery()) {
				assertThat(rs.next()).as("la tabla tiene una política").isTrue();
				assertThat(rs.getBoolean("relrowsecurity")).as("ENABLE").isTrue();
				assertThat(rs.getBoolean("relforcerowsecurity")).as("FORCE").isTrue();
				assertThat(rs.getString("qual")).as("USING").contains("current_workspace()");
				assertThat(rs.getString("with_check")).as("WITH CHECK").contains("current_workspace()");
				assertThat(rs.next()).as("una sola política").isFalse();
			}
		}
	}

	// B4
	@Test
	void cadaWorkspaceSoloVeSusPropiasFilas() throws SQLException {
		UUID a = crearWorkspace();
		UUID b = crearWorkspace();
		UUID filaA1 = crearFila(a);
		UUID filaA2 = crearFila(a);
		UUID filaB = crearFila(b);

		assertThat(idsVisiblesCon(a)).containsExactlyInAnyOrder(filaA1, filaA2).doesNotContain(filaB);
		assertThat(idsVisiblesCon(b)).containsExactly(filaB);
	}

	// B5
	@Test
	void noSePuedeInsertarUnaFilaConElWorkspaceAjeno() throws SQLException {
		UUID a = crearWorkspace();
		UUID b = crearWorkspace();

		try (Connection c = transaccionConContexto(a);
				PreparedStatement ps = c.prepareStatement(
						"INSERT INTO " + TABLA + " (workspace_id) VALUES (?)")) {
			ps.setObject(1, b);
			assertThatThrownBy(ps::executeUpdate).isInstanceOf(SQLException.class)
					.hasMessageContaining("row-level security");
			c.rollback();
		}
		assertThat(idsVisiblesCon(b)).isEmpty();
	}

	// B5: sin WITH CHECK también se podría "regalar" una fila propia al workspace ajeno
	@Test
	void noSePuedeMoverUnaFilaPropiaAOtroWorkspace() throws SQLException {
		UUID a = crearWorkspace();
		UUID b = crearWorkspace();
		UUID fila = crearFila(a);

		try (Connection c = transaccionConContexto(a);
				PreparedStatement ps = c.prepareStatement(
						"UPDATE " + TABLA + " SET workspace_id = ? WHERE id = ?")) {
			ps.setObject(1, b);
			ps.setObject(2, fila);
			assertThatThrownBy(ps::executeUpdate).isInstanceOf(SQLException.class)
					.hasMessageContaining("row-level security");
			c.rollback();
		}
		assertThat(idsVisiblesCon(a)).containsExactly(fila);
	}

	// B6
	@Test
	void unaFilaAjenaNoApareceNiSiSeConoceSuIdExacto() throws SQLException {
		UUID a = crearWorkspace();
		UUID b = crearWorkspace();
		UUID filaDeB = crearFila(b);

		try (Connection c = transaccionConContexto(a)) {
			assertThat(afectadas(c, "SELECT id FROM " + TABLA + " WHERE id = ?", filaDeB)).isZero();
			assertThat(afectadas(c, "UPDATE " + TABLA + " SET updated_at = now() WHERE id = ?", filaDeB)).isZero();
			assertThat(afectadas(c, "DELETE FROM " + TABLA + " WHERE id = ?", filaDeB)).isZero();
			c.rollback();
		}
		assertThat(idsVisiblesCon(b)).containsExactly(filaDeB);
	}

	// B11
	@Test
	void sinContextoLaConsultaFallaEnLugarDeDevolverCeroFilas() throws SQLException {
		try (Connection c = conectar(RolesDePrueba.APLICACION, RolesDePrueba.CLAVE_APLICACION);
				PreparedStatement ps = c.prepareStatement("SELECT id FROM " + TABLA)) {
			assertThatThrownBy(ps::executeQuery).isInstanceOf(SQLException.class)
					.hasMessageContaining("No hay contexto de workspace");
		}
	}

	// B11
	@Test
	void unaCadenaVaciaCuentaComoSinContextoTambienAlEscribir() throws SQLException {
		try (Connection c = conectar(RolesDePrueba.APLICACION, RolesDePrueba.CLAVE_APLICACION)) {
			c.setAutoCommit(false);
			fijarWorkspace(c, "");
			try (PreparedStatement ps = c.prepareStatement("SELECT id FROM " + TABLA)) {
				assertThatThrownBy(ps::executeQuery).isInstanceOf(SQLException.class)
						.hasMessageContaining("No hay contexto de workspace");
			}
			c.rollback();
			c.setAutoCommit(false);
			fijarWorkspace(c, "");
			try (PreparedStatement ps = c.prepareStatement(
					"INSERT INTO " + TABLA + " (workspace_id) VALUES (?)")) {
				ps.setObject(1, UUID.randomUUID());
				assertThatThrownBy(ps::executeUpdate).isInstanceOf(SQLException.class);
			}
			c.rollback();
		}
	}

	@Test
	void laPoliticaTambienAplicaAlDuenoDeLaTabla() throws SQLException {
		try (Connection c = conectar(RolesDePrueba.DUENO, RolesDePrueba.CLAVE_DUENO);
				PreparedStatement ps = c.prepareStatement("SELECT id FROM " + TABLA)) {
			assertThatThrownBy(ps::executeQuery).isInstanceOf(SQLException.class)
					.hasMessageContaining("No hay contexto de workspace");
		}
	}

	/** Un workspace nuevo se crea bajo su propio contexto: su política exige id = workspace activo. */
	private static UUID crearWorkspace() throws SQLException {
		UUID id = UUID.randomUUID();
		try (Connection c = transaccionConContexto(id);
				PreparedStatement ps = c.prepareStatement("INSERT INTO workspace (id, name) VALUES (?, 'Agencia')")) {
			ps.setObject(1, id);
			ps.executeUpdate();
			c.commit();
		}
		return id;
	}

	private static UUID crearFila(UUID workspace) throws SQLException {
		UUID id = UUID.randomUUID();
		try (Connection c = transaccionConContexto(workspace);
				PreparedStatement ps = c.prepareStatement(
						"INSERT INTO " + TABLA + " (id, workspace_id) VALUES (?, ?)")) {
			ps.setObject(1, id);
			ps.setObject(2, workspace);
			assertThat(ps.executeUpdate()).isEqualTo(1);
			c.commit();
		}
		return id;
	}

	private static List<UUID> idsVisiblesCon(UUID workspace) throws SQLException {
		List<UUID> ids = new ArrayList<>();
		try (Connection c = transaccionConContexto(workspace);
				PreparedStatement ps = c.prepareStatement("SELECT id FROM " + TABLA);
				ResultSet rs = ps.executeQuery()) {
			while (rs.next()) {
				ids.add(rs.getObject("id", UUID.class));
			}
			c.rollback();
		}
		return ids;
	}

	/** Filas que devuelve (SELECT) o modifica (UPDATE, DELETE) la sentencia con ese id. */
	private static int afectadas(Connection c, String sql, UUID id) throws SQLException {
		try (PreparedStatement ps = c.prepareStatement(sql)) {
			ps.setObject(1, id);
			if (sql.startsWith("SELECT")) {
				try (ResultSet rs = ps.executeQuery()) {
					int filas = 0;
					while (rs.next()) {
						filas++;
					}
					return filas;
				}
			}
			return ps.executeUpdate();
		}
	}

	private static Connection transaccionConContexto(UUID workspace) throws SQLException {
		Connection c = conectar(RolesDePrueba.APLICACION, RolesDePrueba.CLAVE_APLICACION);
		c.setAutoCommit(false);
		fijarWorkspace(c, workspace.toString());
		return c;
	}

	private static void fijarWorkspace(Connection c, String valor) throws SQLException {
		try (PreparedStatement ps = c.prepareStatement("SELECT set_config('app.workspace_id', ?, true)")) {
			ps.setString(1, valor);
			ps.execute();
		}
	}

	private static Connection conectar(String usuario, String clave) throws SQLException {
		return DriverManager.getConnection(base.getJdbcUrl(), usuario, clave);
	}

}
