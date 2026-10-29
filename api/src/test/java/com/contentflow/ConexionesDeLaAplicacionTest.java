package com.contentflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * B10: la aplicación se conecta con el rol de aplicación y Flyway con el dueño. Si el
 * {@code DataSource} de la API fuera el superusuario o el dueño, RLS no la alcanzaría y las
 * pruebas de aislamiento de B4 a B8 pasarían en falso.
 */
class ConexionesDeLaAplicacionTest extends PostgresIntegrationTest {

	@Autowired
	private JdbcTemplate jdbc;

	@Test
	void elDataSourceDeLaApiEsElRolDeAplicacionSinPrivilegiosParaSaltarseRls() {
		Map<String, Object> rol = jdbc.queryForMap(
				"SELECT r.rolname, r.rolsuper, r.rolbypassrls FROM pg_roles r WHERE r.rolname = current_user");

		assertThat(rol).containsEntry("rolname", RolesDePrueba.APLICACION)
				.containsEntry("rolsuper", false)
				.containsEntry("rolbypassrls", false);
	}

	@Test
	void flywayMigroComoDuenoYLaApiNoEsDuenaDeLasTablas() {
		String dueno = jdbc.queryForObject(
				"SELECT tableowner FROM pg_tables WHERE tablename = 'workspace'", String.class);

		assertThat(dueno).isEqualTo(RolesDePrueba.DUENO);
	}

	@Test
	void laApiNoPuedeDesactivarRlsNiAlterarElEsquema() {
		assertThatThrownBy(() -> jdbc.execute("ALTER TABLE workspace DISABLE ROW LEVEL SECURITY"))
				.isInstanceOf(DataAccessException.class).hasRootCauseMessage("ERROR: must be owner of table workspace");
		assertThatThrownBy(() -> jdbc.execute("CREATE TABLE intruso (id int)"))
				.isInstanceOf(DataAccessException.class).rootCause().hasMessageContaining("permission denied");
	}

}
