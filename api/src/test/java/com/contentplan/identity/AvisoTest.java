package com.contentplan.identity;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;

/** C6: lo que un aviso debe cumplir antes de llegar a la base (la base lo vuelve a exigir con CHECK). */
class AvisoTest {

	private static final UUID PERSONA = UUID.randomUUID();

	@Test
	void unAvisoValidoSeCrea() {
		Aviso aviso = new Aviso(PERSONA, "version_rechazada", "Rechazaron la versión 2", true);

		assertThat(aviso.kind()).isEqualTo("version_rechazada");
		assertThat(aviso.importante()).isTrue();
	}

	@Test
	void sinDestinatarioSeRechaza() {
		assertThatThrownBy(() -> new Aviso(null, "k", "m", false)).isInstanceOf(IllegalArgumentException.class);
	}

	@Test
	void elKindDebeIrEnMinusculasConGuionesBajosYEmpezarPorLetra() {
		for (String invalido : new String[] { null, "", "Mayusculas", "con espacio", "con-guion", "1empieza", "_raro",
				"a".repeat(65), "version' OR '1'='1" }) {
			assertThatThrownBy(() -> new Aviso(PERSONA, invalido, "m", false)).as(String.valueOf(invalido))
					.isInstanceOf(IllegalArgumentException.class);
		}
	}

	@Test
	void elMensajeNoPuedeEstarVacioNiPasarDe500Caracteres() {
		for (String invalido : new String[] { null, "", "   " }) {
			assertThatThrownBy(() -> new Aviso(PERSONA, "k", invalido, false)).isInstanceOf(IllegalArgumentException.class);
		}
		assertThatThrownBy(() -> new Aviso(PERSONA, "k", "x".repeat(501), false))
				.isInstanceOf(IllegalArgumentException.class);
		assertThat(new Aviso(PERSONA, "k", "x".repeat(500), false).mensaje()).hasSize(500);
	}

}
