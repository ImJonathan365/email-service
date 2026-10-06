package com.emailservice.tenancy;

import static com.emailservice.tenancy.RowLevelSecurityTest.setTenant;
import static com.emailservice.tenancy.RowLevelSecurityTest.update;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import com.emailservice.support.PostgresTestDatabase;
import com.emailservice.support.PostgresTestDatabase.Role;
import com.emailservice.support.TenantFixture;

/** Invariants enforced by the database itself (NFR-08, AC-05.2). */
class SchemaInvariantsTest {

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

	@Test
	void messageReferencingAnotherTenantsTemplateVersionViolatesForeignKey() throws SQLException {
		try (Connection app = PostgresTestDatabase.connect(Role.APP)) {
			app.setAutoCommit(false);
			setTenant(app, tenantA.tenantId());
			assertThatThrownBy(() -> update(app, """
					INSERT INTO email_message (id, tenant_id, template_id, template_version_id, category,
					    priority, to_email, from_email, from_name, status)
					VALUES (?, ?, ?, ?, 'TRANSACTIONAL', 1, 'user@example.test', 'no-reply@example.test', 'Test', 'QUEUED')
					""", UUID.randomUUID(), tenantA.tenantId(), tenantA.templateId(), tenantB.templateVersionId()))
				.isInstanceOfSatisfying(SQLException.class, e -> assertThat(e.getSQLState()).isEqualTo("23503"));
			app.rollback();
		}
	}

	@Test
	void publishedTemplateVersionContentIsImmutable() throws SQLException {
		try (Connection system = PostgresTestDatabase.connect(Role.SYSTEM)) {
			assertThatThrownBy(() -> update(system, "UPDATE template_version SET html_template = '<p>x</p>' WHERE id = ?",
					tenantA.templateVersionId()))
				.isInstanceOf(SQLException.class)
				.hasMessageContaining("immutable");
			assertThatThrownBy(() -> update(system, "UPDATE template_version SET status = 'DRAFT' WHERE id = ?",
					tenantA.templateVersionId()))
				.isInstanceOf(SQLException.class)
				.hasMessageContaining("cannot change status");
		}
	}

	@Test
	void publishedTemplateVersionCanBeArchived() throws SQLException {
		try (Connection system = PostgresTestDatabase.connect(Role.SYSTEM)) {
			assertThat(update(system, "UPDATE template_version SET status = 'ARCHIVED' WHERE id = ?",
					tenantB.templateVersionId()))
				.isOne();
		}
	}

}
