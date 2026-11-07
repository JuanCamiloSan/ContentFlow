package com.contentplan.tenancy;

import java.util.UUID;

/**
 * Quién pregunta, en qué workspace y con qué rol, tal como lo verá la base en {@code app.user_id} y
 * {@code app.workspace_id} (ADR 0003). Son {@code UUID} y no texto: el valor se valida en Java
 * antes de llegar a la base.
 *
 * Cualquiera de los dos identificadores puede faltar. Si falta el workspace, {@code current_workspace()}
 * hace fallar la consulta en lugar de devolver cero filas. El rol sale de {@code membership} (C5), solo
 * lo lee el código para autorizar acciones y no se fija en la base: allí el aislamiento lo da la
 * política, no el rol. Es {@code null} cuando no hay workspace.
 */
public record ContextoDeSesion(UUID userId, UUID workspaceId, Rol rol) {

	public ContextoDeSesion(UUID userId, UUID workspaceId) {
		this(userId, workspaceId, null);
	}

}
