package com.contentplan.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpStatus;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

import com.contentplan.IdentidadDePrueba;
import com.contentplan.PostgresIntegrationTest;
import com.contentplan.security.TokensDePrueba;
import com.contentplan.tenancy.CualquierMiembro;

/**
 * C6: el buzón por HTTP, con tokens simulados. Cada prueba arma su propio workspace y su gente porque la
 * base se comparte entre las pruebas.
 */
@Import({ TokensDePrueba.Configuracion.class, NotificacionesTest.Sondas.class })
class NotificacionesTest extends PostgresIntegrationTest {

	private static final ParameterizedTypeReference<List<Map<String, Object>>> LISTA = new ParameterizedTypeReference<>() {
	};

	@LocalServerPort
	private int port;

	@Autowired
	private Notificador notificador;

	/** Una agencia con una propietaria, un editor y un colaborador del cliente, más su token. */
	private record Escenario(UUID workspace, Persona ana, Persona beto, Persona carla) {
	}

	private record Persona(UUID id, RestClient cliente) {
	}

	// ---- Leer el buzón

	@Test
	void cadaPersonaVeSoloSuBuzonAunqueCompartaWorkspace() {
		Escenario e = escenario();
		IdentidadDePrueba.aviso(e.workspace, e.ana.id, "k", "para ana", false, false, 0);
		IdentidadDePrueba.aviso(e.workspace, e.beto.id, "k", "para beto", false, false, 0);

		assertThat(mensajes(e.ana.cliente, "/notificaciones")).containsExactly("para ana");
		assertThat(mensajes(e.beto.cliente, "/notificaciones")).containsExactly("para beto");
		assertThat(mensajes(e.carla.cliente, "/notificaciones")).isEmpty();
	}

	@Test
	void elBuzonDependeDelWorkspaceActivoDeLaPersona() {
		String sub = nuevoSub();
		UUID persona = IdentidadDePrueba.persona(sub);
		UUID w1 = IdentidadDePrueba.workspace("Agencia 1");
		UUID w2 = IdentidadDePrueba.workspace("Agencia 2");
		IdentidadDePrueba.unirse(persona, w1, "propietario");
		IdentidadDePrueba.unirse(persona, w2, "editor");
		IdentidadDePrueba.aviso(w1, persona, "k", "en la agencia 1", false, false, 0);
		IdentidadDePrueba.aviso(w2, persona, "k", "en la agencia 2", false, false, 0);
		RestClient cliente = TokensDePrueba.clienteAutenticado(port, sub);

		assertThat(mensajes(cliente, "/notificaciones", w1)).containsExactly("en la agencia 1");
		assertThat(mensajes(cliente, "/notificaciones", w2)).containsExactly("en la agencia 2");
	}

	@Test
	void losAvisosVienenDelMasRecienteAlMasAntiguo() {
		Escenario e = escenario();
		IdentidadDePrueba.aviso(e.workspace, e.ana.id, "k", "viejo", false, false, 30);
		IdentidadDePrueba.aviso(e.workspace, e.ana.id, "k", "nuevo", false, false, 1);
		IdentidadDePrueba.aviso(e.workspace, e.ana.id, "k", "medio", false, false, 10);

		assertThat(mensajes(e.ana.cliente, "/notificaciones")).containsExactly("nuevo", "medio", "viejo");
	}

	@Test
	void losFiltrosSeparanTodasSinLeerEImportantes() {
		Escenario e = escenario();
		IdentidadDePrueba.aviso(e.workspace, e.ana.id, "k", "importante sin leer", true, false, 3);
		IdentidadDePrueba.aviso(e.workspace, e.ana.id, "k", "normal sin leer", false, false, 2);
		IdentidadDePrueba.aviso(e.workspace, e.ana.id, "k", "importante leído", true, true, 1);

		assertThat(mensajes(e.ana.cliente, "/notificaciones?filtro=todas")).hasSize(3);
		assertThat(mensajes(e.ana.cliente, "/notificaciones?filtro=sin-leer"))
				.containsExactly("normal sin leer", "importante sin leer");
		assertThat(mensajes(e.ana.cliente, "/notificaciones?filtro=importantes"))
				.containsExactly("importante leído", "importante sin leer");
	}

	@Test
	void unFiltroOUnLimiteInvalidoSeRechazaConElError400() {
		Escenario e = escenario();

		for (String ruta : List.of("/notificaciones?filtro=nada", "/notificaciones?filtro=x'%20OR%20'1'='1",
				"/notificaciones?limite=0", "/notificaciones?limite=101", "/notificaciones?limite=abc")) {
			assertThat(estado(e.ana.cliente, ruta)).as(ruta).isEqualTo(HttpStatus.BAD_REQUEST);
		}
	}

	@Test
	void elLimiteRecortaLaLista() {
		Escenario e = escenario();
		for (int i = 0; i < 3; i++) {
			IdentidadDePrueba.aviso(e.workspace, e.ana.id, "k", "aviso " + i, false, false, i);
		}

		assertThat(mensajes(e.ana.cliente, "/notificaciones?limite=2")).containsExactly("aviso 0", "aviso 1");
	}

