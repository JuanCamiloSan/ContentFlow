package com.contentplan.identity;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/** El buzón dentro de la aplicación: guarda el aviso en {@code notification}. */
@Component
class CanalBuzon implements CanalDeNotificacion {

	private final JdbcTemplate jdbc;

	CanalBuzon(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Override
	public void enviar(Aviso aviso) {
		// El workspace sale del contexto de la transacción (current_workspace() falla si no lo hay), no de
		// un parámetro: no hay forma de escribir un aviso en un workspace distinto al de la petición.
		// Sin RETURNING: la política deja leer solo los avisos propios, y este es de otra persona.
		jdbc.update("INSERT INTO notification (workspace_id, user_id, kind, message, important) "
				+ "VALUES (current_workspace(), ?, ?, ?, ?)", aviso.destinatario(), aviso.kind(), aviso.mensaje(),
				aviso.importante());
	}

}
