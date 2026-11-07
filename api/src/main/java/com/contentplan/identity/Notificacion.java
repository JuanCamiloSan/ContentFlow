package com.contentplan.identity;

import java.time.Instant;
import java.util.UUID;

/** Un aviso tal como lo ve su destinatario. No expone el workspace ni a quién más se le mandó. */
public record Notificacion(UUID id, String kind, String message, boolean important, Instant createdAt,
		Instant readAt) {

}