	@Test
	void laRespuestaNoExponeNiElWorkspaceNiElDestinatario() {
		Escenario e = escenario();
		IdentidadDePrueba.aviso(e.workspace, e.ana.id, "version_rechazada", "Rechazaron la versión", true, false, 0);

		Map<String, Object> aviso = e.ana.cliente.get().uri("/notificaciones").retrieve().body(LISTA).get(0);

		assertThat(aviso).containsOnlyKeys("id", "kind", "message", "important", "createdAt", "readAt");
	}

	// ---- Contador

	@Test
	void elContadorCuentaSoloLosAvisosSinLeerDeLaPersona() {
		Escenario e = escenario();
		IdentidadDePrueba.aviso(e.workspace, e.ana.id, "k", "a", false, false, 0);
		IdentidadDePrueba.aviso(e.workspace, e.ana.id, "k", "b", false, false, 0);
		IdentidadDePrueba.aviso(e.workspace, e.ana.id, "k", "leído", false, true, 0);
		IdentidadDePrueba.aviso(e.workspace, e.beto.id, "k", "ajeno", false, false, 0);

		Map<String, Integer> cantidad = e.ana.cliente.get().uri("/notificaciones/sin-leer/cantidad").retrieve()
				.body(new ParameterizedTypeReference<>() {
				});

		assertThat(cantidad).containsEntry("cantidad", 2);
	}

	// ---- Marcar como leído

	@Test
	void marcarComoLeidoGuardaLaFechaYRepetirNoLaMueve() {
		Escenario e = escenario();
		UUID aviso = IdentidadDePrueba.aviso(e.workspace, e.ana.id, "k", "a", false, false, 0);

		assertThat(post(e.ana.cliente, "/notificaciones/" + aviso + "/leer")).isEqualTo(HttpStatus.NO_CONTENT);
		String primera = IdentidadDePrueba.leidoEn(aviso);
		assertThat(primera).isNotNull();

		assertThat(post(e.ana.cliente, "/notificaciones/" + aviso + "/leer")).isEqualTo(HttpStatus.NO_CONTENT);
		assertThat(IdentidadDePrueba.leidoEn(aviso)).isEqualTo(primera);
	}

	@Test
	void marcarElAvisoDeOtraPersonaDa404YNoLoToca() {
		Escenario e = escenario();
		UUID deBeto = IdentidadDePrueba.aviso(e.workspace, e.beto.id, "k", "para beto", false, false, 0);

		// 404 y no 403: para Ana, el aviso de Beto no existe
		assertThat(post(e.ana.cliente, "/notificaciones/" + deBeto + "/leer")).isEqualTo(HttpStatus.NOT_FOUND);
		assertThat(IdentidadDePrueba.leidoEn(deBeto)).isNull();
	}

	@Test
	void marcarUnAvisoQueNoExisteDa404() {
		Escenario e = escenario();

		assertThat(post(e.ana.cliente, "/notificaciones/" + UUID.randomUUID() + "/leer")).isEqualTo(HttpStatus.NOT_FOUND);
	}

	@Test
	void marcarTodasSoloAfectaLosAvisosDeQuienLoPide() {
		Escenario e = escenario();
		UUID a1 = IdentidadDePrueba.aviso(e.workspace, e.ana.id, "k", "a1", false, false, 0);
		UUID a2 = IdentidadDePrueba.aviso(e.workspace, e.ana.id, "k", "a2", false, false, 0);
		IdentidadDePrueba.aviso(e.workspace, e.ana.id, "k", "ya leído", false, true, 0);
		UUID deBeto = IdentidadDePrueba.aviso(e.workspace, e.beto.id, "k", "de beto", false, false, 0);

		Map<String, Integer> respuesta = e.ana.cliente.post().uri("/notificaciones/leer-todas").retrieve()
				.body(new ParameterizedTypeReference<>() {
				});

		assertThat(respuesta).containsEntry("marcadas", 2);
		assertThat(IdentidadDePrueba.leidoEn(a1)).isNotNull();
		assertThat(IdentidadDePrueba.leidoEn(a2)).isNotNull();
		assertThat(IdentidadDePrueba.leidoEn(deBeto)).isNull();
	}

	// ---- Quién tiene buzón

	@Test
	void tambienLoTienenLosRolesDeCliente() {
		Escenario e = escenario();
		UUID aviso = IdentidadDePrueba.aviso(e.workspace, e.carla.id, "k", "para carla", false, false, 0);

		assertThat(mensajes(e.carla.cliente, "/notificaciones")).containsExactly("para carla");
		assertThat(post(e.carla.cliente, "/notificaciones/" + aviso + "/leer")).isEqualTo(HttpStatus.NO_CONTENT);
	}

