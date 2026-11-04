package com.contentplan;

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
 * C3: {@code app_user} y {@code membership}, las dos tablas cuyo aislamiento se apoya en
 * {@code app.user_id} y no en el workspace (ADR 0003). Todo se comprueba con el rol de aplicación contra
 * un PostgreSQL real; con un superusuario estas pruebas pasarían sin probar nada. Los datos de prueba
 * los inserta el dueño del esquema, que es lo que harán las funciones {@code SECURITY DEFINER}.
 */
class IdentidadRlsTest {

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

	// ---- Configuración de las tablas

	@Test
	void rlsEstaActivaEnAmbasTablasYSinForceAPropositoParaLasFuncionesDelDueno() throws SQLException {
		for (String tabla : List.of("app_user", "membership")) {
			try (Connection c = conectar(base.getUsername(), base.getPassword());
					PreparedStatement ps = c.prepareStatement(
							"SELECT relrowsecurity, relforcerowsecurity FROM pg_class WHERE relname = ?")) {
				ps.setString(1, tabla);
				try (ResultSet rs = ps.executeQuery()) {
					assertThat(rs.next()).isTrue();
					assertThat(rs.getBoolean("relrowsecurity")).as("ENABLE en " + tabla).isTrue();
					assertThat(rs.getBoolean("relforcerowsecurity")).as("FORCE en " + tabla).isFalse();
				}
			}
		}
	}

	@Test
	void unaPersonaNuevaNaceConUnWorkspaceDeCupoYSinSerAdministrador() throws SQLException {
		UUID persona = crearPersona("nueva");

		try (Connection c = conectar(RolesDePrueba.DUENO, RolesDePrueba.CLAVE_DUENO);
				PreparedStatement ps = c.prepareStatement(
						"SELECT max_workspaces, is_platform_admin FROM app_user WHERE id = ?")) {
			ps.setObject(1, persona);
			try (ResultSet rs = ps.executeQuery()) {
				assertThat(rs.next()).isTrue();
				assertThat(rs.getInt("max_workspaces")).isEqualTo(1);
				assertThat(rs.getBoolean("is_platform_admin")).isFalse();
			}
		}
	}

	@Test
	void elCorreoSeGuardaNormalizadoYElSubDeGoogleNoSeRepite() {
		assertThatThrownBy(() -> comoDueno("INSERT INTO app_user (google_sub, email) VALUES ('s-1', 'Mayus@Example.com')"))
				.hasMessageContaining("check constraint");

		comoDueno("INSERT INTO app_user (google_sub, email) VALUES ('s-repetido', 'a@example.com')");
		assertThatThrownBy(() -> comoDueno("INSERT INTO app_user (google_sub, email) VALUES ('s-repetido', 'b@example.com')"))
				.hasMessageContaining("duplicate key");
	}

	// ---- app_user

	@Test
	void cadaPersonaVeSoloSuPropiaFila() throws SQLException {
		UUID ana = crearPersona("ana");
		UUID beto = crearPersona("beto");

		assertThat(ids(ana, null, "SELECT id FROM app_user")).containsExactly(ana);
		assertThat(ids(beto, null, "SELECT id FROM app_user")).containsExactly(beto);
	}

	@Test
	void noSePuedeLeerLaFilaDeOtraPersonaPorIdDirecto() throws SQLException {
		UUID ana = crearPersona("ana");
		UUID beto = crearPersona("beto");

		assertThat(ids(ana, null, "SELECT id FROM app_user WHERE id = '" + beto + "'")).isEmpty();
	}

	@Test
	void sinUsuarioEnElContextoLaConsultaFallaEnLugarDeDevolverCeroFilas() throws SQLException {
		try (Connection c = conectar(RolesDePrueba.APLICACION, RolesDePrueba.CLAVE_APLICACION);
				PreparedStatement ps = c.prepareStatement("SELECT id FROM app_user")) {
			assertThatThrownBy(ps::executeQuery).isInstanceOf(SQLException.class)
					.hasMessageContaining("No hay contexto de usuario");
		}
	}

