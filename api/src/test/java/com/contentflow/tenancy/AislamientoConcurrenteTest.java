package com.contentflow.tenancy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

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
 * B8: el bug que de verdad filtra datos entre clientes no aparece con una petición a la vez, sino
 * cuando el pool reutiliza una conexión y el workspace anterior sobrevive. Aquí 16 hilos se
 * reparten 4 conexiones y mezclan transacciones del workspace A, del B y sin contexto.
 *
 * Si el contexto sobreviviera a una transacción, una "sin contexto" que caiga en una conexión
 * recién usada por A o B tendría éxito en lugar de fallar, y alguien vería filas ajenas.
 */
@TestPropertySource(properties = { "contentflow.contexto-de-prueba.habilitado=true",
		"spring.datasource.hikari.maximum-pool-size=4" })
@Import(AislamientoConcurrenteTest.Sondas.class)
class AislamientoConcurrenteTest extends PostgresIntegrationTest {

	private static final int HILOS = 16;

	private static final int REPETICIONES = 60;

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private PlatformTransactionManager transacciones;

	@LocalServerPort
	private int port;

	@Test
	void transaccionesSimultaneasNuncaVenElWorkspaceDeOtraNiArrastranElAnterior() throws Exception {
		UUID a = crearWorkspace("Agencia A");
		UUID b = crearWorkspace("Agencia B");
		Queue<String> violaciones = new ConcurrentLinkedQueue<>();

		correrEnParalelo(indice -> {
			for (int i = 0; i < REPETICIONES; i++) {
				switch ((indice + i) % 3) {
					case 0 -> verificarVe(a, "Agencia A", violaciones);
					case 1 -> verificarVe(b, "Agencia B", violaciones);
					default -> verificarFalla(violaciones);
				}
			}
		});

		assertThat(violaciones).isEmpty();
	}

	@Test
	void peticionesHttpSimultaneasNoSeContaminanEntreHilosDelServidor() throws Exception {
		UUID a = crearWorkspace("Agencia A");
		UUID b = crearWorkspace("Agencia B");
		RestClient cliente = RestClient.create("http://localhost:" + port);
		Queue<String> violaciones = new ConcurrentLinkedQueue<>();

		correrEnParalelo(indice -> {
			for (int i = 0; i < REPETICIONES / 2; i++) {
				switch ((indice + i) % 3) {
					case 0 -> verificarHttp(cliente, a, "Agencia A", violaciones);
					case 1 -> verificarHttp(cliente, b, "Agencia B", violaciones);
					default -> verificarHttpSinCabeceras(cliente, violaciones);
				}
			}
		});

		assertThat(violaciones).isEmpty();
	}

	private void verificarVe(UUID workspace, String esperado, Queue<String> violaciones) {
		try {
			List<String> vistos = enTransaccionDe(workspace,
					() -> jdbc.queryForList("SELECT name FROM workspace", String.class));
			if (!vistos.equals(List.of(esperado))) {
				violaciones.add("Con " + esperado + " se vio " + vistos);
			}
		}
		catch (RuntimeException ex) {
			violaciones.add("Con " + esperado + " falló: " + ex.getMessage());
		}
	}

	private void verificarFalla(Queue<String> violaciones) {
		try {
			List<String> vistos = enTransaccionDe(null,
					() -> jdbc.queryForList("SELECT name FROM workspace", String.class));
			violaciones.add("Sin contexto la consulta tuvo éxito y vio " + vistos);
		}
		catch (RuntimeException esperada) {
			Throwable causa = esperada;
			while (causa.getCause() != null) {
				causa = causa.getCause();
			}
			if (!String.valueOf(causa.getMessage()).contains("No hay contexto de workspace")) {
				violaciones.add("Sin contexto falló por otra razón: " + causa.getMessage());
			}
		}
	}

	private static void verificarHttp(RestClient cliente, UUID workspace, String esperado,
			Queue<String> violaciones) {
		try {
			List<String> vistos = cliente.get().uri("/sonda/workspaces")
					.header("X-Workspace-Id", workspace.toString())
					.retrieve().body(new ParameterizedTypeReference<>() {
					});
			if (!List.of(esperado).equals(vistos)) {
				violaciones.add("Por HTTP con " + esperado + " se vio " + vistos);
			}
		}
		catch (RuntimeException ex) {
			violaciones.add("Por HTTP con " + esperado + " falló: " + ex.getMessage());
		}
	}

	private static void verificarHttpSinCabeceras(RestClient cliente, Queue<String> violaciones) {
		HttpStatus estado = (HttpStatus) cliente.get().uri("/sonda/workspaces")
				.exchange((req, res) -> res.getStatusCode());
		if (estado != HttpStatus.INTERNAL_SERVER_ERROR) {
			violaciones.add("Por HTTP sin cabeceras se esperaba 500 y llegó " + estado);
		}
	}

	/** Todos los hilos arrancan a la vez, para que compitan por las pocas conexiones del pool. */
	private static void correrEnParalelo(java.util.function.IntConsumer tarea) throws Exception {
		ExecutorService hilos = Executors.newFixedThreadPool(HILOS);
		CountDownLatch salida = new CountDownLatch(1);
		try {
			List<Future<?>> trabajos = new ArrayList<>();
			for (int h = 0; h < HILOS; h++) {
				int indice = h;
				trabajos.add(hilos.submit(() -> {
					salida.await();
					tarea.accept(indice);
					return null;
				}));
			}
			salida.countDown();
			for (Future<?> trabajo : trabajos) {
				trabajo.get(60, TimeUnit.SECONDS);
			}
		}
		finally {
			hilos.shutdownNow();
		}
	}

	private UUID crearWorkspace(String nombre) {
		UUID id = UUID.randomUUID();
		enTransaccionDe(id, () -> jdbc.update("INSERT INTO workspace (id, name) VALUES (?, ?)", id, nombre));
		return id;
	}

	/** Fija el contexto del hilo (o ninguno si es null), abre la transacción y lo limpia al salir. */
	private <T> T enTransaccionDe(UUID workspace, Supplier<T> accion) {
		if (workspace != null) {
			ContextoDeSesionActual.fijar(new ContextoDeSesion(null, workspace));
		}
		try {
			return new TransactionTemplate(transacciones).execute(estado -> {
				// Retiene la conexión un instante para forzar el entrelazado entre hilos
				jdbc.queryForObject("SELECT pg_sleep(0.002)::text", String.class);
				return accion.get();
			});
		}
		finally {
			ContextoDeSesionActual.limpiar();
		}
	}

	/** El mismo endpoint de sonda que usa ContextoDeSesionTest: lee la tabla con RLS en una transacción. */
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
