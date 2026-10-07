package com.emailservice.common.persistence;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import java.util.List;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.boot.flyway.autoconfigure.FlywayMigrationStrategy;

import com.emailservice.common.config.AppEnv;
import com.emailservice.common.config.AppProperties;
import com.emailservice.common.config.AppProperties.Credentials;
import com.emailservice.common.config.AppProperties.Db;
import com.emailservice.common.config.AppRole;

/** NFR-09: only the migrate job and local "all" run Flyway. */
class MigrationConfigTest {

	static final Credentials CREDENTIALS = new Credentials("user", "secret");
	static final Db DB = new Db("jdbc:postgresql://localhost/db", CREDENTIALS, CREDENTIALS, CREDENTIALS);

	@Test
	void localAllRoleMigrates() {
		Flyway flyway = mock(Flyway.class);
		strategyFor(AppEnv.LOCAL, AppRole.ALL).migrate(flyway);
		verify(flyway).migrate();
	}

	@Test
	void migrateRoleMigrates() {
		Flyway flyway = mock(Flyway.class);
		strategyFor(AppEnv.PRODUCTION, AppRole.MIGRATE).migrate(flyway);
		verify(flyway).migrate();
	}

	@Test
	void productionRuntimeRolesNeverMigrate() {
		for (AppRole role : new AppRole[] { AppRole.API, AppRole.WORKER, AppRole.ALL }) {
			Flyway flyway = mock(Flyway.class);
			strategyFor(AppEnv.PRODUCTION, role).migrate(flyway);
			verify(flyway, never()).migrate();
		}
	}

	private static FlywayMigrationStrategy strategyFor(AppEnv env, AppRole role) {
		return new MigrationConfig().flywayMigrationStrategy(new AppProperties(env, role, DB, null, new AppProperties.Admin(List.of("admin-secret-0123456789abcdef0123456789")), null));
	}

}
