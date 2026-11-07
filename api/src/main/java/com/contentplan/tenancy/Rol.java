package com.contentplan.tenancy;

import java.util.Arrays;

/**
 * Los cuatro roles de {@code membership.role} (ADR 0005). Los dos primeros son de la agencia y ven todo
 * el workspace; los dos últimos son del cliente y la base los limita a su cliente.
 */
public enum Rol {

	PROPIETARIO("propietario", true),

	EDITOR("editor", true),

	APROBADOR("aprobador", false),

	COLABORADOR("colaborador", false);

	private final String valorEnBase;

	private final boolean deAgencia;

	Rol(String valorEnBase, boolean deAgencia) {
		this.valorEnBase = valorEnBase;
		this.deAgencia = deAgencia;
	}

	/** Lo que guarda la columna {@code role}, que el {@code CHECK} de la tabla limita a estos cuatro. */
	public String valorEnBase() {
		return valorEnBase;
	}

	public boolean esDeAgencia() {
		return deAgencia;
	}

	public static Rol desdeBase(String valor) {
		return Arrays.stream(values()).filter(rol -> rol.valorEnBase.equals(valor)).findFirst()
				// Un rol que la base admite y el código no conoce debe fallar, no convertirse en otro
				.orElseThrow(() -> new IllegalStateException("Rol desconocido en membership"));
	}

}
