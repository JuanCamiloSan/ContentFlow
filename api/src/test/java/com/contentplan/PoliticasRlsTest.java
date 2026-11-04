package com.contentplan;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Guardián del esquema real: ninguna tabla de negocio puede llegar sin su política de aislamiento.
 * Si una migración nueva crea una tabla con {@code workspace_id} y olvida RLS, {@code FORCE} o el
 * {@code WITH CHECK}, esta prueba falla. La plantilla está en docs/plantilla-migracion-rls.sql.
 */
class PoliticasRlsTest extends PostgresIntegrationTest {

	/**
	 * Las únicas tablas con {@code workspace_id} que no siguen la plantilla (ADR 0003, decisión 2): su
	 * política se apoya en {@code app.user_id} y no llevan {@code FORCE}, porque las funciones
	 * {@code SECURITY DEFINER} corren como dueño. Agregar una tabla aquí exige un ADR que lo justifique;
	 * {@code invitation} se suma cuando llegue en C8.
	 */
	private static final List<String> EXCEPCIONES = List.of("membership");

	private static final String EXCLUIDAS = EXCEPCIONES.stream().map(t -> "'" + t + "'")
			.collect(Collectors.joining(", "));

	/** Las tablas con workspace_id, más workspace, que es la raíz y no lo lleva. */
	private static final String TABLAS_AISLADAS = "SELECT table_name FROM information_schema.columns "
			+ "WHERE table_schema = 'public' AND column_name = 'workspace_id' "
			+ "UNION SELECT 'workspace'";

	@Autowired
	private JdbcTemplate jdbc;

	@Test
	void todaTablaConWorkspaceIdTieneRlsActivaYForzada() {
		List<String> sinProteger = jdbc.queryForList(
				"SELECT c.relname FROM pg_class c WHERE c.relname IN (" + TABLAS_AISLADAS + ") "
						+ "AND c.relname NOT IN (" + EXCLUIDAS + ") "
						+ "AND c.relkind = 'r' AND NOT (c.relrowsecurity AND c.relforcerowsecurity)",
				String.class);

		assertThat(sinProteger).as("tablas sin ENABLE y FORCE ROW LEVEL SECURITY").isEmpty();
	}

	@Test
	void todaTablaConWorkspaceIdTienePoliticaConUsingYWithCheck() {
		List<String> sinPolitica = jdbc.queryForList(
				"SELECT t.name FROM (" + TABLAS_AISLADAS.replace("table_name", "table_name AS name")
						+ ") t WHERE t.name NOT IN (" + EXCLUIDAS + ") "
						+ "AND NOT EXISTS (SELECT 1 FROM pg_policies p WHERE p.tablename = t.name "
						+ "AND p.qual LIKE '%current_workspace()%' "
						+ "AND p.with_check LIKE '%current_workspace()%')",
				String.class);

		assertThat(sinPolitica).as("tablas sin política con USING y WITH CHECK sobre current_workspace()")
				.isEmpty();
	}

	@Test
	void lasExcepcionesSiguenConRlsActivaYConUnaPoliticaDeLectura() {
		for (String tabla : EXCEPCIONES) {
			Boolean rls = jdbc.queryForObject("SELECT relrowsecurity FROM pg_class WHERE relname = ?",
					Boolean.class, tabla);
			Integer politicas = jdbc.queryForObject("SELECT count(*) FROM pg_policies WHERE tablename = ?",
					Integer.class, tabla);

			assertThat(rls).as("RLS activa en la excepción " + tabla).isTrue();
			assertThat(politicas).as("políticas de la excepción " + tabla).isPositive();
		}
	}

	@Test
	void laAplicacionNoEscribeEnLasTablasDeIdentidad() {
		// Se crean y cambian por funciones SECURITY DEFINER, nunca por la API (C3). Si una migración
		// vuelve a conceder DML aquí, una ruta con un bug podría darse privilegios a sí misma.
		List<String> conEscritura = jdbc.queryForList(
				"SELECT t.tabla FROM (VALUES ('app_user'), ('membership')) AS t(tabla) "
						+ "WHERE has_table_privilege('contentplan_app', t.tabla, 'INSERT') "
						+ "OR has_table_privilege('contentplan_app', t.tabla, 'UPDATE') "
						+ "OR has_table_privilege('contentplan_app', t.tabla, 'DELETE')",
				String.class);

		assertThat(conEscritura).isEmpty();
	}

	@Test
	void elGuardianEncuentraAlMenosLaTablaRaiz() {
		assertThat(jdbc.queryForList(TABLAS_AISLADAS, String.class)).contains("workspace");
	}

}
