package com.contentplan.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

import com.contentplan.IdentidadDePrueba;
import com.contentplan.PostgresIntegrationTest;
import com.contentplan.security.TokensDePrueba;

/**
 * C5: el rol de la persona en el workspace activo decide qué acciones puede hacer, y lo no declarado queda
 * cerrado para todos, incluido el propietario. Las sondas hacen de endpoints de negocio, que no llegan
 * hasta el bloque E; las reglas concretas de aprobar y comentar se prueban allí y en C10.
 */
@Import({ TokensDePrueba.Configuracion.class, RolesTest.Sondas.class })
class RolesTest extends PostgresIntegrationTest {

	@LocalServerPort
	private int port;

	// ---- Qué roles pasan por cada declaración

	@Test
	void cadaEndpointDejaPasarSoloLosRolesQueDeclara() {
		Map<String, Set<Rol>> esperado = Map.of(
				"/sonda/solo-propietario", EnumSet.of(Rol.PROPIETARIO),
				"/sonda/agencia", EnumSet.of(Rol.PROPIETARIO, Rol.EDITOR),
				"/sonda/decide", EnumSet.of(Rol.PROPIETARIO, Rol.APROBADOR),
				"/sonda/cliente", EnumSet.of(Rol.APROBADOR, Rol.COLABORADOR),
				"/sonda/todos", EnumSet.allOf(Rol.class),
				"/sonda/clase-todos", EnumSet.allOf(Rol.class),
				"/sonda/clase-con-metodo-mas-estricto", EnumSet.of(Rol.PROPIETARIO));

		for (Rol rol : Rol.values()) {
			RestClient cliente = clienteConRol(rol);
			for (Map.Entry<String, Set<Rol>> ruta : esperado.entrySet()) {
				HttpStatus esperada = ruta.getValue().contains(rol) ? HttpStatus.OK : HttpStatus.FORBIDDEN;
				assertThat(estado(cliente, ruta.getKey())).as("%s en %s", rol, ruta.getKey()).isEqualTo(esperada);
			}
		}
	}

	@Test
	void unEndpointSinDeclararNingunRolQuedaCerradoParaTodosTambienParaElPropietario() {
		for (Rol rol : Rol.values()) {
			assertThat(estado(clienteConRol(rol), "/sonda/sin-declarar")).as(rol.name()).isEqualTo(HttpStatus.FORBIDDEN);
		}
	}

	@Test
	void unEndpointSinRolesEnLaAnotacionNoDejaPasarANadie() {
		for (Rol rol : Rol.values()) {
			assertThat(estado(clienteConRol(rol), "/sonda/lista-vacia")).as(rol.name()).isEqualTo(HttpStatus.FORBIDDEN);
		}
	}

	@Test
	void unaRutaQueNoExisteTampocoSeAbreAUnMiembro() {
		assertThat(estado(clienteConRol(Rol.PROPIETARIO), "/no-existe")).isEqualTo(HttpStatus.FORBIDDEN);
	}

	// ---- De dónde sale el rol

	@Test
	void elRolEsElDelWorkspaceActivoYNoElMasAltoQueTengaLaPersona() {
		String sub = nuevoSub();
		UUID persona = IdentidadDePrueba.persona(sub);
		UUID suAgencia = IdentidadDePrueba.workspace("Mi agencia");
		UUID otraAgencia = IdentidadDePrueba.workspace("Agencia donde solo comento");
		IdentidadDePrueba.unirse(persona, suAgencia, "propietario");
		IdentidadDePrueba.unirse(persona, otraAgencia, "colaborador");
		RestClient cliente = TokensDePrueba.clienteAutenticado(port, sub);

		assertThat(estado(cliente, "/sonda/solo-propietario", suAgencia)).isEqualTo(HttpStatus.OK);
		// Mismo token, otro encabezado: ser propietario aquí no sirve allá
		assertThat(estado(cliente, "/sonda/solo-propietario", otraAgencia)).isEqualTo(HttpStatus.FORBIDDEN);
		assertThat(estado(cliente, "/sonda/todos", otraAgencia)).isEqualTo(HttpStatus.OK);
	}

	@Test
	void unRolQueVengaEnElTokenOEnUnaCabeceraNoCuenta() {
		String sub = nuevoSub();
		IdentidadDePrueba.unirse(IdentidadDePrueba.persona(sub), IdentidadDePrueba.workspace("Agencia"), "colaborador");
		String token = TokensDePrueba.firmadoConLlaveValida(
				TokensDePrueba.claims(sub).claim("role", "propietario").claim("roles", java.util.List.of("propietario"))
						.build());

		HttpStatus estado = (HttpStatus) TokensDePrueba.clienteConToken(port, token).get().uri("/sonda/solo-propietario")
				.header("X-Rol", "propietario").header("X-Role", "propietario")
				.exchange((req, res) -> res.getStatusCode());

		assertThat(estado).isEqualTo(HttpStatus.FORBIDDEN);
	}

