package com.emailservice.common.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;

import org.springframework.boot.context.properties.ConfigurationProperties;

import com.emailservice.common.web.Cidr;

/**
 * Environment configuration (docs/05 §6), validated at startup so a misconfigured instance never
 * boots (NFR-09). Error messages name the offending variables, never secret values.
 */
@ConfigurationProperties("app")
public record AppProperties(AppEnv env, AppRole role, Db db, Http http, Admin admin, Tenancy tenancy, Mail mail,
		Sending sending, Worker worker) {

	public AppProperties {
		Objects.requireNonNull(env, "APP_ENV is required");
		Objects.requireNonNull(role, "APP_ROLE is required");
		Objects.requireNonNull(db, "DB_* configuration is required");
		http = http == null ? new Http(List.of()) : http;
		admin = admin == null ? new Admin(List.of()) : admin;
		tenancy = tenancy == null ? Tenancy.DEFAULTS : tenancy;
		mail = mail == null ? Mail.DEFAULTS : mail;
		sending = sending == null ? Sending.DEFAULTS : sending;
		worker = worker == null ? Worker.DEFAULTS : worker;

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
		if (role != AppRole.MIGRATE) {
			mail.validate(env, problems);
			sending.validate(env, problems);
			worker.validate(mail, problems);
		}
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

	/** MAIL_PROVIDER and SMTP settings (FR-14, FR-15). SMTP is for local development only (ADR-0016). */
	public record Mail(String provider, String smtpHost, int smtpPort, boolean allowSmtpInProduction,
			int connectTimeoutMs, int readTimeoutMs) {

		public static final String SMTP = "smtp";

		public static final String NOOP = "noop";

		static final Mail DEFAULTS = new Mail(SMTP, "mailpit", 1025, false, 3_000, 10_000);

		public Mail {
			provider = provider == null ? SMTP : provider.trim().toLowerCase(Locale.ROOT);
		}

		private void validate(AppEnv env, List<String> problems) {
			switch (provider) {
				case SMTP -> {
					if (env == AppEnv.PRODUCTION && !allowSmtpInProduction) {
						problems.add("MAIL_PROVIDER=smtp is not allowed in production (set ALLOW_SMTP_IN_PRODUCTION only "
								+ "if you really mean it, AC-15.3)");
					}
					if (isBlank(smtpHost) || smtpPort < 1 || smtpPort > 65_535) {
						problems.add("SMTP_HOST and SMTP_PORT are required with MAIL_PROVIDER=smtp");
					}
				}
				case NOOP -> {
					// A no-op sender in production would silently drop every email.
					if (env == AppEnv.PRODUCTION) {
						problems.add("MAIL_PROVIDER=noop is not allowed in production");
					}
				}
				case "resend" -> problems.add("MAIL_PROVIDER=resend is not available yet (milestone H5)");
				default -> problems.add("MAIL_PROVIDER must be smtp or noop");
			}
			if (connectTimeoutMs <= 0 || readTimeoutMs <= 0) {
				problems.add("PROVIDER_CONNECT_TIMEOUT_MS and PROVIDER_READ_TIMEOUT_MS must be positive");
			}
		}

	}

	/** Acceptance settings (FR-07, FR-09, FR-10). */
	public record Sending(String suppressionHashKey, String suppressionRejectMode, List<String> allowedRecipientDomains,
			int maxRequestBytes) {

		static final int MIN_HASH_KEY_LENGTH = 32;

		static final Sending DEFAULTS = new Sending("", "accept", List.of(), 262_144);

		public Sending {
			suppressionRejectMode = suppressionRejectMode == null ? "accept"
					: suppressionRejectMode.trim().toLowerCase(Locale.ROOT);
			allowedRecipientDomains = trimmed(allowedRecipientDomains).stream()
				.map(domain -> domain.toLowerCase(Locale.ROOT))
				.toList();
		}

		public boolean rejectSuppressed() {
			return "reject".equals(suppressionRejectMode);
		}

		private void validate(AppEnv env, List<String> problems) {
			if (suppressionHashKey == null || suppressionHashKey.length() < MIN_HASH_KEY_LENGTH) {
				problems.add("SUPPRESSION_HASH_KEY is required (at least " + MIN_HASH_KEY_LENGTH + " characters)");
			}
			if (!"accept".equals(suppressionRejectMode) && !"reject".equals(suppressionRejectMode)) {
				problems.add("SUPPRESSION_REJECT_MODE must be accept or reject");
			}
			if (env == AppEnv.STAGING && allowedRecipientDomains.isEmpty()) {
				problems.add("ALLOWED_RECIPIENT_DOMAINS is required in staging (AC-09.4)");
			}
			if (maxRequestBytes < 1_024) {
				problems.add("MAX_REQUEST_BYTES must be at least 1024");
			}
		}

		@Override
		public String toString() {
			return "Sending[suppressionHashKey=***, suppressionRejectMode=" + suppressionRejectMode
					+ ", allowedRecipientDomains=" + allowedRecipientDomains + ", maxRequestBytes=" + maxRequestBytes + "]";
		}

	}

	/** Queue and retry settings (FR-12, FR-13, ADR-0010). */
	public record Worker(int concurrency, int pollIntervalMs, int maxAttempts, List<Integer> retryBackoffSeconds,
			int lockTimeoutSeconds) {

		/** Resend keeps idempotency keys for 24 h; every attempt must fall inside 23 h (AC-13.7). */
		static final long RETRY_WINDOW_SECONDS = 23 * 3_600;

		static final Worker DEFAULTS = new Worker(4, 1_000, 6, List.of(60, 300, 900, 3_600, 21_600), 60);

		public Worker {
			retryBackoffSeconds = retryBackoffSeconds == null ? List.of() : List.copyOf(retryBackoffSeconds);
		}

		private void validate(Mail mail, List<String> problems) {
			if (concurrency < 1 || concurrency > 64) {
				problems.add("WORKER_CONCURRENCY must be between 1 and 64");
			}
			if (pollIntervalMs < 100 || pollIntervalMs > 60_000) {
				problems.add("WORKER_POLL_INTERVAL_MS must be between 100 and 60000");
			}
			if (retryBackoffSeconds.isEmpty() || retryBackoffSeconds.stream().anyMatch(s -> s == null || s <= 0)) {
				problems.add("RETRY_BACKOFF_SECONDS must be a list of positive seconds");
				return;
			}
			if (maxAttempts != retryBackoffSeconds.size() + 1) {
				problems.add("MAX_ATTEMPTS must equal the number of RETRY_BACKOFF_SECONDS waits + 1 (AC-13.3)");
			}
			// A lock must outlive the slowest provider call, or a healthy send would be reclaimed.
			if (lockTimeoutSeconds * 1_000L <= (long) mail.connectTimeoutMs() + mail.readTimeoutMs()) {
				problems.add("LOCK_TIMEOUT_SECONDS must exceed the provider connect + read timeouts");
			}
			long window = Math.round(retryBackoffSeconds.stream().mapToLong(Integer::longValue).sum() * 1.2)
					+ (long) maxAttempts * lockTimeoutSeconds;
			if (window >= RETRY_WINDOW_SECONDS) {
				problems.add("RETRY_BACKOFF_SECONDS x 1.2 + MAX_ATTEMPTS x LOCK_TIMEOUT_SECONDS must stay under 23 h (AC-13.7)");
			}
		}

	}

}
