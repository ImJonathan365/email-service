package com.emailservice.common.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;

import com.emailservice.common.web.Cidr;

/**
 * Environment configuration (docs/05 §6), validated at startup so a misconfigured instance never
 * boots (NFR-09). Error messages name the offending variables, never secret values.
 */
@ConfigurationProperties("app")
public record AppProperties(AppEnv env, AppRole role, Db db, Http http, Admin admin, Tenancy tenancy) {

	public AppProperties {
		Objects.requireNonNull(env, "APP_ENV is required");
		Objects.requireNonNull(role, "APP_ROLE is required");
		Objects.requireNonNull(db, "DB_* configuration is required");
		http = http == null ? new Http(List.of()) : http;
		admin = admin == null ? new Admin(List.of()) : admin;
		tenancy = tenancy == null ? Tenancy.DEFAULTS : tenancy;

		List<String> problems = new ArrayList<>();
		if (isBlank(db.url())) {
			problems.add("DB_URL is required");
		}
		if (role != AppRole.MIGRATE) {
			Credentials.collectMissing(db.app(), "DB_APP", problems);
			Credentials.collectMissing(db.system(), "DB_SYSTEM", problems);
		}
		if (migratesOnStartup(env, role)) {
			Credentials.collectMissing(db.owner(), "DB_OWNER", problems);
		}
		http.validate(problems);
		if (role == AppRole.API || role == AppRole.ALL) {
			admin.validate(problems);
		}
		tenancy.validate(problems);
		if (!problems.isEmpty()) {
			throw new IllegalStateException("Invalid configuration for APP_ENV=%s APP_ROLE=%s: %s"
				.formatted(env, role, String.join("; ", problems)));
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

	private static List<String> trimmed(List<String> values) {
		return values == null ? List.of() : values.stream().map(String::trim).filter(v -> !v.isEmpty()).toList();
	}

	public record Db(String url, Credentials app, Credentials system, Credentials owner) {
	}

	public record Credentials(String username, String password) {

		private static void collectMissing(Credentials credentials, String prefix, List<String> problems) {
			if (credentials == null || isBlank(credentials.username())) {
				problems.add(prefix + "_USER is required");
			}
			if (credentials == null || isBlank(credentials.password())) {
				problems.add(prefix + "_PASSWORD is required");
			}
		}

		@Override
		public String toString() {
			return "Credentials[username=" + username + ", password=***]";
		}

	}

	/** TRUSTED_PROXIES: proxies whose X-Forwarded-For is believed (AC-33.3). */
	public record Http(List<String> trustedProxies) {

		public Http {
			trustedProxies = trimmed(trustedProxies);
		}

		public List<Cidr> trustedProxyRanges() {
			return trustedProxies.stream().map(Cidr::parse).toList();
		}

		private void validate(List<String> problems) {
			for (String proxy : trustedProxies) {
				try {
					Cidr.parse(proxy);
				}
				catch (IllegalArgumentException ex) {
					problems.add("TRUSTED_PROXIES has an invalid CIDR: " + proxy);
				}
			}
		}

	}

	/**
	 * ADMIN_API_KEYS: credentials for /admin/v1/** (several, to rotate without downtime). Only the
	 * roles that serve the API need them, so worker and migrate never hold an admin secret.
	 */
	public record Admin(List<String> apiKeys) {

		static final int MIN_KEY_LENGTH = 32;

		public Admin {
			apiKeys = trimmed(apiKeys);
		}

		private void validate(List<String> problems) {
			if (apiKeys.isEmpty()) {
				problems.add("ADMIN_API_KEYS is required");
			}
			else if (apiKeys.stream().anyMatch(key -> key.length() < MIN_KEY_LENGTH)) {
				problems.add("ADMIN_API_KEYS entries must be at least " + MIN_KEY_LENGTH + " characters");
			}
		}

		@Override
		public String toString() {
			return "Admin[apiKeys=" + apiKeys.size() + " configured]";
		}

	}

	/** Defaults applied to new tenants (AC-02.5) and the locales templates may use (FR-37). */
	public record Tenancy(int defaultRateLimitPerMinute, int defaultDailyQuota, int defaultRetentionDays,
			List<String> supportedLocales) {

		// The template_version.locale CHECK constraint bounds what can ever be configured.
		static final Set<String> KNOWN_LOCALES = Set.of("es-CR", "en");

		static final Tenancy DEFAULTS = new Tenancy(60, 1000, 90, List.of("es-CR", "en"));

		public Tenancy {
			supportedLocales = trimmed(supportedLocales);
		}

		private void validate(List<String> problems) {
			if (defaultRateLimitPerMinute <= 0) {
				problems.add("DEFAULT_RATE_LIMIT_PER_MINUTE must be positive");
			}
			if (defaultDailyQuota <= 0) {
				problems.add("DEFAULT_DAILY_QUOTA must be positive");
			}
			if (defaultRetentionDays < 7 || defaultRetentionDays > 400) {
				problems.add("DEFAULT_RETENTION_DAYS must be between 7 and 400");
			}
			if (supportedLocales.isEmpty() || !KNOWN_LOCALES.containsAll(supportedLocales)) {
				problems.add("SUPPORTED_LOCALES must be a non-empty subset of " + KNOWN_LOCALES);
			}
		}

	}

}
