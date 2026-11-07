package com.contentplan.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.contentplan.IdentidadDePrueba;
import com.contentplan.PostgresIntegrationTest;
import com.contentplan.tenancy.ContextoDeSesion;
import com.contentplan.tenancy.ContextoDeSesionActual;

/**
 * C6: lo que la base, y no el código, impide en {@code notification}. Corre con el rol de aplicación y
 * fijando el contexto como lo hace la API; con otro rol estas pruebas no probarían nada (ADR 0003).
 */
class NotificationRlsTest extends PostgresIntegrationTest {

	@Autowired
	private JdbcTemplate jdbc;

	@Autowired
	private PlatformTransactionManager transacciones;

	private final UUID workspace = IdentidadDePrueba.workspace("Agencia");

	private final UUID ana = IdentidadDePrueba.persona("ana-" + UUID.randomUUID());

	private final UUID beto = IdentidadDePrueba.persona("beto-" + UUID.randomUUID());

	@AfterEach
	void limpiar() {
		ContextoDeSesionActual.limpiar();
	}

	// ---- Qué ve cada quien

	@Test
	void cadaPersonaVeSoloLosAvisosDondeEsDestinataria() {
		UUID deAna = IdentidadDePrueba.aviso(workspace, ana, "k", "para ana", false, false, 0);
		UUID deBeto = IdentidadDePrueba.aviso(workspace, beto, "k", "para beto", false, false, 0);

		assertThat(ids(ana, workspace)).containsExactly(deAna);
		assertThat(ids(beto, workspace)).containsExactly(deBeto);
	}

	@Test
	void elAvisoDeOtraPersonaNoApareceNiPorIdDirecto() {
		UUID deBeto = IdentidadDePrueba.aviso(workspace, beto, "k", "para beto", false, false, 0);

		List<UUID> vistos = en(ana, workspace, () -> jdbc.queryForList(
				"SELECT id FROM notification WHERE id = ?", UUID.class, deBeto));

		assertThat(vistos).isEmpty();
	}

	@Test
	void elAvisoDeOtroWorkspaceNoApareceAunqueSeaParaLaMismaPersona() {
		UUID otro = IdentidadDePrueba.workspace("Otra agencia");
		IdentidadDePrueba.aviso(otro, ana, "k", "en otro workspace", false, false, 0);

		assertThat(ids(ana, workspace)).isEmpty();
		assertThat(ids(ana, otro)).hasSize(1);
	}

	@Test
	void sinContextoDeUsuarioOdeWorkspaceLaLecturaFallaEnLugarDeDevolverCeroFilas() {
		assertThatThrownBy(() -> en(null, workspace, () -> jdbc.queryForList("SELECT id FROM notification", UUID.class)))
				.rootCause().hasMessageContaining("No hay contexto de usuario");
		assertThatThrownBy(() -> en(ana, null, () -> jdbc.queryForList("SELECT id FROM notification", UUID.class)))
				.rootCause().hasMessageContaining("No hay contexto de workspace");
	}

	// ---- Qué puede escribir la aplicación

	@Test
	void elSistemaPuedeAvisarAOtraPersonaDelMismoWorkspace() {
		en(ana, workspace, () -> jdbc.update("INSERT INTO notification (workspace_id, user_id, kind, message) "
				+ "VALUES (current_workspace(), ?, 'prueba', 'hola')", beto));

		assertThat(ids(beto, workspace)).hasSize(1);
		assertThat(ids(ana, workspace)).isEmpty();
	}

	@Test
	void noSePuedeEscribirUnAvisoEnOtroWorkspace() {
		UUID otro = IdentidadDePrueba.workspace("Otra agencia");

		assertThatThrownBy(() -> en(ana, workspace, () -> jdbc.update(
				"INSERT INTO notification (workspace_id, user_id, kind, message) VALUES (?, ?, 'prueba', 'hola')",
				otro, ana))).rootCause().hasMessageContaining("row-level security");
	}

	@Test
	void laAplicacionSoloPuedeCambiarLaFechaDeLectura() {
		UUID aviso = IdentidadDePrueba.aviso(workspace, ana, "k", "original", false, false, 0);

		// Reescribir el texto, el destinatario, el workspace o la importancia sería falsificar un aviso
		for (String cambio : List.of("message = 'reescrito'", "user_id = '" + beto + "'",
				"workspace_id = '" + UUID.randomUUID() + "'", "important = true", "kind = 'otro'",
				"created_at = now()")) {
			assertThatThrownBy(() -> en(ana, workspace,
					() -> jdbc.update("UPDATE notification SET " + cambio + " WHERE id = ?", aviso)))
					.as(cambio).rootCause().hasMessageContaining("permission denied");
		}
		assertThat(IdentidadDePrueba.textoDelAviso(aviso)).isEqualTo("original");

		assertThat(en(ana, workspace, () -> jdbc.update(
				"UPDATE notification SET read_at = now(), updated_at = now() WHERE id = ?", aviso))).isEqualTo(1);
		assertThat(IdentidadDePrueba.leidoEn(aviso)).isNotNull();
	}

	@Test
	void unaPersonaNoPuedeMarcarComoLeidoElAvisoDeOtra() {
		UUID deBeto = IdentidadDePrueba.aviso(workspace, beto, "k", "para beto", false, false, 0);

		int filas = en(ana, workspace, () -> jdbc.update("UPDATE notification SET read_at = now() WHERE id = ?", deBeto));

		assertThat(filas).isZero();
		assertThat(IdentidadDePrueba.leidoEn(deBeto)).isNull();
	}

	@Test
	void laAplicacionNoPuedeBorrarAvisos() {
		UUID aviso = IdentidadDePrueba.aviso(workspace, ana, "k", "para ana", false, false, 0);

		assertThatThrownBy(() -> en(ana, workspace, () -> jdbc.update("DELETE FROM notification WHERE id = ?", aviso)))
				.rootCause().hasMessageContaining("permission denied");
		assertThat(IdentidadDePrueba.textoDelAviso(aviso)).isEqualTo("para ana");
	}

	// ---- Qué exige la tabla aunque el código falle

	@Test
	void elKindElMensajeYElCanalSeValidanEnLaBase() {
		for (String[] invalido : new String[][] { { "Mayusculas", "m", "app" }, { "con espacio", "m", "app" },
				{ "k", "   ", "app" }, { "k", "x".repeat(501), "app" }, { "k", "m", "whatsapp" } }) {
			assertThatThrownBy(() -> en(ana, workspace, () -> jdbc.update(
					"INSERT INTO notification (workspace_id, user_id, kind, message, channel) "
							+ "VALUES (current_workspace(), ?, ?, ?, ?)", ana, invalido[0], invalido[1], invalido[2])))
					.as(String.join("|", invalido)).rootCause().hasMessageContaining("check constraint");
		}
	}

	// ---- Auxiliares

	private List<UUID> ids(UUID usuario, UUID espacio) {
		return en(usuario, espacio, () -> jdbc.queryForList("SELECT id FROM notification", UUID.class));
	}

	/** Fija el contexto como la API y corre en una transacción, que es donde se aplica. */
	private <T> T en(UUID usuario, UUID espacio, Supplier<T> accion) {
		ContextoDeSesionActual.fijar(new ContextoDeSesion(usuario, espacio));
		try {
			return new TransactionTemplate(transacciones).execute(estado -> accion.get());
		}
		finally {
			ContextoDeSesionActual.limpiar();
		}
	}

}
