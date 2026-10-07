package com.emailservice.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.emailservice.common.config.AppProperties.Admin;
import com.emailservice.common.config.AppProperties.Credentials;
import com.emailservice.common.config.AppProperties.Db;
import com.emailservice.common.config.AppProperties.Http;
import com.emailservice.common.config.AppProperties.Tenancy;

/** NFR-09: configuration is validated at startup and an invalid one never boots. */
class AppPropertiesTest {

	static final String URL = "jdbc:postgresql://localhost:5432/email_service";
	static final Credentials APP = new Credentials("email_app", "app-secret");
	static final Credentials SYSTEM = new Credentials("email_system", "system-secret");
	static final Credentials OWNER = new Credentials("email_owner", "owner-secret");
	static final Credentials NONE = new Credentials("", "");
	static final String ADMIN_KEY = "admin-secret-0123456789abcdef0123456789";
	static final Admin ADMIN = new Admin(List.of(ADMIN_KEY));

	static AppProperties props(AppEnv env, AppRole role, Db db) {
		return new AppProperties(env, role, db, null, ADMIN, null);
	}

	static AppProperties apiWith(Http http, Admin admin, Tenancy tenancy) {
		return new AppProperties(AppEnv.PRODUCTION, AppRole.API, new Db(URL, APP, SYSTEM, NONE), http, admin, tenancy);
	}

	@Test
	void apiRoleRequiresAppAndSystemCredentialsButNotOwner() {
		assertThatNoException().isThrownBy(() -> props(AppEnv.PRODUCTION, AppRole.API, new Db(URL, APP, SYSTEM, NONE)));
		assertThatThrownBy(() -> props(AppEnv.PRODUCTION, AppRole.API, new Db(URL, NONE, SYSTEM, NONE)))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("DB_APP_USER")
			.hasMessageContaining("DB_APP_PASSWORD");
		assertThatThrownBy(() -> props(AppEnv.PRODUCTION, AppRole.WORKER, new Db(URL, APP, NONE, NONE)))
			.hasMessageContaining("DB_SYSTEM_USER");
	}

	@Test
	void migrateRoleRequiresOnlyOwnerCredentials() {
		assertThatNoException().isThrownBy(() -> new AppProperties(AppEnv.PRODUCTION, AppRole.MIGRATE,
				new Db(URL, NONE, NONE, OWNER), null, null, null));
		assertThatThrownBy(() -> props(AppEnv.PRODUCTION, AppRole.MIGRATE, new Db(URL, APP, SYSTEM, NONE)))
			.hasMessageContaining("DB_OWNER_USER")
			.hasMessageContaining("DB_OWNER_PASSWORD");
	}

	@Test
	void onlyMigrateAndLocalAllMigrateOnStartup() {
		assertThat(props(AppEnv.LOCAL, AppRole.ALL, new Db(URL, APP, SYSTEM, OWNER)).migratesOnStartup()).isTrue();
		assertThat(props(AppEnv.PRODUCTION, AppRole.MIGRATE, new Db(URL, NONE, NONE, OWNER)).migratesOnStartup())
			.isTrue();
		assertThat(props(AppEnv.PRODUCTION, AppRole.ALL, new Db(URL, APP, SYSTEM, NONE)).migratesOnStartup()).isFalse();
		assertThat(props(AppEnv.LOCAL, AppRole.API, new Db(URL, APP, SYSTEM, NONE)).migratesOnStartup()).isFalse();
	}

	@Test
	void localAllRoleRequiresOwnerCredentialsToMigrate() {
		assertThatThrownBy(() -> props(AppEnv.LOCAL, AppRole.ALL, new Db(URL, APP, SYSTEM, NONE)))
			.hasMessageContaining("DB_OWNER_USER");
	}

	@Test
	void urlIsAlwaysRequired() {
		assertThatThrownBy(() -> props(AppEnv.LOCAL, AppRole.API, new Db(" ", APP, SYSTEM, NONE)))
			.hasMessageContaining("DB_URL");
	}

	@Test
	void adminKeysAreRequiredOnlyWhereTheApiIsServed() {
		assertThatThrownBy(() -> apiWith(null, null, null)).hasMessageContaining("ADMIN_API_KEYS is required");
		assertThatThrownBy(() -> new AppProperties(AppEnv.LOCAL, AppRole.ALL, new Db(URL, APP, SYSTEM, OWNER), null,
				null, null))
			.hasMessageContaining("ADMIN_API_KEYS");
		assertThatNoException().isThrownBy(() -> new AppProperties(AppEnv.PRODUCTION, AppRole.WORKER,
				new Db(URL, APP, SYSTEM, NONE), null, null, null));
	}

	@Test
	void adminKeysMustBeLongAndSeveralAreAccepted() {
		assertThatThrownBy(() -> apiWith(null, new Admin(List.of("short")), null))
			.hasMessageContaining("at least 32 characters")
			.message()
			.doesNotContain("short");
		assertThat(apiWith(null, new Admin(List.of(ADMIN_KEY + " ", " " + ADMIN_KEY + "x", "")), null).admin().apiKeys())
			.containsExactly(ADMIN_KEY, ADMIN_KEY + "x");
	}

	@Test
	void tenantDefaultsAndLocalesAreValidated() {
		assertThat(apiWith(null, ADMIN, null).tenancy()).isEqualTo(Tenancy.DEFAULTS);
		assertThatThrownBy(() -> apiWith(null, ADMIN, new Tenancy(0, 1000, 90, List.of("es-CR"))))
			.hasMessageContaining("DEFAULT_RATE_LIMIT_PER_MINUTE");
		assertThatThrownBy(() -> apiWith(null, ADMIN, new Tenancy(60, -1, 90, List.of("es-CR"))))
			.hasMessageContaining("DEFAULT_DAILY_QUOTA");
		assertThatThrownBy(() -> apiWith(null, ADMIN, new Tenancy(60, 1000, 401, List.of("es-CR"))))
			.hasMessageContaining("DEFAULT_RETENTION_DAYS");
		assertThatThrownBy(() -> apiWith(null, ADMIN, new Tenancy(60, 1000, 90, List.of("es-CR", "fr"))))
			.hasMessageContaining("SUPPORTED_LOCALES");
	}

	@Test
	void trustedProxiesMustBeValidCidrs() {
		assertThat(apiWith(new Http(List.of(" 10.0.0.0/8 ", "192.168.1.10")), ADMIN, null).http().trustedProxyRanges())
			.hasSize(2);
		assertThatThrownBy(() -> apiWith(new Http(List.of("10.0.0.1/8")), ADMIN, null))
			.hasMessageContaining("TRUSTED_PROXIES");
		assertThatThrownBy(() -> apiWith(new Http(List.of("proxy.internal")), ADMIN, null))
			.hasMessageContaining("TRUSTED_PROXIES");
	}

	@Test
	void errorsAndToStringNeverContainSecrets() {
		assertThatThrownBy(() -> props(AppEnv.LOCAL, AppRole.ALL, new Db(URL, APP, NONE, NONE)))
			.message()
			.doesNotContain("app-secret");
		assertThat(props(AppEnv.LOCAL, AppRole.ALL, new Db(URL, APP, SYSTEM, OWNER)).toString())
			.doesNotContain("app-secret", "system-secret", "owner-secret", ADMIN_KEY);
	}

	@Test
	void invalidRoleIsRejected() {
		assertThatThrownBy(() -> AppRole.parse("scheduler")).hasMessageContaining("Invalid APP_ROLE");
		assertThat(AppRole.parse(" Migrate ")).isEqualTo(AppRole.MIGRATE);
	}

}
