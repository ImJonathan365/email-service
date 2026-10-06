package com.emailservice.support;

import org.springframework.test.context.DynamicPropertyRegistry;

import com.emailservice.support.PostgresTestDatabase.Role;

/** Points the application at the shared test container, the way the DB_* variables do in production. */
public final class IntegrationTestDatabase {

	private IntegrationTestDatabase() {
	}

	public static void register(DynamicPropertyRegistry registry) {
		registry.add("app.db.url", PostgresTestDatabase::jdbcUrl);
		registry.add("app.db.app.username", () -> Role.APP.username);
		registry.add("app.db.app.password", () -> Role.APP.password);
		registry.add("app.db.system.username", () -> Role.SYSTEM.username);
		registry.add("app.db.system.password", () -> Role.SYSTEM.password);
		registry.add("app.db.owner.username", () -> Role.OWNER.username);
		registry.add("app.db.owner.password", () -> Role.OWNER.password);
	}

}
