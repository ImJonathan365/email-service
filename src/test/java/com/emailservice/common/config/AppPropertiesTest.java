package com.emailservice.common.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.Test;

import com.emailservice.common.config.AppProperties.Credentials;
import com.emailservice.common.config.AppProperties.Db;

/** NFR-09: configuration is validated at startup and an invalid one never boots. */
class AppPropertiesTest {

	static final String URL = "jdbc:postgresql://localhost:5432/email_service";
	static final Credentials APP = new Credentials("email_app", "app-secret");
	static final Credentials SYSTEM = new Credentials("email_system", "system-secret");
	static final Credentials OWNER = new Credentials("email_owner", "owner-secret");
	static final Credentials NONE = new Credentials("", "");

	@Test
	void apiRoleRequiresAppAndSystemCredentialsButNotOwner() {
		assertThatNoException().isThrownBy(
				() -> new AppProperties(AppEnv.PRODUCTION, AppRole.API, new Db(URL, APP, SYSTEM, NONE), null));
		assertThatThrownBy(() -> new AppProperties(AppEnv.PRODUCTION, AppRole.API, new Db(URL, NONE, SYSTEM, NONE), null))
			.isInstanceOf(IllegalStateException.class)
			.hasMessageContaining("DB_APP_USER")
			.hasMessageContaining("DB_APP_PASSWORD");
		assertThatThrownBy(() -> new AppProperties(AppEnv.PRODUCTION, AppRole.WORKER, new Db(URL, APP, NONE, NONE), null))
			.hasMessageContaining("DB_SYSTEM_USER");
	}

	@Test
	void migrateRoleRequiresOnlyOwnerCredentials() {
		assertThatNoException().isThrownBy(
				() -> new AppProperties(AppEnv.PRODUCTION, AppRole.MIGRATE, new Db(URL, NONE, NONE, OWNER), null));
		assertThatThrownBy(() -> new AppProperties(AppEnv.PRODUCTION, AppRole.MIGRATE, new Db(URL, APP, SYSTEM, NONE), null))
			.hasMessageContaining("DB_OWNER_USER")
			.hasMessageContaining("DB_OWNER_PASSWORD");
	}

	@Test
	void onlyMigrateAndLocalAllMigrateOnStartup() {
		assertThat(new AppProperties(AppEnv.LOCAL, AppRole.ALL, new Db(URL, APP, SYSTEM, OWNER), null).migratesOnStartup())
			.isTrue();
		assertThat(new AppProperties(AppEnv.PRODUCTION, AppRole.MIGRATE, new Db(URL, NONE, NONE, OWNER), null)
			.migratesOnStartup()).isTrue();
		assertThat(new AppProperties(AppEnv.PRODUCTION, AppRole.ALL, new Db(URL, APP, SYSTEM, NONE), null).migratesOnStartup())
			.isFalse();
		assertThat(new AppProperties(AppEnv.LOCAL, AppRole.API, new Db(URL, APP, SYSTEM, NONE), null).migratesOnStartup())
			.isFalse();
	}

	@Test
	void localAllRoleRequiresOwnerCredentialsToMigrate() {
		assertThatThrownBy(() -> new AppProperties(AppEnv.LOCAL, AppRole.ALL, new Db(URL, APP, SYSTEM, NONE), null))
			.hasMessageContaining("DB_OWNER_USER");
	}

	@Test
	void urlIsAlwaysRequired() {
		assertThatThrownBy(() -> new AppProperties(AppEnv.LOCAL, AppRole.API, new Db(" ", APP, SYSTEM, NONE), null))
			.hasMessageContaining("DB_URL");
	}

	@Test
	void errorsAndToStringNeverContainSecrets() {
		assertThatThrownBy(() -> new AppProperties(AppEnv.LOCAL, AppRole.ALL, new Db(URL, APP, NONE, NONE), null))
			.message()
			.doesNotContain("app-secret");
		assertThat(new AppProperties(AppEnv.LOCAL, AppRole.ALL, new Db(URL, APP, SYSTEM, OWNER), null).toString())
			.doesNotContain("app-secret", "system-secret", "owner-secret");
	}

	@Test
	void trustedProxiesMustBeValidCidrs() {
		assertThat(new AppProperties(AppEnv.PRODUCTION, AppRole.API, new Db(URL, APP, SYSTEM, NONE),
				new AppProperties.Http(List.of(" 10.0.0.0/8 ", "192.168.1.10"))).http().trustedProxyRanges())
			.hasSize(2);
		assertThatThrownBy(() -> new AppProperties(AppEnv.PRODUCTION, AppRole.API, new Db(URL, APP, SYSTEM, NONE),
				new AppProperties.Http(List.of("10.0.0.1/8"))))
			.hasMessageContaining("TRUSTED_PROXIES");
		assertThatThrownBy(() -> new AppProperties(AppEnv.PRODUCTION, AppRole.API, new Db(URL, APP, SYSTEM, NONE),
				new AppProperties.Http(List.of("proxy.internal"))))
			.hasMessageContaining("TRUSTED_PROXIES");
	}

	@Test
	void invalidRoleIsRejected() {
		assertThatThrownBy(() -> AppRole.parse("scheduler")).hasMessageContaining("Invalid APP_ROLE");
		assertThat(AppRole.parse(" Migrate ")).isEqualTo(AppRole.MIGRATE);
	}

}
