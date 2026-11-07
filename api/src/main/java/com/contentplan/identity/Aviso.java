package com.contentplan.identity;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Un aviso para una persona, listo para enviarse por los canales. El workspace no viaja aquí: es el de la
 * petición en curso, y la base lo exige igual.
 *
 * @param destinatario la persona que lo recibe
 * @param kind código del aviso, en minúsculas y con guiones bajos
 * @param mensaje texto que escribe el sistema, nunca una persona
 * @param importante los canales externos (WhatsApp, Fase 2) solo envían los importantes
 */
public record Aviso(UUID destinatario, String kind, String mensaje, boolean importante) {

	static final int LARGO_MAXIMO_MENSAJE = 500;

	private static final Pattern KIND = Pattern.compile("[a-z][a-z0-9_]{0,63}");

	public Aviso {
		if (destinatario == null) {
			throw new IllegalArgumentException("Un aviso necesita destinatario");
		}
		if (kind == null || !KIND.matcher(kind).matches()) {
			throw new IllegalArgumentException("El kind debe ir en minúsculas y con guiones bajos");
		}
		if (mensaje == null || mensaje.isBlank() || mensaje.length() > LARGO_MAXIMO_MENSAJE) {
			throw new IllegalArgumentException("El mensaje no puede estar vacío ni pasar de 500 caracteres");
		}
	}

}
