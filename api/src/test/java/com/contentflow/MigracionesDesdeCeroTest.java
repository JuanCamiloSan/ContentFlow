package com.contentflow;

import static org.assertj.core.api.Assertions.assertThat;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationInfo;
import org.flywaydb.core.api.output.MigrateResult;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.support.PathMatchingResourcePatternResolver;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Las migraciones se aplican sobre una base vacía propia, no sobre la compartida por las demás
 * pruebas, que Spring ya migró al arrancar. Cubre dos fallos distintos: una migración que no
 * corre desde cero, y una que no tolera un segundo arranque (checksum alterado o migración
 * pendiente que reaparece).
 */
class MigracionesDesdeCeroTest {

	@Test
	void aplicaTodasLasMigracionesSobreUnaBaseVaciaYUnReinicioNoCambiaNada() throws Exception {
		int migracionesEnElProyecto = new PathMatchingResourcePatternResolver()
				.getResources("classpath:db/migration/V*__*.sql").length;

		// Con los roles creados, como en Compose: las migraciones hacen GRANT a un rol que ya existe
		try (PostgreSQLContainer baseVacia = RolesDePrueba.nuevaBase()) {
			baseVacia.start();

			MigrateResult primerArranque = flyway(baseVacia).migrate();
			assertThat(primerArranque.success).isTrue();
			assertThat(primerArranque.migrationsExecuted).isEqualTo(migracionesEnElProyecto);

			// Una instancia nueva de Flyway equivale a reiniciar la aplicación
			Flyway reinicio = flyway(baseVacia);
			MigrateResult segundoArranque = reinicio.migrate();
			assertThat(segundoArranque.success).isTrue();
			assertThat(segundoArranque.migrationsExecuted).isZero();
			reinicio.validate();

			assertThat(reinicio.info().applied()).hasSize(migracionesEnElProyecto)
					.allSatisfy(m -> assertThat(m.getState().isApplied()).isTrue())
					.extracting(MigrationInfo::getVersion).doesNotHaveDuplicates();
			assertThat(reinicio.info().pending()).isEmpty();
		}
	}

	private static Flyway flyway(PostgreSQLContainer base) {
		return Flyway.configure()
				.dataSource(base.getJdbcUrl(), RolesDePrueba.DUENO, RolesDePrueba.CLAVE_DUENO)
				.locations("classpath:db/migration")
				.load();
	}

}
