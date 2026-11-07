package com.contentplan.tenancy;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * C4: de un token validado al contexto de tenant (ADR 0003). Convierte el {@code sub} de Google en el
 * {@code uuid} de la persona y resuelve su workspace activo contra {@code membership}.
 *
 * Son dos transacciones cortas que terminan antes de que empiece la de la petición, de modo que no
 * retienen una conexión del pool mientras el controlador trabaja:
 * <ol>
 * <li>{@code resolver_usuario}: función {@code SECURITY DEFINER}, sin contexto, que devuelve (y la
 * primera vez crea) a la persona.</li>
 * <li>Lectura de {@code membership} con solo {@code app.user_id} fijado. Es RLS legítimo: la política
 * deja ver las filas propias antes de que haya workspace activo.</li>
 * </ol>
 * El valor de {@code app.workspace_id} solo sale de aquí si la persona pertenece a ese workspace; las
 * transacciones las abre {@link TransaccionesConContexto}, el único punto que escribe en la base.
 */
@Component
public class ResolutorDeContexto {

	private final JdbcTemplate jdbc;

	private final TransactionTemplate transaccion;

	public ResolutorDeContexto(JdbcTemplate jdbc, PlatformTransactionManager transacciones) {
		this.jdbc = jdbc;
		this.transaccion = new TransactionTemplate(transacciones);
	}

	/**
	 * @param googleSub identidad estable de la persona, del token
	 * @param correo correo verificado del token
	 * @param workspaceSolicitado valor crudo del encabezado {@code X-Workspace-Id}, o {@code null}
	 * @return el contexto; su {@code workspaceId} es {@code null} si la persona aún no pertenece a ninguno
	 * @throws ContextoRechazadoException si el encabezado no es válido, el workspace no es suyo o hace
	 * falta indicarlo
	 */
	public ContextoDeSesion resolver(String googleSub, String correo, String workspaceSolicitado) {
		// Antes de tocar la base: un encabezado malformado no debe crear personas
		UUID solicitado = parsear(workspaceSolicitado);
		try {
			// Ninguno de los dos pasos lleva contexto previo: lo fija el segundo con lo que acaba de resolver
			ContextoDeSesionActual.limpiar();
			UUID usuario = transaccion.execute(estado -> jdbc.queryForObject("SELECT resolver_usuario(?, ?)",
					UUID.class, googleSub, correo));

			ContextoDeSesionActual.fijar(new ContextoDeSesion(usuario, null));
			Set<UUID> pertenencias = new HashSet<>(transaccion.execute(
					estado -> jdbc.queryForList("SELECT workspace_id FROM membership", UUID.class)));

			return new ContextoDeSesion(usuario, elegir(solicitado, pertenencias));
		}
		finally {
			ContextoDeSesionActual.limpiar();
		}
	}

	private static UUID elegir(UUID solicitado, Set<UUID> pertenencias) {
		if (solicitado != null) {
			if (pertenencias.contains(solicitado)) {
				return solicitado;
			}
			// Un workspace que no existe y uno ajeno dan la misma respuesta: no se revela cuál es cuál.
			// TODO(E13): aquí el administrador de la plataforma (app_user.is_platform_admin) podrá
			// entrar a un workspace ajeno, pero solo si antes se escribe la fila en audit_log (ADR 0006:
			// "sin fila no hay acceso"). Hasta que exista audit_log, el administrador recibe este 403
			// como cualquier otra persona sin pertenencia.
			throw new ContextoRechazadoException(403, "No perteneces a ese workspace");
		}
		if (pertenencias.isEmpty()) {
			// Registrada pero sin workspace: solo puede crear el suyo (C14) o aceptar una invitación (C9)
			return null;
		}
		if (pertenencias.size() == 1) {
			return pertenencias.iterator().next();
		}
		throw new ContextoRechazadoException(400, "Indica el workspace en el encabezado X-Workspace-Id");
	}

	private static UUID parsear(String valor) {
		if (valor == null || valor.isBlank()) {
			return null;
		}
		try {
			return UUID.fromString(valor.trim());
		}
		catch (IllegalArgumentException ex) {
			// Sin eco del valor recibido: no se registra ni se devuelve lo que mandó el cliente
			throw new ContextoRechazadoException(400, "Encabezado X-Workspace-Id inválido");
		}
	}

}
