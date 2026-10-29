package com.contentflow.tenancy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;

import javax.sql.DataSource;

import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * El único punto donde se fija el contexto de sesión (ADR 0003). Cada transacción que abre la
 * aplicación pasa por aquí, así que ningún endpoint puede olvidarse de fijarlo.
 *
 * Usa {@code set_config(nombre, valor, true)} con parámetros enlazados: el tercer argumento
 * equivale a {@code SET LOCAL} y el valor muere al terminar la transacción, de modo que la
 * conexión que vuelve al pool no arrastra el workspace anterior. {@code SET LOCAL} no admite
 * parámetros y concatenar el valor sería abrir la puerta a una inyección.
 *
 * Sin contexto no fija nada: las políticas fallan ruidosamente con {@code current_workspace()}.
 */
public class TransaccionesConContexto extends DataSourceTransactionManager {

	private static final String FIJAR = "SELECT set_config(?, ?, true)";

	public TransaccionesConContexto(DataSource dataSource) {
		super(dataSource);
	}

	@Override
	protected void doBegin(Object transaction, TransactionDefinition definition) {
		super.doBegin(transaction, definition);
		try {
			ConnectionHolder holder = (ConnectionHolder) TransactionSynchronizationManager
					.getResource(obtainDataSource());
			Connection conexion = holder.getConnection();
			ContextoDeSesionActual.actual().ifPresent(contexto -> fijar(conexion, contexto));
		}
		catch (RuntimeException ex) {
			// super.doBegin ya dejó la conexión ligada al hilo; si no se libera aquí, se pierde
			doCleanupAfterCompletion(transaction);
			throw new CannotCreateTransactionException("No se pudo fijar el contexto de sesión", ex);
		}
	}

	private static void fijar(Connection conexion, ContextoDeSesion contexto) {
		try {
			fijar(conexion, "app.user_id", contexto.userId());
			fijar(conexion, "app.workspace_id", contexto.workspaceId());
		}
		catch (SQLException ex) {
			throw new IllegalStateException(ex);
		}
	}

	private static void fijar(Connection conexion, String nombre, UUID valor) throws SQLException {
		if (valor == null) {
			return;
		}
		try (PreparedStatement ps = conexion.prepareStatement(FIJAR)) {
			ps.setString(1, nombre);
			ps.setString(2, valor.toString());
			ps.execute();
		}
	}

}
