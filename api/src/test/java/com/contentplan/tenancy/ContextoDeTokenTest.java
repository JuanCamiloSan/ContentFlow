package com.contentplan.tenancy;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

import com.contentplan.IdentidadDePrueba;
import com.contentplan.PostgresIntegrationTest;
import com.contentplan.security.TokensDePrueba;

/**
 * C4: el contexto de tenant sale del token y de {@code membership}, no de lo que mande el cliente.
 * Los tokens son simulados (ADR 0004); cada prueba siembra a su gente con un {@code sub} propio porque
 * la base se comparte entre las pruebas.
 */
@Import({ TokensDePrueba.Configuracion.class, ContextoDeTokenTest.Sondas.class })
class ContextoDeTokenTest extends PostgresIntegrationTest {

	private static final String CONTEXTO = "/sonda/contexto";

	@LocalServerPort
	private int port;

	// ---- Quién eres: sale del token

	@Test
	void unSubNuevoCreaALaPersonaUnaSolaVezYLaSiguientePeticionLaReconoce() {
		String sub = nuevoSub();
		RestClient cliente = TokensDePrueba.clienteAutenticado(port, sub);

		assertThat(estado(cliente, "/sonda/sin-workspace")).isEqualTo(HttpStatus.OK);
		assertThat(estado(cliente, "/sonda/sin-workspace")).isEqualTo(HttpStatus.OK);

		assertThat(IdentidadDePrueba.contarPersonas(sub)).isEqualTo(1);
	}

	@Test
	void primerosIngresosSimultaneosDeLaMismaPersonaNoDuplicanNiFallan() throws Exception {
		String sub = nuevoSub();
		RestClient cliente = TokensDePrueba.clienteAutenticado(port, sub);
		int hilos = 8;
		ExecutorService pool = Executors.newFixedThreadPool(hilos);
		CountDownLatch salida = new CountDownLatch(1);
		try {
			List<Future<HttpStatus>> trabajos = new ArrayList<>();
			for (int i = 0; i < hilos; i++) {
				trabajos.add(pool.submit(() -> {
					salida.await();
					return estado(cliente, "/sonda/sin-workspace");
				}));
			}
			salida.countDown();
			for (Future<HttpStatus> trabajo : trabajos) {
				assertThat(trabajo.get(30, TimeUnit.SECONDS)).isEqualTo(HttpStatus.OK);
			}
		}
		finally {
			pool.shutdownNow();
		}

		assertThat(IdentidadDePrueba.contarPersonas(sub)).isEqualTo(1);
	}

	@Test
	void laIdentidadSaleDelTokenYNoDeUnaCabeceraQueElClienteInvente() {
		String subAna = nuevoSub();
		String subBeto = nuevoSub();
		UUID ana = IdentidadDePrueba.persona(subAna);
		UUID beto = IdentidadDePrueba.persona(subBeto);
		UUID w = IdentidadDePrueba.workspace("Agencia de Ana");
		IdentidadDePrueba.unirse(ana, w, "propietario");

		Map<String, String> visto = contexto(TokensDePrueba.clienteAutenticado(port, subAna), null,
				// La cabecera del andamiaje del bloque B: ya no tiene ningún efecto
				Map.of("X-Test-User-Id", beto.toString()));

		assertThat(visto).containsEntry("usuario", ana.toString()).containsEntry("workspace", w.toString());
	}

	@Test
	void unCorreoSinVerificarSeRechazaYNoCreaALaPersona() {
		String sub = nuevoSub();
		String token = TokensDePrueba.firmadoConLlaveValida(
				TokensDePrueba.claims(sub).claim("email_verified", false).build());

		assertThat(estado(TokensDePrueba.clienteConToken(port, token), "/sonda/sin-workspace"))
				.isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(IdentidadDePrueba.contarPersonas(sub)).isZero();
	}

