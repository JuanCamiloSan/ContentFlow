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

	/** Pertenencia de agencia (propietario o editor): sin cliente. Un rol de cliente recibe uno al azar. */
	public static void unirse(UUID persona, UUID workspace, String rol) {
		boolean deCliente = rol.equals("aprobador") || rol.equals("colaborador");
		// client no tiene tabla hasta D1: el CHECK de membership solo exige que el rol de cliente lleve uno
		comoDueno("INSERT INTO membership (workspace_id, user_id, role, client_id) VALUES (?, ?, ?, ?)", workspace,
				persona, rol, deCliente ? UUID.randomUUID() : null);
	}

	/** Cambia el rol y ajusta el cliente en la misma sentencia, porque el CHECK de membership los liga. */
	public static void cambiarRol(UUID persona, UUID workspace, String rol) {
		boolean deCliente = rol.equals("aprobador") || rol.equals("colaborador");
		comoDueno("UPDATE membership SET role = ?, client_id = ? WHERE user_id = ? AND workspace_id = ?", rol,
				deCliente ? UUID.randomUUID() : null, persona, workspace);
	}

	public static void hacerAdministradorDeLaPlataforma(UUID persona) {
		comoDueno("UPDATE app_user SET is_platform_admin = true WHERE id = ?", persona);
	}

	/**
	 * Siembra un aviso sin pasar por la API. Usa el superusuario del contenedor: {@code notification} tiene
	 * RLS forzada y hasta el dueño necesitaría contexto, y aquí solo se prepara o se inspecciona.
	 */
	public static UUID aviso(UUID workspace, UUID destinatario, String kind, String mensaje, boolean importante,
			boolean leido, int minutosAtras) {
		UUID id = UUID.randomUUID();
		comoSuperusuario("INSERT INTO notification (id, workspace_id, user_id, kind, message, important, read_at, "
				+ "created_at) VALUES (?, ?, ?, ?, ?, ?, CASE WHEN ? THEN now() END, now() - make_interval(mins => ?))",
				id, workspace, destinatario, kind, mensaje, importante, leido, minutosAtras);
		return id;
	}

	/** La fecha de lectura tal como está guardada, o {@code null} si no se ha leído. */
	public static String leidoEn(UUID aviso) {
		return consultaTexto("SELECT read_at::text FROM notification WHERE id = ?", aviso);
	}

	public static String textoDelAviso(UUID aviso) {
		return consultaTexto("SELECT message FROM notification WHERE id = ?", aviso);
	}

	public static int contarAvisos(UUID workspace) {
		return Integer.parseInt(consultaTexto("SELECT count(*) FROM notification WHERE workspace_id = ?", workspace));
	}

	private static String consultaTexto(String sql, Object parametro) {
		try (Connection c = conectar(PostgresIntegrationTest.POSTGRES.getUsername(),
				PostgresIntegrationTest.POSTGRES.getPassword()); PreparedStatement ps = c.prepareStatement(sql)) {
			ps.setObject(1, parametro);
			try (ResultSet rs = ps.executeQuery()) {
				return rs.next() ? rs.getString(1) : null;
			}
		}
		catch (SQLException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static void comoSuperusuario(String sql, Object... parametros) {
		try (Connection c = conectar(PostgresIntegrationTest.POSTGRES.getUsername(),
				PostgresIntegrationTest.POSTGRES.getPassword()); PreparedStatement ps = c.prepareStatement(sql)) {
			for (int i = 0; i < parametros.length; i++) {
				ps.setObject(i + 1, parametros[i]);
			}
			ps.executeUpdate();
		}
		catch (SQLException ex) {
			throw new IllegalStateException(ex.getMessage(), ex);
		}
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
