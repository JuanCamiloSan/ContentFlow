package com.contentflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Guardián del esquema real: ninguna tabla de negocio puede llegar sin su política de aislamiento.
 * Si una migración nueva crea una tabla con {@code workspace_id} y olvida RLS, {@code FORCE} o el
 * {@code WITH CHECK}, esta prueba falla. La plantilla está en docs/plantilla-migracion-rls.sql.
 */
class PoliticasRlsTest extends PostgresIntegrationTest {

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
						+ "AND c.relkind = 'r' AND NOT (c.relrowsecurity AND c.relforcerowsecurity)",
				String.class);

		assertThat(sinProteger).as("tablas sin ENABLE y FORCE ROW LEVEL SECURITY").isEmpty();
	}

	@Test
	void todaTablaConWorkspaceIdTienePoliticaConUsingYWithCheck() {
		List<String> sinPolitica = jdbc.queryForList(
				"SELECT t.name FROM (" + TABLAS_AISLADAS.replace("table_name", "table_name AS name")
						+ ") t WHERE NOT EXISTS (SELECT 1 FROM pg_policies p WHERE p.tablename = t.name "
						+ "AND p.qual LIKE '%current_workspace()%' "
						+ "AND p.with_check LIKE '%current_workspace()%')",
				String.class);

		assertThat(sinPolitica).as("tablas sin política con USING y WITH CHECK sobre current_workspace()")
				.isEmpty();
	}

	@Test
	void elGuardianEncuentraAlMenosLaTablaRaiz() {
		assertThat(jdbc.queryForList(TABLAS_AISLADAS, String.class)).contains("workspace");
	}

}
