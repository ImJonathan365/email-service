package com.emailservice.support;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.UUID;

import com.emailservice.support.PostgresTestDatabase.Role;

/**
 * Inserts messages for one tenant directly, for tests that need volume or history the API would
 * take too long to build (anomalies, purges). Uses the tenant's first published template.
 */
public final class MessageSeeder {

	private MessageSeeder() {
	}

	/**
	 * {@code count} messages in {@code status}; message {@code g} (1..count) is created
	 * {@code createdAgo} before now, an SQL interval expression that may use g, e.g.
	 * "g * interval '1 hour'".
	 */
	public static void seed(UUID tenantId, int count, String status, String createdAgo) throws Exception {
		try (Connection system = PostgresTestDatabase.connect(Role.SYSTEM);
				PreparedStatement insert = system.prepareStatement("""
						INSERT INTO email_message (id, tenant_id, template_id, template_version_id, category, priority,
						    to_email, from_email, from_name, status, provider, provider_message_id, failure_code,
						    created_at)
						SELECT gen_random_uuid(), t.tenant_id, t.id, v.id, 'TRANSACTIONAL', 1,
						       'seed' || g || '-' || substr(md5(random()::text), 1, 8) || '@example.test',
						       'no-reply@sender.test', 'Seed', ?,
						       CASE WHEN ? IN ('SENT','DELIVERED','BOUNCED','COMPLAINED') THEN 'resend' END,
						       CASE WHEN ? IN ('SENT','DELIVERED','BOUNCED','COMPLAINED') THEN 'seed-' || gen_random_uuid() END,
						       CASE WHEN ? = 'FAILED' THEN 'PROVIDER_REJECTED' END,
						       now() - (%s)
						FROM generate_series(1, ?) g
						CROSS JOIN LATERAL (
						    SELECT t.tenant_id, t.id FROM template t
						    WHERE t.tenant_id = ? ORDER BY t.created_at LIMIT 1) t
						JOIN template_version v ON v.template_id = t.id AND v.status = 'PUBLISHED' AND v.locale = 'es-CR'
						""".formatted(createdAgo))) {
			insert.setString(1, status);
			insert.setString(2, status);
			insert.setString(3, status);
			insert.setString(4, status);
			insert.setInt(5, count);
			insert.setObject(6, tenantId);
			insert.executeUpdate();
		}
	}

}