	@Test
	void unCambioDeRolEnLaBaseSurteEfectoEnLaSiguientePeticion() {
		String sub = nuevoSub();
		UUID persona = IdentidadDePrueba.persona(sub);
		UUID workspace = IdentidadDePrueba.workspace("Agencia");
		IdentidadDePrueba.unirse(persona, workspace, "editor");
		RestClient cliente = TokensDePrueba.clienteAutenticado(port, sub);
		assertThat(estado(cliente, "/sonda/agencia")).isEqualTo(HttpStatus.OK);

		IdentidadDePrueba.cambiarRol(persona, workspace, "colaborador");

		// Sin caché (ADR 0003): quitarle el rol a alguien tiene efecto de inmediato
		assertThat(estado(cliente, "/sonda/agencia")).isEqualTo(HttpStatus.FORBIDDEN);
	}

	// ---- Los roles en el código y en la base

	@Test
	void losRolesDelCodigoSonExactamenteLosQueAceptaLaBase() {
		Set<String> delCodigo = new java.util.TreeSet<>();
		for (Rol rol : Rol.values()) {
			delCodigo.add(rol.valorEnBase());
		}

		assertThat(delCodigo).containsExactlyInAnyOrder("propietario", "editor", "aprobador", "colaborador");
		// Y cada uno lo acepta el CHECK de membership: sembrar uno por rol no puede fallar
		UUID workspace = IdentidadDePrueba.workspace("Agencia");
		for (Rol rol : Rol.values()) {
			IdentidadDePrueba.unirse(IdentidadDePrueba.persona(nuevoSub()), workspace, rol.valorEnBase());
		}
	}

	@Test
	void soloElPropietarioYElEditorSonDeAgencia() {
		assertThat(EnumSet.allOf(Rol.class).stream().filter(Rol::esDeAgencia))
				.containsExactlyInAnyOrder(Rol.PROPIETARIO, Rol.EDITOR);
	}

	@Test
	void unRolDesconocidoEnLaBaseFallaEnLugarDeConvertirseEnOtro() {
		assertThatThrownBy(() -> Rol.desdeBase("superusuario")).isInstanceOf(IllegalStateException.class);
		assertThatThrownBy(() -> Rol.desdeBase(null)).isInstanceOf(IllegalStateException.class);
	}

	// ---- Auxiliares

	private RestClient clienteConRol(Rol rol) {
		String sub = nuevoSub();
		IdentidadDePrueba.unirse(IdentidadDePrueba.persona(sub), IdentidadDePrueba.workspace("Agencia " + rol),
				rol.valorEnBase());
		return TokensDePrueba.clienteAutenticado(port, sub);
	}

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

	/** Endpoints que solo existen en pruebas, uno por cada forma de declarar (o no) los roles. */
	@TestConfiguration(proxyBeanMethods = false)
	static class Sondas {

		@RestController
		static class SondaController {

			@RequiereRol(Rol.PROPIETARIO)
			@GetMapping("/sonda/solo-propietario")
			String soloPropietario() {
				return "ok";
			}

			@RequiereRol({ Rol.PROPIETARIO, Rol.EDITOR })
			@GetMapping("/sonda/agencia")
			String agencia() {
				return "ok";
			}

			@RequiereRol({ Rol.PROPIETARIO, Rol.APROBADOR })
			@GetMapping("/sonda/decide")
			String decide() {
				return "ok";
			}

			@RequiereRol({ Rol.APROBADOR, Rol.COLABORADOR })
			@GetMapping("/sonda/cliente")
			String cliente() {
				return "ok";
			}

			@CualquierMiembro
			@GetMapping("/sonda/todos")
			String todos() {
				return "ok";
			}

			/** Olvidó declarar: el caso que la opción B vuelve visible. */
			@GetMapping("/sonda/sin-declarar")
			String sinDeclarar() {
				return "ok";
			}

			@RequiereRol({})
			@GetMapping("/sonda/lista-vacia")
			String listaVacia() {
				return "ok";
			}

		}

		/** La declaración de la clase vale para sus métodos, y la del método manda sobre ella. */
		@RestController
		@CualquierMiembro
		static class SondaDeClaseController {

			@GetMapping("/sonda/clase-todos")
			String hereda() {
				return "ok";
			}

			@RequiereRol(Rol.PROPIETARIO)
			@GetMapping("/sonda/clase-con-metodo-mas-estricto")
			String masEstricto() {
				return "ok";
			}

		}

	}

}
