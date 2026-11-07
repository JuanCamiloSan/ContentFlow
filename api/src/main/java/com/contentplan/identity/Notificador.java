package com.contentplan.identity;

import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * La puerta para avisar a una persona. Lo usan los módulos que producen eventos (por ejemplo, al rechazar
 * una versión), nunca un endpoint: ningún cliente crea avisos.
 *
 * Exige una transacción en curso ({@code MANDATORY}) y se une a ella, de modo que el aviso y el evento que
 * lo origina se confirman o se deshacen juntos, y el workspace es el de esa petición.
 */
@Service
public class Notificador {

	private final List<CanalDeNotificacion> canales;

	Notificador(List<CanalDeNotificacion> canales) {
		this.canales = canales;
	}

	@Transactional(propagation = Propagation.MANDATORY)
	public void notificar(Aviso aviso) {
		for (CanalDeNotificacion canal : canales) {
			canal.enviar(aviso);
		}
	}

}
