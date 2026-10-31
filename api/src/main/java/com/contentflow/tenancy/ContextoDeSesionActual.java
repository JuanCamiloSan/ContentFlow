package com.contentflow.tenancy;

import java.util.Optional;

/**
 * El contexto de la petición en curso, ligado al hilo. Lo escribe quien autentica la petición y lo
 * lee {@link TransaccionesConContexto} al abrir cada transacción. Quien lo fija debe limpiarlo en un
 * {@code finally}: los hilos del servidor se reutilizan y un contexto olvidado pasaría a la
 * petición siguiente.
 */
public final class ContextoDeSesionActual {

	private static final ThreadLocal<ContextoDeSesion> ACTUAL = new ThreadLocal<>();

	private ContextoDeSesionActual() {
	}

	public static void fijar(ContextoDeSesion contexto) {
		ACTUAL.set(contexto);
	}

	public static Optional<ContextoDeSesion> actual() {
		return Optional.ofNullable(ACTUAL.get());
	}

	public static void limpiar() {
		ACTUAL.remove();
	}

}
