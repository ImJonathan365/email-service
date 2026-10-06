package com.emailservice.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.support.TransactionTemplate;

import com.emailservice.support.PostgresTestDatabase;
import com.emailservice.support.PostgresTestDatabase.Role;
import com.emailservice.support.TenantFixture;
import com.zaxxer.hikari.HikariDataSource;

/** ADR-0008: every tenant transaction runs with app.tenant_id set from TenantContext. */
class TenantTransactionManagerTest {

	static HikariDataSource dataSource;
	static JdbcClient jdbc;
	static TransactionTemplate tx;
	static TenantFixture tenantA;
	static TenantFixture tenantB;

	@BeforeAll
	static void setUp() throws SQLException {
		PostgresTestDatabase.migrate();
		try (Connection system = PostgresTestDatabase.connect(Role.SYSTEM)) {
			tenantA = TenantFixture.create(system);
			tenantB = TenantFixture.create(system);
		}
		dataSource = new HikariDataSource();
		dataSource.setJdbcUrl(PostgresTestDatabase.jdbcUrl());
		dataSource.setUsername(Role.APP.username);
		dataSource.setPassword(Role.APP.password);
		// One connection makes every transaction reuse it, which is where a leaked setting would show.
		dataSource.setMaximumPoolSize(1);
		jdbc = JdbcClient.create(dataSource);
		tx = new TransactionTemplate(new TenantTransactionManager(dataSource));
	}

	@AfterAll
	static void tearDown() {
		dataSource.close();
	}

	@Test
	void transactionSeesOnlyTheContextTenant() {
		UUID seen = TenantContext.call(tenantA.tenantId(),
				() -> tx.execute(status -> jdbc.sql("SELECT id FROM tenant").query(UUID.class).single()));
		assertThat(seen).isEqualTo(tenantA.tenantId());

		long otherTenantMessages = TenantContext.call(tenantA.tenantId(), () -> tx.execute(status -> jdbc
			.sql("SELECT count(*) FROM email_message WHERE tenant_id = ?")
			.param(tenantB.tenantId())
			.query(Long.class)
			.single()));
		assertThat(otherTenantMessages).isZero();
	}

	@Test
	void settingDoesNotLeakToTheNextUseOfThePooledConnection() {
		TenantContext.run(tenantA.tenantId(), () -> tx.executeWithoutResult(status -> jdbc.sql("SELECT 1").query()));

		long visible = jdbc.sql("SELECT count(*) FROM email_message").query(Long.class).single();
		assertThat(visible).isZero();
	}

	@Test
	void transactionWithoutTenantContextIsRefused() {
		assertThatThrownBy(() -> tx.executeWithoutResult(status -> jdbc.sql("SELECT 1").query()))
			.isInstanceOf(CannotCreateTransactionException.class)
			.hasRootCauseMessage("Tenant transaction started without a tenant context");
	}

}
