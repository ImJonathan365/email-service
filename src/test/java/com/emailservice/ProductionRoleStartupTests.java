package com.emailservice;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.emailservice.support.IntegrationTestEnvironment;
import com.emailservice.support.PostgresTestDatabase;

/** NFR-09: production api/worker instances start without owner credentials and never migrate. */
@SpringBootTest(properties = { "app.env=production", "app.role=api" })
class ProductionRoleStartupTests {

	@BeforeAll
	static void migrateAsTheDeployJobWould() {
		PostgresTestDatabase.migrate();
	}

	@DynamicPropertySource
	static void database(DynamicPropertyRegistry registry) {
		IntegrationTestEnvironment.register(registry);
		registry.add("app.db.owner.username", () -> "");
		registry.add("app.db.owner.password", () -> "");
		// Production refuses noop and plain SMTP; this startup test opts into SMTP explicitly.
		registry.add("app.mail.provider", () -> "smtp");
		registry.add("app.mail.allow-smtp-in-production", () -> "true");
	}

	@Autowired
	JdbcClient tenantJdbc;

	@Test
	void startsWithoutOwnerCredentials() {
		assertThat(tenantJdbc.sql("SELECT current_user").query(String.class).single()).isEqualTo("email_app");
	}

}