	@Test
	void unaCadenaVaciaComoUsuarioCuentaComoSinContexto() throws SQLException {
		try (Connection c = conectar(RolesDePrueba.APLICACION, RolesDePrueba.CLAVE_APLICACION)) {
			c.setAutoCommit(false);
			fijar(c, "app.user_id", "");
			try (PreparedStatement ps = c.prepareStatement("SELECT id FROM app_user")) {
				assertThatThrownBy(ps::executeQuery).isInstanceOf(SQLException.class)
						.hasMessageContaining("No hay contexto de usuario");
			}
			c.rollback();
		}
	}

	@Test
	void laAplicacionNoPuedeCrearModificarNiBorrarPersonas() throws SQLException {
		UUID ana = crearPersona("ana");

		// La más grave: subirse a sí misma el cupo o la marca de administrador
		for (String sql : List.of(
				"UPDATE app_user SET is_platform_admin = true WHERE id = '" + ana + "'",
				"UPDATE app_user SET max_workspaces = 99 WHERE id = '" + ana + "'",
				"DELETE FROM app_user WHERE id = '" + ana + "'",
				"INSERT INTO app_user (google_sub, email) VALUES ('s-intruso', 'i@example.com')")) {
			assertDenegadoPorPermisos(ana, null, sql);
		}
		assertThat(esAdministrador(ana)).isFalse();
	}

	// ---- membership

	@Test
	void cadaPersonaVeSusPertenenciasEnTodosSusWorkspaces() throws SQLException {
		UUID ana = crearPersona("ana");
		UUID beto = crearPersona("beto");
		UUID w1 = crearWorkspace("Agencia 1");
		UUID w2 = crearWorkspace("Agencia 2");
		UUID w3 = crearWorkspace("Agencia 3");
		unirse(ana, w1, "propietario", null);
		unirse(ana, w2, "editor", null);
		unirse(beto, w3, "propietario", null);

		// Sin workspace activo: es como se descubre a cuáles pertenece, el "huevo y la gallina" del ADR 0003
		assertThat(ids(ana, null, "SELECT workspace_id FROM membership")).containsExactlyInAnyOrder(w1, w2);
		assertThat(ids(beto, null, "SELECT workspace_id FROM membership")).containsExactly(w3);
	}

	@Test
	void conWorkspaceActivoSeVeElEquipoPeroNoElDeOtroWorkspace() throws SQLException {
		UUID ana = crearPersona("ana");
		UUID beto = crearPersona("beto");
		UUID carla = crearPersona("carla");
		UUID w1 = crearWorkspace("Agencia 1");
		UUID w3 = crearWorkspace("Agencia 3");
		unirse(ana, w1, "propietario", null);
		unirse(beto, w1, "editor", null);
		unirse(carla, w3, "propietario", null);

		assertThat(ids(ana, w1, "SELECT user_id FROM membership")).containsExactlyInAnyOrder(ana, beto);
		assertThat(ids(ana, null, "SELECT user_id FROM membership")).containsExactly(ana);
	}

	@Test
	void noSePuedeLeerLaPertenenciaDeOtroWorkspaceAjenoPorIdDirecto() throws SQLException {
		UUID ana = crearPersona("ana");
		UUID carla = crearPersona("carla");
		UUID w1 = crearWorkspace("Agencia 1");
		UUID w3 = crearWorkspace("Agencia 3");
		unirse(ana, w1, "propietario", null);
		UUID ajena = unirse(carla, w3, "propietario", null);

		assertThat(ids(ana, w1, "SELECT id FROM membership WHERE id = '" + ajena + "'")).isEmpty();
	}

