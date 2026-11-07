package com.contentplan.identity;

/**
 * Por dónde llega un aviso. Hoy solo existe el buzón de la aplicación ({@link CanalBuzon}); el adaptador de
 * WhatsApp de la Fase 2 será otra implementación que se limite a los avisos importantes, y el de correo
 * llegará cuando exista un dominio propio. {@link Notificador} los recorre todos.
 *
 * Se ejecuta dentro de la transacción de quien avisa: si el evento se deshace, el aviso también.
 */
public interface CanalDeNotificacion {

	void enviar(Aviso aviso);

}
