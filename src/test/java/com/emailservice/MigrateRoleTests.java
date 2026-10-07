package com.emailservice;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.emailservice.common.config.AppRole;
import com.emailservice.support.PostgresTestDatabase;
import com.emailservice.support.PostgresTestDatabase.Role;

/** NFR-09: APP_ROLE=migrate runs Flyway as email_owner without runtime credentials and exits cleanly. */
class MigrateRoleTests {

	@Test
	void migrateRoleAppliesMigrationsAndExitsWithZero() throws Exception {
		ConfigurableApplicationContext context = EmailServiceApplication.create(AppRole.MIGRATE)
			.run("--app.role=migrate", "--app.env=production", "--app.db.url=" + PostgresTestDatabase.jdbcUrl(),
					"--app.db.owner.username=" + Role.OWNER.username,
					"--app.db.owner.password=" + Role.OWNER.password);

		// Only Flyway's own connection exists: no runtime DataSource, web layer or tenant code.
		assertThat(context.getBeansOfType(DataSource.class)).isEmpty();
		assertThat(context.getBeansOfType(JdbcClient.class)).isEmpty();
		assertThat(SpringApplication.exit(context)).isZero();

		try (Connection owner = PostgresTestDatabase.connect(Role.OWNER);
				ResultSet rs = owner.createStatement()
					.executeQuery("SELECT count(*) FROM flyway_schema_history WHERE success")) {
			rs.next();
			assertThat(rs.getInt(1)).isEqualTo(2);
		}
	}

}
