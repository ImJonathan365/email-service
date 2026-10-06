package com.emailservice.common.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Environment configuration (docs/05 §6), validated at startup so a misconfigured instance never
 * boots (NFR-09). Error messages name the missing variables, never their values.
 */
@ConfigurationProperties("app")
public record AppProperties(AppEnv env, AppRole role, Db db) {

	public AppProperties {
		Objects.requireNonNull(env, "APP_ENV is required");
		Objects.requireNonNull(role, "APP_ROLE is required");
		Objects.requireNonNull(db, "DB_* configuration is required");

		List<String> missing = new ArrayList<>();
		if (isBlank(db.url())) {
			missing.add("DB_URL");
		}
		if (role != AppRole.MIGRATE) {
			Credentials.collectMissing(db.app(), "DB_APP", missing);
			Credentials.collectMissing(db.system(), "DB_SYSTEM", missing);
		}
		if (migratesOnStartup(env, role)) {
			Credentials.collectMissing(db.owner(), "DB_OWNER", missing);
		}
		if (!missing.isEmpty()) {
			throw new IllegalStateException("Missing required configuration for APP_ENV=%s APP_ROLE=%s: %s"
				.formatted(env, role, String.join(", ", missing)));
		}
	}

	/** Production instances run without DDL privileges; only the migrate job and local "all" migrate. */
	public boolean migratesOnStartup() {
		return migratesOnStartup(env, role);
	}

	private static boolean migratesOnStartup(AppEnv env, AppRole role) {
		return role == AppRole.MIGRATE || (role == AppRole.ALL && env == AppEnv.LOCAL);
	}

	private static boolean isBlank(String value) {
		return value == null || value.isBlank();
	}

	public record Db(String url, Credentials app, Credentials system, Credentials owner) {
	}

	public record Credentials(String username, String password) {

		private static void collectMissing(Credentials credentials, String prefix, List<String> missing) {
			if (credentials == null || isBlank(credentials.username())) {
				missing.add(prefix + "_USER");
			}
			if (credentials == null || isBlank(credentials.password())) {
				missing.add(prefix + "_PASSWORD");
			}
		}

		@Override
		public String toString() {
			return "Credentials[username=" + username + ", password=***]";
		}

	}

}