	@Test
	void sinContextoLaLecturaDevuelveCeroFilasEnLugarDeFallar() throws SQLException {
		UUID ana = crearPersona("ana");
		unirse(ana, crearWorkspace("Agencia 1"), "propietario", null);

		// Aquí cero filas es la respuesta correcta (ADR 0003): antes de resolver el workspace no hay nada que ver
		assertThat(ids(null, null, "SELECT id FROM membership")).isEmpty();
		try (Connection c = conectar(RolesDePrueba.APLICACION, RolesDePrueba.CLAVE_APLICACION)) {
			c.setAutoCommit(false);
			fijar(c, "app.user_id", "");
			fijar(c, "app.workspace_id", "");
			try (PreparedStatement ps = c.prepareStatement("SELECT id FROM membership");
					ResultSet rs = ps.executeQuery()) {
				assertThat(rs.next()).isFalse();
			}
			c.rollback();
		}
	}

	@Test
	void laAplicacionNoPuedeCrearModificarNiBorrarPertenencias() throws SQLException {
		UUID ana = crearPersona("ana");
		UUID w1 = crearWorkspace("Agencia 1");
		UUID w2 = crearWorkspace("Agencia 2");
		UUID propia = unirse(ana, w1, "colaborador", UUID.randomUUID());

		// Un colaborador que se hace propietario, o que se agrega a otro workspace, es la escalada que hay que impedir
		for (String sql : List.of(
				"UPDATE membership SET role = 'propietario', client_id = NULL WHERE id = '" + propia + "'",
				"DELETE FROM membership WHERE id = '" + propia + "'",
				"INSERT INTO membership (workspace_id, user_id, role) VALUES ('" + w2 + "', '" + ana + "', 'propietario')")) {
			assertDenegadoPorPermisos(ana, w1, sql);
		}
		assertThat(ids(ana, w1, "SELECT id FROM membership")).containsExactly(propia);
	}

	@Test
	void losRolesDeAgenciaNoLlevanClienteYLosDeClienteSiLoExigen() {
		UUID ana = crearPersona("persona");
		UUID w1 = crearWorkspace("Agencia");
		UUID cliente = UUID.randomUUID();

		assertThatThrownBy(() -> insertarPertenencia(ana, w1, "propietario", cliente))
				.hasMessageContaining("membership_rol_y_cliente");
		assertThatThrownBy(() -> insertarPertenencia(ana, w1, "editor", cliente))
				.hasMessageContaining("membership_rol_y_cliente");
		assertThatThrownBy(() -> insertarPertenencia(ana, w1, "aprobador", null))
				.hasMessageContaining("membership_rol_y_cliente");
		assertThatThrownBy(() -> insertarPertenencia(ana, w1, "colaborador", null))
				.hasMessageContaining("membership_rol_y_cliente");
		assertThatThrownBy(() -> insertarPertenencia(ana, w1, "superusuario", null))
				.hasMessageContaining("check constraint");

		// Y las combinaciones válidas pasan
		insertarPertenencia(ana, w1, "aprobador", cliente);
	}

	@Test
	void unaPersonaTieneUnaSolaPertenenciaPorWorkspace() {
		UUID ana = crearPersona("persona");
		UUID w1 = crearWorkspace("Agencia");
		insertarPertenencia(ana, w1, "editor", null);

		assertThatThrownBy(() -> insertarPertenencia(ana, w1, "propietario", null))
				.hasMessageContaining("membership_una_por_persona");
	}

	@Test
	void unaPertenenciaExigeUnaPersonaYUnWorkspaceQueExisten() {
		UUID ana = crearPersona("persona");
		UUID w1 = crearWorkspace("Agencia");

		assertThatThrownBy(() -> insertarPertenencia(UUID.randomUUID(), w1, "editor", null))
				.hasMessageContaining("foreign key");
		assertThatThrownBy(() -> insertarPertenencia(ana, UUID.randomUUID(), "editor", null))
				.hasMessageContaining("foreign key");
	}

	// ---- puede_ver_cliente: la segunda frontera (ADR 0003, decisión 7)

	@Test
	void unRolDeAgenciaVeTodosLosClientesDeSuWorkspace() throws SQLException {
		UUID ana = crearPersona("ana");
		UUID w1 = crearWorkspace("Agencia 1");
		unirse(ana, w1, "editor", null);

		assertThat(puedeVer(ana, w1, UUID.randomUUID())).isTrue();
		assertThat(puedeVer(ana, w1, UUID.randomUUID())).isTrue();
	}

