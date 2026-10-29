package com.contentflow.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

import com.contentflow.PostgresIntegrationTest;

/**
 * B3: el contexto de sesión llega a la base al abrir cada transacción y muere con ella. Corre con
 * el rol de aplicación y con un pool de una sola conexión, de modo que toda transacción reutiliza la
 * misma conexión física: si el contexto sobreviviera, se vería.
 */
@TestPropertySource(properties = { "contentflow.contexto-de-prueba.habilitado=true",
		"spring.datasource.hikari.maximum-pool-size=1" })
@Import(ContextoDeSesionTest.Sondas.class)
class ContextoDeSesionTest extends PostgresIntegrationTest {

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private PlatformTransactionManager transacciones;

	@LocalServerPort
	private int port;

	@AfterEach
	void limpiar() {
		ContextoDeSesionActual.limpiar();
	}

	@Test
	void fijaUsuarioYWorkspaceAlAbrirLaTransaccion() {
		UUID usuario = UUID.randomUUID();
		UUID workspace = UUID.randomUUID();
		ContextoDeSesionActual.fijar(new ContextoDeSesion(usuario, workspace));

		Map<String, Object> vistos = enTransaccion(() -> jdbc.queryForMap(
				"SELECT current_setting('app.user_id', true) AS usuario, "
						+ "current_setting('app.workspace_id', true) AS workspace"));

		assertThat(vistos).containsEntry("usuario", usuario.toString())
				.containsEntry("workspace", workspace.toString());
	}

	@Test
	void elContextoNoSobreviveALaTransaccionEnLaMismaConexionDelPool() {
		ContextoDeSesionActual.fijar(new ContextoDeSesion(UUID.randomUUID(), UUID.randomUUID()));
		enTransaccion(() -> jdbc.queryForObject("SELECT 1", Integer.class));
		ContextoDeSesionActual.limpiar();

		Map<String, Object> vistos = enTransaccion(() -> jdbc.queryForMap(
				"SELECT NULLIF(current_setting('app.user_id', true), '') AS usuario, "
						+ "NULLIF(current_setting('app.workspace_id', true), '') AS workspace"));

		assertThat(vistos.get("usuario")).isNull();
		assertThat(vistos.get("workspace")).isNull();
	}

	@Test
	void sinContextoLaConsultaFallaEnLugarDeDevolverCeroFilas() {
		assertThatThrownBy(() -> enTransaccion(() -> jdbc.queryForList("SELECT id FROM workspace")))
				.rootCause().hasMessageContaining("No hay contexto de workspace");
	}

	@Test
	void conSoloElUsuarioElWorkspaceSigueSinFijar() {
		ContextoDeSesionActual.fijar(new ContextoDeSesion(UUID.randomUUID(), null));

		assertThatThrownBy(() -> enTransaccion(() -> jdbc.queryForList("SELECT id FROM workspace")))
				.rootCause().hasMessageContaining("No hay contexto de workspace");
	}

	@Test
	void unaTransaccionFallidaNoDejaElContextoNiLaConexionBloqueada() {
		ContextoDeSesionActual.fijar(new ContextoDeSesion(UUID.randomUUID(), UUID.randomUUID()));
		assertThatThrownBy(() -> enTransaccion(() -> jdbc.queryForObject("SELECT 1/0", Integer.class)))
				.isInstanceOf(RuntimeException.class);
		ContextoDeSesionActual.limpiar();

		// Con un pool de una conexión, esto se cuelga si la anterior no volvió
		assertThat(enTransaccion(() -> jdbc.queryForObject(
				"SELECT NULLIF(current_setting('app.workspace_id', true), '')", String.class))).isNull();
	}

	@Test
	void porHttpLasCabecerasFijanElWorkspaceYCadaUnoSoloVeElSuyo() {
		UUID a = crearWorkspace("Agencia A");
		UUID b = crearWorkspace("Agencia B");

		assertThat(nombresVistosPorHttp(a)).containsExactly("Agencia A");
		assertThat(nombresVistosPorHttp(b)).containsExactly("Agencia B");
	}

	@Test
	void porHttpSinCabecerasLaConsultaFalla() {
		HttpStatus estado = (HttpStatus) cliente().get().uri("/sonda/workspaces")
				.exchange((req, res) -> res.getStatusCode());

		assertThat(estado).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
	}

	@Test
	void porHttpUnaCabeceraQueNoEsUuidSeRechazaConElError400() {
		HttpStatus estado = (HttpStatus) cliente().get().uri("/sonda/workspaces")
				.header("X-Workspace-Id", "no-es-un-uuid' OR '1'='1")
				.exchange((req, res) -> res.getStatusCode());

		assertThat(estado).isEqualTo(HttpStatus.BAD_REQUEST);
	}

	private List<String> nombresVistosPorHttp(UUID workspace) {
		return cliente().get().uri("/sonda/workspaces")
				.header("X-Workspace-Id", workspace.toString())
				.retrieve().body(new ParameterizedTypeReference<>() {
				});
	}

	private RestClient cliente() {
		return RestClient.create("http://localhost:" + port);
	}

	private UUID crearWorkspace(String nombre) {
		UUID id = UUID.randomUUID();
		ContextoDeSesionActual.fijar(new ContextoDeSesion(null, id));
		enTransaccion(() -> jdbc.update("INSERT INTO workspace (id, name) VALUES (?, ?)", id, nombre));
		ContextoDeSesionActual.limpiar();
		return id;
	}

	private <T> T enTransaccion(Supplier<T> accion) {
		return new TransactionTemplate(transacciones).execute(estado -> accion.get());
	}

	/** Un endpoint que solo existe en pruebas: lee la tabla con RLS dentro de una transacción. */
	@TestConfiguration(proxyBeanMethods = false)
	static class Sondas {

		@RestController
		static class SondaController {

			private final JdbcTemplate jdbc;

			private final TransactionTemplate tx;

			SondaController(JdbcTemplate jdbc, PlatformTransactionManager transacciones) {
				this.jdbc = jdbc;
				this.tx = new TransactionTemplate(transacciones);
			}

			@GetMapping("/sonda/workspaces")
			List<String> workspaces() {
				return tx.execute(estado -> jdbc.queryForList("SELECT name FROM workspace", String.class));
			}

		}

	}

}
