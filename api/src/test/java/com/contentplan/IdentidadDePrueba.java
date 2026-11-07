package com.contentplan;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.UUID;

import com.contentplan.security.TokensDePrueba;

/**
 * Siembra personas, workspaces y pertenencias en la base de {@link PostgresIntegrationTest}. La
 * aplicación no puede escribir en {@code app_user} ni en {@code membership} (solo lo hacen funciones
 * {@code SECURITY DEFINER}), así que las pruebas lo hacen como dueño del esquema, igual que ellas.
 */
public final class IdentidadDePrueba {

	private IdentidadDePrueba() {
	}

	/** Una persona cuyo token de prueba es {@code TokensDePrueba.valido(sub)}. */
	public static UUID persona(String sub) {
		UUID id = UUID.randomUUID();
		comoDueno("INSERT INTO app_user (id, google_sub, email) VALUES (?, ?, ?)", id, sub,
				TokensDePrueba.correoDe(sub));
		return id;
	}

	/** El workspace tiene RLS forzada: se crea con su propio contexto, como en WorkspaceRlsTest. */
	public static UUID workspace(String nombre) {
		UUID id = UUID.randomUUID();
		try (Connection c = conectar(RolesDePrueba.APLICACION, RolesDePrueba.CLAVE_APLICACION)) {
			c.setAutoCommit(false);
			try (PreparedStatement ps = c.prepareStatement("SELECT set_config('app.workspace_id', ?, true)")) {
				ps.setString(1, id.toString());
				ps.execute();
			}
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

	/** Pertenencia de agencia (propietario o editor): sin cliente. */
	public static void unirse(UUID persona, UUID workspace, String rol) {
		comoDueno("INSERT INTO membership (workspace_id, user_id, role) VALUES (?, ?, ?)", workspace, persona, rol);
	}

	public static void hacerAdministradorDeLaPlataforma(UUID persona) {
		comoDueno("UPDATE app_user SET is_platform_admin = true WHERE id = ?", persona);
	}

	public static int contarPersonas(String sub) {
		try (Connection c = conectar(RolesDePrueba.DUENO, RolesDePrueba.CLAVE_DUENO);
				PreparedStatement ps = c.prepareStatement("SELECT count(*) FROM app_user WHERE google_sub = ?")) {
			ps.setString(1, sub);
			try (ResultSet rs = ps.executeQuery()) {
				rs.next();
				return rs.getInt(1);
			}
		}
		catch (SQLException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static void comoDueno(String sql, Object... parametros) {
		try (Connection c = conectar(RolesDePrueba.DUENO, RolesDePrueba.CLAVE_DUENO);
				PreparedStatement ps = c.prepareStatement(sql)) {
			for (int i = 0; i < parametros.length; i++) {
				ps.setObject(i + 1, parametros[i]);
			}
			ps.executeUpdate();
		}
		catch (SQLException ex) {
			throw new IllegalStateException(ex.getMessage(), ex);
		}
	}

	private static Connection conectar(String usuario, String clave) throws SQLException {
		return DriverManager.getConnection(PostgresIntegrationTest.POSTGRES.getJdbcUrl(), usuario, clave);
	}

}
