package com.emailservice.support;

import org.springframework.test.context.DynamicPropertyRegistry;

import com.emailservice.support.PostgresTestDatabase.Role;

/**
 * Points the application at the shared test container and sets the remaining required variables,
 * the way the environment does in production.
 */
public final class IntegrationTestEnvironment {

	/** Test-only admin credential; never used outside the test JVM. */
	public static final String ADMIN_API_KEY = "test-admin-key-0123456789abcdef0123456789";

	private IntegrationTestEnvironment() {
	}

	public static void register(DynamicPropertyRegistry registry) {
		registry.add("app.db.url", PostgresTestDatabase::jdbcUrl);
		registry.add("app.db.app.username", () -> Role.APP.username);
		registry.add("app.db.app.password", () -> Role.APP.password);
		registry.add("app.db.system.username", () -> Role.SYSTEM.username);
		registry.add("app.db.system.password", () -> Role.SYSTEM.password);
		registry.add("app.db.owner.username", () -> Role.OWNER.username);
		registry.add("app.db.owner.password", () -> Role.OWNER.password);
		registry.add("app.admin.api-keys", () -> ADMIN_API_KEY);
	}

}
