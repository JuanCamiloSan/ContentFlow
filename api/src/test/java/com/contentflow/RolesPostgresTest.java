package com.contentflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Los roles de ADR 0003, probados contra un PostgreSQL real. Flyway corre como dueño del esquema y
 * cada comprobación se conecta como el rol que corresponde. Si el rol de aplicación fuera
 * superusuario o dueño de las tablas, las pruebas de aislamiento del bloque B pasarían en falso.
 */
class RolesPostgresTest {

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
	void ningunRolDeLaAplicacionEsSuperusuarioNiSaltaRls() throws SQLException {
		try (Connection c = comoSuperusuario(); Statement s = c.createStatement()) {
			var rs = s.executeQuery("SELECT rolname, rolsuper, rolbypassrls, rolcreatedb, rolcreaterole "
					+ "FROM pg_roles WHERE rolname IN ('" + RolesDePrueba.DUENO + "', '"
					+ RolesDePrueba.APLICACION + "')");
			int roles = 0;
			while (rs.next()) {
				roles++;
				assertThat(rs.getBoolean("rolsuper")).as(rs.getString("rolname") + " superusuario").isFalse();
				assertThat(rs.getBoolean("rolbypassrls")).as(rs.getString("rolname") + " BYPASSRLS").isFalse();
				assertThat(rs.getBoolean("rolcreatedb")).as(rs.getString("rolname") + " CREATEDB").isFalse();
				assertThat(rs.getBoolean("rolcreaterole")).as(rs.getString("rolname") + " CREATEROLE").isFalse();
			}
			assertThat(roles).isEqualTo(2);
		}
	}

	@Test
	void laAplicacionUsaLasTablasPeroNoEsDuenaDeNinguna() throws SQLException {
		UUID id = UUID.randomUUID();
		try (Connection c = comoAplicacion(); Statement s = c.createStatement()) {
			assertThat(s.executeUpdate("INSERT INTO workspace (id, name) VALUES ('" + id + "', 'Agencia')"))
					.isEqualTo(1);
			assertThat(s.executeUpdate("UPDATE workspace SET name = 'Renombrada' WHERE id = '" + id + "'"))
					.isEqualTo(1);
			assertThat(s.executeUpdate("DELETE FROM workspace WHERE id = '" + id + "'")).isEqualTo(1);

			var rs = s.executeQuery("SELECT tableowner FROM pg_tables WHERE tablename = 'workspace'");
			assertThat(rs.next()).isTrue();
			assertThat(rs.getString("tableowner")).isEqualTo(RolesDePrueba.DUENO);
		}
	}

	@Test
	void laAplicacionNoPuedeAlterarElEsquema() throws SQLException {
		try (Connection c = comoAplicacion(); Statement s = c.createStatement()) {
			assertThatThrownBy(() -> s.execute("CREATE TABLE intruso (id int)"))
					.isInstanceOf(SQLException.class).hasMessageContaining("permission denied");
			assertThatThrownBy(() -> s.execute("ALTER TABLE workspace DISABLE ROW LEVEL SECURITY"))
					.isInstanceOf(SQLException.class).hasMessageContaining("must be owner");
			assertThatThrownBy(() -> s.execute("DROP TABLE workspace"))
					.isInstanceOf(SQLException.class).hasMessageContaining("must be owner");
		}
	}

	@Test
	void laAplicacionNoLeeElHistorialDeFlyway() throws SQLException {
		try (Connection c = comoAplicacion(); Statement s = c.createStatement()) {
			assertThatThrownBy(() -> s.executeQuery("SELECT * FROM flyway_schema_history"))
					.isInstanceOf(SQLException.class).hasMessageContaining("permission denied");
		}
	}

	@Test
	void unaTablaNuevaDelDuenoQuedaAccesibleParaLaAplicacionSinGrantManual() throws SQLException {
		try (Connection c = conectar(RolesDePrueba.DUENO, RolesDePrueba.CLAVE_DUENO);
				Statement s = c.createStatement()) {
			s.execute("CREATE TABLE tabla_futura (id int)");
		}
		try (Connection c = comoAplicacion(); Statement s = c.createStatement()) {
			assertThat(s.executeUpdate("INSERT INTO tabla_futura (id) VALUES (1)")).isEqualTo(1);
		}
	}

	@Test
	void sinElScriptDeInicioLasMigracionesFallanEnLugarDeCrearElRol() {
		// Garantiza que el rol viene de fuera: una migración nunca debe crearlo con contraseña
		try (PostgreSQLContainer sinRoles = new PostgreSQLContainer("postgres:16.4")) {
			sinRoles.start();
			assertThatThrownBy(() -> Flyway.configure()
					.dataSource(sinRoles.getJdbcUrl(), sinRoles.getUsername(), sinRoles.getPassword())
					.locations("classpath:db/migration").load().migrate())
					.hasMessageContaining(RolesDePrueba.APLICACION);
		}
	}

	private static Connection comoAplicacion() throws SQLException {
		return conectar(RolesDePrueba.APLICACION, RolesDePrueba.CLAVE_APLICACION);
	}

	private static Connection comoSuperusuario() throws SQLException {
		return conectar(base.getUsername(), base.getPassword());
	}

	private static Connection conectar(String usuario, String clave) throws SQLException {
		return DriverManager.getConnection(base.getJdbcUrl(), usuario, clave);
	}

}
