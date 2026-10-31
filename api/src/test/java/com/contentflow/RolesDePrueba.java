package com.contentflow;

import java.nio.file.Path;
import java.util.UUID;

import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

/**
 * Crea los contenedores de prueba con los mismos roles que Docker Compose, usando el mismo script
 * de inicio. Testcontainers arranca por defecto con un superusuario, y con él las pruebas de
 * aislamiento pasarían sin probar nada (ADR 0003).
 * Las contraseñas son aleatorias en cada ejecución: ninguna queda en Git.
 */
public final class RolesDePrueba {

	public static final String DUENO = "contentflow_owner";

	public static final String APLICACION = "contentflow_app";

	public static final String CLAVE_DUENO = UUID.randomUUID().toString();

	public static final String CLAVE_APLICACION = UUID.randomUUID().toString();

	private static final Path SCRIPT = Path.of("..", "infrastructure", "postgres", "init-roles.sh")
			.toAbsolutePath().normalize();

	private RolesDePrueba() {
	}

	/** Un PostgreSQL sin arrancar, con el script de roles listo para correr al inicializarse. */
	public static PostgreSQLContainer nuevaBase() {
		return new PostgreSQLContainer("postgres:16.4")
				.withEnv("CONTENTFLOW_OWNER_PASSWORD", CLAVE_DUENO)
				.withEnv("CONTENTFLOW_APP_PASSWORD", CLAVE_APLICACION)
				.withCopyFileToContainer(MountableFile.forHostPath(SCRIPT, 0755),
						"/docker-entrypoint-initdb.d/init-roles.sh");
	}

}
