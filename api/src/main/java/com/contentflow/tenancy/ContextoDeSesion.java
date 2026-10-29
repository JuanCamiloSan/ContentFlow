package com.contentflow.tenancy;

import java.util.UUID;

/**
 * Quién pregunta y en qué workspace, tal como lo verá la base en {@code app.user_id} y
 * {@code app.workspace_id} (ADR 0003). Son {@code UUID} y no texto: el valor se valida en Java
 * antes de llegar a la base.
 *
 * Cualquiera de los dos puede faltar. Si falta el workspace, {@code current_workspace()} hace
 * fallar la consulta en lugar de devolver cero filas.
 */
public record ContextoDeSesion(UUID userId, UUID workspaceId) {
}