	@Test
	void unRolDeClienteSoloVeSuCliente() throws SQLException {
		UUID ana = crearPersona("ana");
		UUID w1 = crearWorkspace("Agencia 1");
		UUID suCliente = UUID.randomUUID();
		unirse(ana, w1, "aprobador", suCliente);

		assertThat(puedeVer(ana, w1, suCliente)).isTrue();
		assertThat(puedeVer(ana, w1, UUID.randomUUID())).isFalse();
	}

	@Test
	void quienNoPerteneceAlWorkspaceActivoNoVeNingunCliente() throws SQLException {
		UUID ana = crearPersona("ana");
		UUID w1 = crearWorkspace("Agencia 1");
		UUID w2 = crearWorkspace("Agencia 2");
		UUID cliente = UUID.randomUUID();
		unirse(ana, w1, "propietario", null);

		assertThat(puedeVer(ana, w2, cliente)).isFalse();
	}

	@Test
	void unaMembershipDeOtroWorkspaceNoSirveParaVerClientesDeEste() throws SQLException {
		UUID ana = crearPersona("ana");
		UUID w1 = crearWorkspace("Agencia 1");
		UUID w2 = crearWorkspace("Agencia 2");
		UUID cliente = UUID.randomUUID();
		unirse(ana, w1, "aprobador", cliente);

		// El mismo UUID de cliente en otro workspace no abre nada
		assertThat(puedeVer(ana, w2, cliente)).isFalse();
	}

	@Test
	void elAdministradorDeLaPlataformaVeTodosLosClientesSinTenerPertenencia() throws SQLException {
		UUID admin = crearPersona("admin");
		UUID w1 = crearWorkspace("Agencia 1");
		comoDueno("UPDATE app_user SET is_platform_admin = true WHERE id = '" + admin + "'");

		assertThat(puedeVer(admin, w1, UUID.randomUUID())).isTrue();
	}

	@Test
	void sinContextoDeUsuarioOdeWorkspaceLaFuncionFalla() throws SQLException {
		UUID ana = crearPersona("ana");
		UUID w1 = crearWorkspace("Agencia 1");

		assertThatThrownBy(() -> puedeVer(null, w1, UUID.randomUUID())).hasMessageContaining("No hay contexto de usuario");
		assertThatThrownBy(() -> puedeVer(ana, null, UUID.randomUUID())).hasMessageContaining("No hay contexto de workspace");
	}

	// ---- Auxiliares

	private static boolean puedeVer(UUID usuario, UUID workspace, UUID cliente) throws SQLException {
		try (Connection c = conexionConContexto(usuario, workspace);
				PreparedStatement ps = c.prepareStatement("SELECT puede_ver_cliente(?)")) {
			ps.setObject(1, cliente);
			try (ResultSet rs = ps.executeQuery()) {
				assertThat(rs.next()).isTrue();
				boolean resultado = rs.getBoolean(1);
				c.rollback();
				return resultado;
			}
		}
	}

	/** Ejecuta con el rol de aplicación y exige que la base lo niegue por falta de permisos, no por RLS ni por otra cosa. */
	private static void assertDenegadoPorPermisos(UUID usuario, UUID workspace, String sql) throws SQLException {
		try (Connection c = conexionConContexto(usuario, workspace);
				PreparedStatement ps = c.prepareStatement(sql)) {
			assertThatThrownBy(ps::executeUpdate).as(sql).isInstanceOf(SQLException.class)
					.hasMessageContaining("permission denied");
			c.rollback();
		}
	}

	/** Primera columna de la consulta, con el rol de aplicación y el contexto indicado (los nulos no se fijan). */
	private static List<UUID> ids(UUID usuario, UUID workspace, String sql) throws SQLException {
		List<UUID> ids = new ArrayList<>();
		try (Connection c = conexionConContexto(usuario, workspace);
				PreparedStatement ps = c.prepareStatement(sql);
				ResultSet rs = ps.executeQuery()) {
			while (rs.next()) {
				ids.add(rs.getObject(1, UUID.class));
			}
			c.rollback();
		}
		return ids;
	}

