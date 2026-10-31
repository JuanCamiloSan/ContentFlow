package com.contentflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * La política de RLS de {@code workspace} (B2), probada con el rol de aplicación contra un
 * PostgreSQL real. Con un superusuario estas pruebas pasarían sin probar nada (ADR 0003).
 * El contexto se fija con {@code set_config(..., true)} como lo hará el filtro de B3; aquí es
 * solo andamiaje de prueba.
 */
class WorkspaceRlsTest {

	private static PostgreSQLContainer base;

	@BeforeAll
	static void arrancarBaseYMigrarComoDueno() {
		base = RolesDePrueba.nuevaBase();
		base.start();
		Flyway.configure()
				.dataSource(base.getJdbcUrl(), RolesDePrueba.DUENO, RolesDePrueba.CLAVE_DUENO)
				.locations("classpath:db/migration")
				.load()
				.migrate();
	}

	@AfterAll
	static void detenerBase() {
		base.stop();
	}

	@Test
	void rlsEstaActivaYForzadaEnWorkspace() throws SQLException {
		try (Connection c = conectar(base.getUsername(), base.getPassword());
				PreparedStatement ps = c.prepareStatement(
						"SELECT relrowsecurity, relforcerowsecurity FROM pg_class WHERE relname = 'workspace'");
				ResultSet rs = ps.executeQuery()) {
			assertThat(rs.next()).isTrue();
			assertThat(rs.getBoolean("relrowsecurity")).as("ENABLE").isTrue();
			assertThat(rs.getBoolean("relforcerowsecurity")).as("FORCE").isTrue();
		}
	}

	@Test
	void cadaWorkspaceSoloVeLaFilaPropia() throws SQLException {
		UUID a = crearWorkspace("Agencia A");
		UUID b = crearWorkspace("Agencia B");

		assertThat(idsVisiblesCon(a)).containsExactly(a);
		assertThat(idsVisiblesCon(b)).containsExactly(b);
	}

	@Test
	void noSePuedeLeerLaFilaAjenaPorIdDirecto() throws SQLException {
		UUID a = crearWorkspace("Agencia A");
		UUID b = crearWorkspace("Agencia B");

		try (Connection c = transaccionConContexto(a);
				PreparedStatement ps = c.prepareStatement("SELECT 1 FROM workspace WHERE id = ?")) {
			ps.setObject(1, b);
			try (ResultSet rs = ps.executeQuery()) {
				assertThat(rs.next()).isFalse();
			}
			c.rollback();
		}
	}

	@Test
	void noSePuedeInsertarUnaFilaDeOtroWorkspace() throws SQLException {
		UUID a = crearWorkspace("Agencia A");

		try (Connection c = transaccionConContexto(a);
				PreparedStatement ps = c.prepareStatement("INSERT INTO workspace (id, name) VALUES (?, 'Intrusa')")) {
			ps.setObject(1, UUID.randomUUID());
			assertThatThrownBy(ps::executeUpdate).isInstanceOf(SQLException.class)
					.hasMessageContaining("row-level security");
			c.rollback();
		}
	}

	@Test
	void noSePuedeModificarNiBorrarLaFilaAjena() throws SQLException {
		UUID a = crearWorkspace("Agencia A");
		UUID b = crearWorkspace("Agencia B");

		try (Connection c = transaccionConContexto(a)) {
			try (PreparedStatement ps = c.prepareStatement("UPDATE workspace SET name = 'Hackeada' WHERE id = ?")) {
				ps.setObject(1, b);
				assertThat(ps.executeUpdate()).isZero();
			}
			try (PreparedStatement ps = c.prepareStatement("DELETE FROM workspace WHERE id = ?")) {
				ps.setObject(1, b);
				assertThat(ps.executeUpdate()).isZero();
			}
			c.rollback();
		}
		assertThat(nombreDe(b)).isEqualTo("Agencia B");
	}

	@Test
	void noSePuedeMoverUnaFilaAOtroWorkspaceCambiandoSuId() throws SQLException {
		UUID a = crearWorkspace("Agencia A");

		try (Connection c = transaccionConContexto(a);
				PreparedStatement ps = c.prepareStatement("UPDATE workspace SET id = ? WHERE id = ?")) {
			ps.setObject(1, UUID.randomUUID());
			ps.setObject(2, a);
			assertThatThrownBy(ps::executeUpdate).isInstanceOf(SQLException.class)
					.hasMessageContaining("row-level security");
			c.rollback();
		}
	}

	@Test
	void sinContextoLaConsultaFallaEnLugarDeDevolverCeroFilas() throws SQLException {
		try (Connection c = conectar(RolesDePrueba.APLICACION, RolesDePrueba.CLAVE_APLICACION);
				PreparedStatement ps = c.prepareStatement("SELECT id FROM workspace")) {
			assertThatThrownBy(ps::executeQuery).isInstanceOf(SQLException.class)
					.hasMessageContaining("No hay contexto de workspace");
		}
	}

	@Test
	void unaCadenaVaciaCuentaComoSinContexto() throws SQLException {
		try (Connection c = conectar(RolesDePrueba.APLICACION, RolesDePrueba.CLAVE_APLICACION)) {
			c.setAutoCommit(false);
			fijarWorkspace(c, "");
			try (PreparedStatement ps = c.prepareStatement("SELECT id FROM workspace")) {
				assertThatThrownBy(ps::executeQuery).isInstanceOf(SQLException.class)
						.hasMessageContaining("No hay contexto de workspace");
			}
			c.rollback();
		}
	}

	@Test
	void laPoliticaTambienAplicaAlDuenoDeLaTabla() throws SQLException {
		try (Connection c = conectar(RolesDePrueba.DUENO, RolesDePrueba.CLAVE_DUENO);
				PreparedStatement ps = c.prepareStatement("SELECT id FROM workspace")) {
			assertThatThrownBy(ps::executeQuery).isInstanceOf(SQLException.class)
					.hasMessageContaining("No hay contexto de workspace");
		}
	}

	/** Crea el workspace bajo su propio contexto: la política exige que id sea el workspace activo. */
	private static UUID crearWorkspace(String nombre) throws SQLException {
		UUID id = UUID.randomUUID();
		try (Connection c = transaccionConContexto(id);
				PreparedStatement ps = c.prepareStatement("INSERT INTO workspace (id, name) VALUES (?, ?)")) {
			ps.setObject(1, id);
			ps.setString(2, nombre);
			assertThat(ps.executeUpdate()).isEqualTo(1);
			c.commit();
		}
		return id;
	}

	private static List<UUID> idsVisiblesCon(UUID workspace) throws SQLException {
		List<UUID> ids = new ArrayList<>();
		try (Connection c = transaccionConContexto(workspace);
				PreparedStatement ps = c.prepareStatement("SELECT id FROM workspace");
				ResultSet rs = ps.executeQuery()) {
			while (rs.next()) {
				ids.add(rs.getObject("id", UUID.class));
			}
			c.rollback();
		}
		return ids;
	}

	private static String nombreDe(UUID id) throws SQLException {
		try (Connection c = transaccionConContexto(id);
				PreparedStatement ps = c.prepareStatement("SELECT name FROM workspace WHERE id = ?")) {
			ps.setObject(1, id);
			try (ResultSet rs = ps.executeQuery()) {
				assertThat(rs.next()).isTrue();
				return rs.getString("name");
			} finally {
				c.rollback();
			}
		}
	}

	/** Conexión del rol de aplicación con una transacción abierta y el workspace ya fijado. */
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
