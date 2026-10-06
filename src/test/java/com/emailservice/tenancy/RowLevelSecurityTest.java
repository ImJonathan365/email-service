package com.emailservice.tenancy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.FieldSource;

import com.emailservice.support.PostgresTestDatabase;
import com.emailservice.support.PostgresTestDatabase.Role;
import com.emailservice.support.TenantFixture;

/** NFR-08 / ADR-0008: RLS is the second barrier and must fail closed for the email_app role. */
class RowLevelSecurityTest {

	static final List<String> TENANT_SCOPED_TABLES = List.of("tenant", "api_key", "template",
			"template_version", "email_message", "email_event", "audit_log", "rate_limit_counter");

	static TenantFixture tenantA;
	static TenantFixture tenantB;

	@BeforeAll
	static void seed() throws SQLException {
		PostgresTestDatabase.migrate();
		try (Connection system = PostgresTestDatabase.connect(Role.SYSTEM)) {
			tenantA = TenantFixture.create(system);
			tenantB = TenantFixture.create(system);
		}
	}

	@ParameterizedTest
	@FieldSource("TENANT_SCOPED_TABLES")
	void appRoleWithoutTenantContextSeesNoRows(String table) throws SQLException {
		try (Connection app = PostgresTestDatabase.connect(Role.APP)) {
			assertThat(count(app, "SELECT count(*) FROM " + table)).isZero();
		}
	}

	@Test
	void appRoleWithoutTenantContextSeesNoTenantSuppressions() throws SQLException {
		try (Connection app = PostgresTestDatabase.connect(Role.APP)) {
			assertThat(count(app, "SELECT count(*) FROM suppression WHERE scope = 'TENANT'")).isZero();
		}
	}

	@Test
	void appRoleSeesGlobalSuppressionsByDesign() throws SQLException {
		try (Connection app = PostgresTestDatabase.connect(Role.APP)) {
			assertThat(count(app, "SELECT count(*) FROM suppression WHERE id = ?", tenantA.globalSuppressionId()))
				.isOne();
		}
	}

	@Test
	void tenantContextEndsWithTheTransaction() throws SQLException {
		try (Connection app = PostgresTestDatabase.connect(Role.APP)) {
			app.setAutoCommit(false);
			setTenant(app, tenantA.tenantId());
			assertThat(count(app, "SELECT count(*) FROM email_message")).isOne();
			app.commit();

			// Same pooled connection, new transaction without context: must still fail closed.
			assertThat(count(app, "SELECT count(*) FROM email_message")).isZero();
			app.commit();
		}
	}

	@Test
	void appRoleSeesOnlyItsOwnTenantRows() throws SQLException {
		try (Connection app = PostgresTestDatabase.connect(Role.APP)) {
			app.setAutoCommit(false);
			setTenant(app, tenantA.tenantId());
			assertThat(count(app, "SELECT count(*) FROM tenant")).isOne();
			assertThat(count(app, "SELECT count(*) FROM tenant WHERE id = ?", tenantB.tenantId())).isZero();
			assertThat(count(app, "SELECT count(*) FROM email_message WHERE id = ?", tenantB.messageId())).isZero();
			assertThat(count(app, "SELECT count(*) FROM suppression WHERE id = ?", tenantB.tenantSuppressionId()))
				.isZero();
			assertThat(count(app, "SELECT count(*) FROM suppression WHERE id = ?", tenantA.tenantSuppressionId()))
				.isOne();
			app.rollback();
		}
	}

	@Test
	void appRoleCannotWriteAnotherTenantsRows() throws SQLException {
		try (Connection app = PostgresTestDatabase.connect(Role.APP)) {
			app.setAutoCommit(false);
			setTenant(app, tenantA.tenantId());
			assertThat(update(app, "UPDATE email_message SET to_name = 'x' WHERE id = ?", tenantB.messageId()))
				.isZero();
			assertThatThrownBy(() -> update(app, """
					INSERT INTO audit_log (id, tenant_id, actor_type, action) VALUES (?, ?, 'SYSTEM', 'X')
					""", UUID.randomUUID(), tenantB.tenantId()))
				.isInstanceOf(SQLException.class)
				.hasMessageContaining("row-level security");
			app.rollback();
		}
	}

	@Test
	void appRoleCannotReadSuppressionSourceColumns() throws SQLException {
		try (Connection app = PostgresTestDatabase.connect(Role.APP)) {
			assertThatThrownBy(() -> count(app, "SELECT count(source_tenant_id) FROM suppression"))
				.isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("42501"));
			assertThatThrownBy(() -> count(app, "SELECT count(source_message_id) FROM suppression"))
				.isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("42501"));
		}
	}

	@Test
	void appRoleCannotWriteTenants() throws SQLException {
		try (Connection app = PostgresTestDatabase.connect(Role.APP)) {
			assertThatThrownBy(() -> update(app, "UPDATE tenant SET name = 'x' WHERE id = ?", tenantA.tenantId()))
				.isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("42501"));
			assertThatThrownBy(() -> update(app, "DELETE FROM tenant WHERE id = ?", tenantA.tenantId()))
				.isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("42501"));
		}
	}

	@Test
	void auditLogIsAppendOnlyForRuntimeRoles() throws SQLException {
		for (Role role : List.of(Role.APP, Role.SYSTEM)) {
			try (Connection connection = PostgresTestDatabase.connect(role)) {
				assertThatThrownBy(() -> update(connection, "DELETE FROM audit_log"))
					.isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("42501"));
				assertThatThrownBy(() -> update(connection, "UPDATE audit_log SET action = 'X'"))
					.isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("42501"));
			}
		}
	}

	@Test
	void systemRoleBypassesRls() throws SQLException {
		try (Connection system = PostgresTestDatabase.connect(Role.SYSTEM)) {
			assertThat(count(system, "SELECT count(*) FROM email_message WHERE tenant_id IN (?, ?)",
					tenantA.tenantId(), tenantB.tenantId()))
				.isEqualTo(2);
		}
	}

	static void setTenant(Connection connection, UUID tenantId) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement("SELECT set_config('app.tenant_id', ?, true)")) {
			statement.setString(1, tenantId.toString());
			statement.execute();
		}
	}

	static long count(Connection connection, String sql, Object... params) throws SQLException {
		try (PreparedStatement statement = prepare(connection, sql, params); ResultSet rs = statement.executeQuery()) {
			rs.next();
			return rs.getLong(1);
		}
	}

	static int update(Connection connection, String sql, Object... params) throws SQLException {
		try (PreparedStatement statement = prepare(connection, sql, params)) {
			return statement.executeUpdate();
		}
	}

	private static PreparedStatement prepare(Connection connection, String sql, Object... params) throws SQLException {
		PreparedStatement statement = connection.prepareStatement(sql);
		for (int i = 0; i < params.length; i++) {
			statement.setObject(i + 1, params[i]);
		}
		return statement;
	}

}
