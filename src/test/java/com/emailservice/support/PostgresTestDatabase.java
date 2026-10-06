package com.emailservice.support;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;

import org.flywaydb.core.Flyway;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import org.testcontainers.utility.MountableFile;

/**
 * One PostgreSQL 18 container per test JVM, initialised with scripts/init-db-roles.sql so tests
 * run against the same roles, grants and RLS policies as production.
 */
public final class PostgresTestDatabase {

	public enum Role {
		OWNER("email_owner", "test-owner-password"),
		APP("email_app", "test-app-password"),
		SYSTEM("email_system", "test-system-password");

		public final String username;
		public final String password;

		Role(String username, String password) {
			this.username = username;
			this.password = password;
		}
	}

	private static final PostgreSQLContainer CONTAINER = new PostgreSQLContainer(
			DockerImageName.parse("postgres:18-alpine"))
		.withEnv("DB_OWNER_PASSWORD", Role.OWNER.password)
		.withEnv("DB_APP_PASSWORD", Role.APP.password)
		.withEnv("DB_SYSTEM_PASSWORD", Role.SYSTEM.password)
		.withCopyFileToContainer(MountableFile.forHostPath("scripts/init-db-roles.sql"),
				"/docker-entrypoint-initdb.d/10-init-db-roles.sql");

	private static boolean migrated;

	private PostgresTestDatabase() {
	}

	public static synchronized String jdbcUrl() {
		if (!CONTAINER.isRunning()) {
			CONTAINER.start();
		}
		return CONTAINER.getJdbcUrl();
	}

	public static synchronized void migrate() {
		if (!migrated) {
			Flyway.configure()
				.dataSource(jdbcUrl(), Role.OWNER.username, Role.OWNER.password)
				.load()
				.migrate();
			migrated = true;
		}
	}

	public static Connection connect(Role role) throws SQLException {
		return DriverManager.getConnection(jdbcUrl(), role.username, role.password);
	}

}
