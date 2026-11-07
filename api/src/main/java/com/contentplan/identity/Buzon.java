package com.contentplan.identity;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * El buzón de la persona autenticada. Ninguna consulta filtra por destinatario: lo impone la política
 * RLS {@code solo_destinatario_*}, así que pedir el aviso de otra persona devuelve lo mismo que pedir uno
 * que no existe. Cada método abre una transacción para que se fije el contexto de sesión (ADR 0003).
 */
@Service
class Buzon {

	enum Filtro {

		TODAS(""), SIN_LEER("WHERE read_at IS NULL"), IMPORTANTES("WHERE important");

		private final String condicion;

		Filtro(String condicion) {
			this.condicion = condicion;
		}

	}

	private static final RowMapper<Notificacion> FILA = (rs, fila) -> new Notificacion(rs.getObject("id", UUID.class),
			rs.getString("kind"), rs.getString("message"), rs.getBoolean("important"),
			rs.getObject("created_at", OffsetDateTime.class).toInstant(),
			rs.getObject("read_at", OffsetDateTime.class) == null ? null
					: rs.getObject("read_at", OffsetDateTime.class).toInstant());

	private final JdbcTemplate jdbc;

	Buzon(JdbcTemplate jdbc) {
		this.jdbc = jdbc;
	}

	@Transactional(readOnly = true)
	List<Notificacion> listar(Filtro filtro, int limite) {
		// La condición sale de un enum, nunca del cliente; el límite va enlazado
		return jdbc.query("SELECT id, kind, message, important, created_at, read_at FROM notification "
				+ filtro.condicion + " ORDER BY created_at DESC, id LIMIT ?", FILA, limite);
	}

	@Transactional(readOnly = true)
	long contarSinLeer() {
		return jdbc.queryForObject("SELECT count(*) FROM notification WHERE read_at IS NULL", Long.class);
	}

	/** @return {@code false} si no existe o no es de la persona, que para ella es lo mismo */
	@Transactional
	boolean marcarLeida(UUID id) {
		// COALESCE: marcar dos veces no mueve la fecha de la primera lectura
		return jdbc.update("UPDATE notification SET read_at = COALESCE(read_at, now()), updated_at = now() "
				+ "WHERE id = ?", id) == 1;
	}

	@Transactional
	int marcarTodasLeidas() {
		return jdbc.update("UPDATE notification SET read_at = now(), updated_at = now() WHERE read_at IS NULL");
	}

}