	private static Connection conexionConContexto(UUID usuario, UUID workspace) throws SQLException {
		Connection c = conectar(RolesDePrueba.APLICACION, RolesDePrueba.CLAVE_APLICACION);
		c.setAutoCommit(false);
		if (usuario != null) {
			fijar(c, "app.user_id", usuario.toString());
		}
		if (workspace != null) {
			fijar(c, "app.workspace_id", workspace.toString());
		}
		return c;
	}

	private static void fijar(Connection c, String variable, String valor) throws SQLException {
		try (PreparedStatement ps = c.prepareStatement("SELECT set_config(?, ?, true)")) {
			ps.setString(1, variable);
			ps.setString(2, valor);
			ps.execute();
		}
	}

	private static UUID crearPersona(String nombre) {
		UUID id = UUID.randomUUID();
		comoDueno("INSERT INTO app_user (id, google_sub, email) VALUES ('" + id + "', 'sub-" + id + "', '"
				+ nombre + "-" + id + "@example.com')");
		return id;
	}


	/** El workspace tiene RLS forzada: se crea con su propio contexto, como en WorkspaceRlsTest. */
	private static UUID crearWorkspace(String nombre) {
		UUID id = UUID.randomUUID();
		try (Connection c = conectar(RolesDePrueba.APLICACION, RolesDePrueba.CLAVE_APLICACION)) {
			c.setAutoCommit(false);
			fijar(c, "app.workspace_id", id.toString());
			try (PreparedStatement ps = c.prepareStatement("INSERT INTO workspace (id, name) VALUES (?, ?)")) {
				ps.setObject(1, id);
				ps.setString(2, nombre);
				ps.executeUpdate();
			}
			c.commit();
		}
		catch (SQLException ex) {
			throw new IllegalStateException(ex);
		}
		return id;
	}


	private static UUID unirse(UUID persona, UUID workspace, String rol, UUID cliente) {
		return insertarPertenencia(persona, workspace, rol, cliente);
	}

	private static UUID insertarPertenencia(UUID persona, UUID workspace, String rol, UUID cliente) {
		UUID id = UUID.randomUUID();
		try (Connection c = conectar(RolesDePrueba.DUENO, RolesDePrueba.CLAVE_DUENO);
				PreparedStatement ps = c.prepareStatement(
						"INSERT INTO membership (id, workspace_id, user_id, role, client_id) VALUES (?, ?, ?, ?, ?)")) {
			ps.setObject(1, id);
			ps.setObject(2, workspace);
			ps.setObject(3, persona);
			ps.setString(4, rol);
			ps.setObject(5, cliente);
			ps.executeUpdate();
		}
		catch (SQLException ex) {
			// Las pruebas de restricciones leen el mensaje de la base, no el envoltorio
			throw new IllegalStateException(ex.getMessage(), ex);
		}
		return id;
	}

	private static boolean esAdministrador(UUID persona) throws SQLException {
		try (Connection c = conectar(RolesDePrueba.DUENO, RolesDePrueba.CLAVE_DUENO);
				PreparedStatement ps = c.prepareStatement("SELECT is_platform_admin FROM app_user WHERE id = ?")) {
			ps.setObject(1, persona);
			try (ResultSet rs = ps.executeQuery()) {
				assertThat(rs.next()).isTrue();
				return rs.getBoolean(1);
			}
		}
	}

	/** Como dueño del esquema: lo que harán las funciones SECURITY DEFINER. */
	private static void comoDueno(String sql) {
		try (Connection c = conectar(RolesDePrueba.DUENO, RolesDePrueba.CLAVE_DUENO);
				PreparedStatement ps = c.prepareStatement(sql)) {
			ps.executeUpdate();
		}
		catch (SQLException ex) {
			throw new IllegalStateException(ex.getMessage(), ex);
		}
	}

	private static Connection conectar(String usuario, String clave) throws SQLException {
		return DriverManager.getConnection(base.getJdbcUrl(), usuario, clave);
	}

}