	@Test
	void sinTokenSeRechazaYSinWorkspaceNoHayBuzon() {
		assertThat(estado(TokensDePrueba.clienteSinToken(port), "/notificaciones")).isEqualTo(HttpStatus.UNAUTHORIZED);
		assertThat(estado(TokensDePrueba.clienteAutenticado(port, nuevoSub()), "/notificaciones"))
				.isEqualTo(HttpStatus.FORBIDDEN);
	}

	@Test
	void ningunClientePuedeCrearAvisosPorLaApi() {
		Escenario e = escenario();

		for (String metodo : List.of("POST", "PUT", "PATCH", "DELETE")) {
			HttpStatus estado = (HttpStatus) e.ana.cliente.method(org.springframework.http.HttpMethod.valueOf(metodo))
					.uri("/notificaciones").contentType(org.springframework.http.MediaType.APPLICATION_JSON)
					.body("{\"kind\":\"k\",\"message\":\"m\"}").exchange((req, res) -> res.getStatusCode());
			assertThat(estado.is2xxSuccessful()).as(metodo).isFalse();
		}
		assertThat(IdentidadDePrueba.contarAvisos(e.workspace)).isZero();
	}

	// ---- Avisar desde el sistema

	@Test
	void notificarCreaElAvisoParaOtraPersonaEnElWorkspaceDeLaPeticion() {
		Escenario e = escenario();

		assertThat(post(e.ana.cliente, "/sonda/avisar?a=" + e.beto.id)).isEqualTo(HttpStatus.OK);

		assertThat(mensajes(e.beto.cliente, "/notificaciones")).containsExactly("Aviso de prueba");
		assertThat(mensajes(e.ana.cliente, "/notificaciones")).isEmpty();
		assertThat(IdentidadDePrueba.contarAvisos(e.workspace)).isEqualTo(1);
	}

	@Test
	void unAvisoSeDeshaceJuntoConLaTransaccionQueLoOrigino() {
		Escenario e = escenario();

		assertThat(post(e.ana.cliente, "/sonda/avisar-y-fallar?a=" + e.beto.id)).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);

		assertThat(IdentidadDePrueba.contarAvisos(e.workspace)).isZero();
	}

	@Test
	void notificarSinUnaTransaccionEnCursoFallaEnLugarDeEscribirSinContexto() {
		assertThatThrownBy(() -> notificador.notificar(new Aviso(UUID.randomUUID(), "k", "m", false)))
				.isInstanceOf(IllegalTransactionStateException.class);
	}

	// ---- Auxiliares

	private Escenario escenario() {
		UUID workspace = IdentidadDePrueba.workspace("Agencia");
		return new Escenario(workspace, miembro(workspace, "propietario"), miembro(workspace, "editor"),
				miembro(workspace, "colaborador"));
	}

	private Persona miembro(UUID workspace, String rol) {
		String sub = nuevoSub();
		UUID id = IdentidadDePrueba.persona(sub);
		IdentidadDePrueba.unirse(id, workspace, rol);
		return new Persona(id, TokensDePrueba.clienteAutenticado(port, sub));
	}

	private static String nuevoSub() {
		return "persona-" + UUID.randomUUID();
	}

	private static List<String> mensajes(RestClient cliente, String ruta) {
		return cliente.get().uri(ruta).retrieve().body(LISTA).stream().map(a -> (String) a.get("message")).toList();
	}

	private static List<String> mensajes(RestClient cliente, String ruta, UUID workspace) {
		return cliente.get().uri(ruta).header("X-Workspace-Id", workspace.toString()).retrieve().body(LISTA).stream()
				.map(a -> (String) a.get("message")).toList();
	}

	private static HttpStatus estado(RestClient cliente, String ruta) {
		return (HttpStatus) cliente.get().uri(ruta).exchange((req, res) -> res.getStatusCode());
	}

	private static HttpStatus post(RestClient cliente, String ruta) {
		return (HttpStatus) cliente.post().uri(ruta).exchange((req, res) -> res.getStatusCode());
	}

	/** Endpoints que solo existen en pruebas y hacen de módulos que avisan al ocurrir un evento. */
	@TestConfiguration(proxyBeanMethods = false)
	static class Sondas {

		@RestController
		static class SondaAvisos {

			private final Notificador notificador;

			private final TransactionTemplate tx;

			SondaAvisos(Notificador notificador, PlatformTransactionManager transacciones) {
				this.notificador = notificador;
				this.tx = new TransactionTemplate(transacciones);
			}

			@CualquierMiembro
			@PostMapping("/sonda/avisar")
			String avisar(@RequestParam UUID a) {
				tx.executeWithoutResult(estado -> notificador.notificar(new Aviso(a, "prueba", "Aviso de prueba", true)));
				return "ok";
			}

			/** El evento falla después de avisar: el aviso no debe quedar. */
			@CualquierMiembro
			@PostMapping("/sonda/avisar-y-fallar")
			String avisarYFallar(@RequestParam UUID a) {
				tx.executeWithoutResult(estado -> {
					notificador.notificar(new Aviso(a, "prueba", "Aviso de prueba", true));
					throw new IllegalStateException("el evento falló");
				});
				return "ok";
			}

		}

	}

}