	@Test
	void unTokenSinCorreoOSinLaMarcaDeVerificadoSeRechaza() {
		String sinCorreo = TokensDePrueba.firmadoConLlaveValida(
				TokensDePrueba.claims(nuevoSub()).claim("email", null).build());
		String sinMarca = TokensDePrueba.firmadoConLlaveValida(
				TokensDePrueba.claims(nuevoSub()).claim("email_verified", null).build());

		assertThat(estado(TokensDePrueba.clienteConToken(port, sinCorreo), "/sonda/sin-workspace"))
				.isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(estado(TokensDePrueba.clienteConToken(port, sinMarca), "/sonda/sin-workspace"))
				.isEqualTo(HttpStatus.FORBIDDEN);
	}

	@Test
	void elCorreoSeGuardaEnMinusculasAunqueElTokenLoTraigaEnMayusculas() {
		String sub = nuevoSub();
		String token = TokensDePrueba.firmadoConLlaveValida(
				TokensDePrueba.claims(sub).claim("email", "  Persona." + sub.toUpperCase() + "@Example.COM ").build());

		assertThat(estado(TokensDePrueba.clienteConToken(port, token), "/sonda/sin-workspace"))
				.isEqualTo(HttpStatus.OK);
	}

	// ---- En qué workspace: sale de membership

	@Test
	void conUnaSolaPertenenciaSeUsaEsaSinNecesidadDelEncabezado() {
		String sub = nuevoSub();
		UUID w = IdentidadDePrueba.workspace("Agencia única");
		IdentidadDePrueba.unirse(IdentidadDePrueba.persona(sub), w, "editor");

		Map<String, String> visto = contexto(TokensDePrueba.clienteAutenticado(port, sub), null, Map.of());

		assertThat(visto).containsEntry("workspace", w.toString());
	}

	@Test
	void conVariasPertenenciasSinEncabezadoSeRechazaEnLugarDeElegir() {
		String sub = nuevoSub();
		UUID persona = IdentidadDePrueba.persona(sub);
		IdentidadDePrueba.unirse(persona, IdentidadDePrueba.workspace("Agencia 1"), "propietario");
		IdentidadDePrueba.unirse(persona, IdentidadDePrueba.workspace("Agencia 2"), "editor");

		assertThat(estado(TokensDePrueba.clienteAutenticado(port, sub), CONTEXTO)).isEqualTo(HttpStatus.BAD_REQUEST);
	}

	@Test
	void conVariasPertenenciasElEncabezadoEligeEntreLasSuyas() {
		String sub = nuevoSub();
		UUID persona = IdentidadDePrueba.persona(sub);
		UUID w1 = IdentidadDePrueba.workspace("Agencia 1");
		UUID w2 = IdentidadDePrueba.workspace("Agencia 2");
		IdentidadDePrueba.unirse(persona, w1, "propietario");
		IdentidadDePrueba.unirse(persona, w2, "editor");
		RestClient cliente = TokensDePrueba.clienteAutenticado(port, sub);

		assertThat(contexto(cliente, w1, Map.of())).containsEntry("workspace", w1.toString());
		assertThat(contexto(cliente, w2, Map.of())).containsEntry("workspace", w2.toString());
	}

	@Test
	void unWorkspaceAjenoSeRechazaConElError403() {
		String sub = nuevoSub();
		IdentidadDePrueba.unirse(IdentidadDePrueba.persona(sub), IdentidadDePrueba.workspace("Mi agencia"), "propietario");
		UUID ajeno = IdentidadDePrueba.workspace("Agencia ajena");

		assertThat(estado(TokensDePrueba.clienteAutenticado(port, sub), CONTEXTO, ajeno))
				.isEqualTo(HttpStatus.FORBIDDEN);
	}

	@Test
	void unWorkspaceQueNoExisteDaLaMismaRespuestaQueUnoAjenoYNoDelataCualEs() {
		String sub = nuevoSub();
		IdentidadDePrueba.unirse(IdentidadDePrueba.persona(sub), IdentidadDePrueba.workspace("Mi agencia"), "propietario");

		assertThat(estado(TokensDePrueba.clienteAutenticado(port, sub), CONTEXTO, UUID.randomUUID()))
				.isEqualTo(HttpStatus.FORBIDDEN);
	}

