package com.emailservice.support;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.util.UUID;

/**
 * Seeds one row in every tenant-scoped table through a connection that bypasses RLS.
 * Addresses use the reserved example.test domain; nothing here is ever sent.
 */
public record TenantFixture(UUID tenantId, UUID templateId, UUID templateVersionId, UUID messageId,
		UUID tenantSuppressionId, UUID globalSuppressionId) {

	public static TenantFixture create(Connection system) throws SQLException {
		var tenantId = UUID.randomUUID();
		var templateId = UUID.randomUUID();
		var versionId = UUID.randomUUID();
		var messageId = UUID.randomUUID();
		var tenantSuppressionId = UUID.randomUUID();
		var globalSuppressionId = UUID.randomUUID();
		var slug = "t-" + tenantId.toString().substring(0, 8);

		exec(system, """
				INSERT INTO tenant (id, slug, name, status, from_email, from_name)
				VALUES (?, ?, 'Test tenant', 'ACTIVE', 'no-reply@example.test', 'Test')
				""", tenantId, slug);
		exec(system, """
				INSERT INTO api_key (id, tenant_id, name, key_prefix, key_hash, status)
				VALUES (?, ?, 'test', ?, decode(repeat('ab', 32), 'hex'), 'ACTIVE')
				""", UUID.randomUUID(), tenantId, "esk_test_" + slug);
		exec(system, """
				INSERT INTO template (id, tenant_id, key, name, category, status)
				VALUES (?, ?, 'welcome', 'Welcome', 'TRANSACTIONAL', 'ACTIVE')
				""", templateId, tenantId);
		exec(system, """
				INSERT INTO template_version (id, tenant_id, template_id, version, subject_template,
				    html_template, variables_schema, status, published_at)
				VALUES (?, ?, ?, 1, 'Hello', '<p>Hello</p>', '{}'::jsonb, 'PUBLISHED', now())
				""", versionId, tenantId, templateId);
		exec(system, """
				INSERT INTO email_message (id, tenant_id, template_id, template_version_id, category,
				    priority, to_email, from_email, from_name, status)
				VALUES (?, ?, ?, ?, 'TRANSACTIONAL', 1, 'user@example.test', 'no-reply@example.test', 'Test', 'QUEUED')
				""", messageId, tenantId, templateId, versionId);
		exec(system, """
				INSERT INTO email_event (id, tenant_id, message_id, provider, provider_event_id, type,
				    provider_type, occurred_at)
				VALUES (?, ?, ?, 'stub', ?, 'SENT', 'email.sent', now())
				""", UUID.randomUUID(), tenantId, messageId, "evt-" + messageId);
		exec(system, """
				INSERT INTO suppression (id, scope, tenant_id, email_hash, reason)
				VALUES (?, 'TENANT', ?, decode(md5(?::text) || md5(?::text), 'hex'), 'MANUAL')
				""", tenantSuppressionId, tenantId, tenantSuppressionId, tenantId);
		exec(system, """
				INSERT INTO suppression (id, scope, email_hash, reason, source_tenant_id, source_message_id)
				VALUES (?, 'GLOBAL', decode(md5(?::text) || md5(?::text), 'hex'), 'HARD_BOUNCE', ?, ?)
				""", globalSuppressionId, globalSuppressionId, tenantId, tenantId, messageId);
		exec(system, """
				INSERT INTO audit_log (id, tenant_id, actor_type, action)
				VALUES (?, ?, 'SYSTEM', 'TEST_SEED')
				""", UUID.randomUUID(), tenantId);
		exec(system, """
				INSERT INTO rate_limit_counter (tenant_id, window_kind, window_start, count)
				VALUES (?, 'MINUTE', date_trunc('minute', now()), 1)
				""", tenantId);

		return new TenantFixture(tenantId, templateId, versionId, messageId, tenantSuppressionId,
				globalSuppressionId);
	}

	private static void exec(Connection connection, String sql, Object... params) throws SQLException {
		try (PreparedStatement statement = connection.prepareStatement(sql)) {
			for (int i = 0; i < params.length; i++) {
				statement.setObject(i + 1, params[i]);
			}
			statement.executeUpdate();
		}
	}

}
