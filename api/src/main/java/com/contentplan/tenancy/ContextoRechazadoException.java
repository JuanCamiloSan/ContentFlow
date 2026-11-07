package com.contentplan.tenancy;

/**
 * La petición viene autenticada pero no se le puede dar un contexto de tenant: el workspace pedido no
 * es suyo, o falta indicarlo. Lleva el estado HTTP que debe recibir el cliente.
 *
 * El mensaje es para el cliente: nunca incluye el valor que mandó ni datos de otras personas.
 */
public class ContextoRechazadoException extends RuntimeException {

	private final int estado;

	public ContextoRechazadoException(int estado, String mensaje) {
		super(mensaje);
		this.estado = estado;
	}

	public int estado() {
		return estado;
	}

}