	@Test
	void unEncabezadoQueNoEsUuidSeRechazaConElError400() {
		String sub = nuevoSub();

		HttpStatus estado = (HttpStatus) TokensDePrueba.clienteAutenticado(port, sub).get().uri(CONTEXTO)
				.header("X-Workspace-Id", "no-es-un-uuid").exchange((req, res) -> res.getStatusCode());

		assertThat(estado).isEqualTo(HttpStatus.BAD_REQUEST);
		// Se rechaza antes de tocar la base: ni siquiera se crea a la persona
		assertThat(IdentidadDePrueba.contarPersonas(sub)).isZero();
	}

	@Test
	void elAdministradorDeLaPlataformaNoEntraAUnWorkspaceAjenoHastaQueExistaElRegistroDeAuditoria() {
		// Anotación para E13: cuando audit_log permita registrar cada acceso (ADR 0006), este caso
		// cambia a 200 con su fila de auditoría. Hasta entonces el administrador es una persona más.
		String sub = nuevoSub();
		IdentidadDePrueba.hacerAdministradorDeLaPlataforma(IdentidadDePrueba.persona(sub));
		UUID ajeno = IdentidadDePrueba.workspace("Agencia ajena");

		assertThat(estado(TokensDePrueba.clienteAutenticado(port, sub), CONTEXTO, ajeno))
				.isEqualTo(HttpStatus.FORBIDDEN);
	}

	// ---- Sin workspace: solo lo marcado con @SinWorkspace

	@Test
	void unaPersonaSinWorkspaceSoloLlegaALosEndpointsMarcados() {
		RestClient cliente = TokensDePrueba.clienteAutenticado(port, nuevoSub());

		assertThat(estado(cliente, "/sonda/sin-workspace")).isEqualTo(HttpStatus.OK);
		assertThat(estado(cliente, CONTEXTO)).isEqualTo(HttpStatus.FORBIDDEN);
	}

	@Test
	void unaRutaQueNoExisteTampocoSeAbreAUnaPersonaSinWorkspace() {
		RestClient cliente = TokensDePrueba.clienteAutenticado(port, nuevoSub());

		assertThat(estado(cliente, "/no-existe")).isEqualTo(HttpStatus.FORBIDDEN);
	}

	@Test
	void laSaludSigueAbiertaAunParaUnaPersonaSinWorkspace() {
		assertThat(estado(TokensDePrueba.clienteAutenticado(port, nuevoSub()), "/health")).isEqualTo(HttpStatus.OK);
	}

	// ---- Auxiliares

	private static String nuevoSub() {
		return "persona-" + UUID.randomUUID();
	}

	private static HttpStatus estado(RestClient cliente, String ruta) {
		return (HttpStatus) cliente.get().uri(ruta).exchange((req, res) -> res.getStatusCode());
	}

	private static HttpStatus estado(RestClient cliente, String ruta, UUID workspace) {
		return (HttpStatus) cliente.get().uri(ruta).header("X-Workspace-Id", workspace.toString())
				.exchange((req, res) -> res.getStatusCode());
	}

	private static Map<String, String> contexto(RestClient cliente, UUID workspace, Map<String, String> otras) {
		RestClient.RequestHeadersSpec<?> peticion = cliente.get().uri(CONTEXTO);
		if (workspace != null) {
			peticion = peticion.header("X-Workspace-Id", workspace.toString());
		}
		for (Map.Entry<String, String> cabecera : otras.entrySet()) {
			peticion = peticion.header(cabecera.getKey(), cabecera.getValue());
		}
		return peticion.retrieve().body(new ParameterizedTypeReference<>() {
		});
	}

	/** Endpoints que solo existen en pruebas: uno con contexto de workspace y uno que lo permite no tenerlo. */
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

			/** Lo que la base ve como contexto dentro de la transacción de la petición. */
			@CualquierMiembro
			@GetMapping(CONTEXTO)
			Map<String, String> contexto() {
				return tx.execute(estado -> jdbc.queryForObject(
						"SELECT current_setting('app.user_id', true) AS usuario, "
								+ "current_setting('app.workspace_id', true) AS workspace",
						(rs, fila) -> Map.of("usuario", rs.getString("usuario"), "workspace", rs.getString("workspace"))));
			}

			@SinWorkspace
			@GetMapping("/sonda/sin-workspace")
			String sinWorkspace() {
				return "ok";
			}

		}

	}

}
