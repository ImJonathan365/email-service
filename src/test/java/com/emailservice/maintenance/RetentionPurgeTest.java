package com.emailservice.maintenance;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.emailservice.sending.SendingTestSupport;
import com.emailservice.support.IntegrationTest;
import com.emailservice.support.PostgresTestDatabase;
import com.emailservice.support.PostgresTestDatabase.Role;

import tools.jackson.databind.JsonNode;

/** FR-23 and docs/06 §8: each retention at its boundary, never touching what must stay. */
class RetentionPurgeTest extends IntegrationTest {

	@Autowired
	RetentionPurges purges;

	SendingTestSupport a;

	@BeforeEach
	void setUp() throws Exception {
		a = new SendingTestSupport(http(), "SECURITY");
	}

	UUID accept(String extra) throws Exception {
		HttpResponse<String> response = a.send(a.bodyTo(SendingTestSupport.uniqueAddress(), extra), null);
		assertThat(response.statusCode()).as(response.body()).isEqualTo(202);
		return UUID.fromString(SendingTestSupport.json(response).get("id").stringValue());
	}

	static void sql(String statement, Object... params) throws Exception {
		try (Connection system = PostgresTestDatabase.connect(Role.SYSTEM);
				PreparedStatement prepared = system.prepareStatement(statement)) {
			for (int i = 0; i < params.length; i++) {
				prepared.setObject(i + 1, params[i]);
			}
			prepared.executeUpdate();
		}
	}

	static long count(String query, Object... params) throws Exception {
		try (Connection system = PostgresTestDatabase.connect(Role.SYSTEM);
				PreparedStatement prepared = system.prepareStatement(query)) {
			for (int i = 0; i < params.length; i++) {
				prepared.setObject(i + 1, params[i]);
			}
			try (ResultSet rs = prepared.executeQuery()) {
				rs.next();
				return rs.getLong(1);
			}
		}
	}

	static JsonNode row(UUID id) throws Exception {
		return SendingTestSupport.row(id);
	}

	/** A message closed some days ago in a terminal state. */
	UUID failedDaysAgo(int days) throws Exception {
		UUID id = accept("");
		sql("""
				UPDATE email_message SET status = 'FAILED', failure_code = 'PROVIDER_REJECTED',
				    finalized_at = now() - ? * interval '1 day' WHERE id = ?
				""", days, id);
		return id;
	}

	@Test
	void ac_23_5_variablesGoSevenDaysAfterATerminalState() throws Exception {
		UUID recent = failedDaysAgo(6);
		UUID old = failedDaysAgo(8);

		purges.purgeVariables();

		assertThat(row(recent).get("variables").has("firstName")).isTrue();
		assertThat(row(recent).get("variables_purged_at").isNull()).isTrue();
		assertThat(row(old).get("variables").toString()).isEqualTo("{}");
		assertThat(row(old).get("variables_purged_at").isNull()).isFalse();
	}

	@Test
	void ac_23_5_aSentMessageIsFinalByTimeAfterSeventyTwoHours() throws Exception {
		UUID stillOpen = accept("");
		UUID finalByTime = accept("");
		// 72 h to be final plus 7 days: 9 days and 23 hours is not yet, 10 days and 1 hour is.
		sql("UPDATE email_message SET status = 'DELIVERED', provider = 'resend', provider_message_id = ?, "
				+ "sent_at = now() - interval '9 days 23 hours' WHERE id = ?", "p-" + stillOpen, stillOpen);
		sql("UPDATE email_message SET status = 'SENT', provider = 'resend', provider_message_id = ?, "
				+ "sent_at = now() - interval '10 days 1 hour' WHERE id = ?", "p-" + finalByTime, finalByTime);

		purges.purgeVariables();

		assertThat(row(stillOpen).get("variables_purged_at").isNull()).isTrue();
		assertThat(row(finalByTime).get("variables").toString()).isEqualTo("{}");
	}

	@Test
	void ac_08_5_idempotencyKeysAreDroppedAfterTwentyFourHours() throws Exception {
		HttpResponse<String> recent = a.send(a.body(""), "recent-" + UUID.randomUUID());
		HttpResponse<String> old = a.send(a.body(""), "old-" + UUID.randomUUID());
		UUID recentId = UUID.fromString(SendingTestSupport.json(recent).get("id").stringValue());
		UUID oldId = UUID.fromString(SendingTestSupport.json(old).get("id").stringValue());
		sql("UPDATE email_message SET created_at = now() - interval '23 hours' WHERE id = ?", recentId);
		sql("UPDATE email_message SET created_at = now() - interval '25 hours' WHERE id = ?", oldId);

		purges.purgeIdempotencyKeys();

		assertThat(row(recentId).get("idempotency_key").isNull()).isFalse();
		assertThat(row(oldId).get("idempotency_key").isNull()).isTrue();
		assertThat(row(oldId).get("request_hash").isNull()).isTrue();
	}

	@Test
	void ac_23_1_messagesEventsAndTagsGoAfterTheTenantsRetention() throws Exception {
		a.admin.patchTenant(a.tenant, "{\"retentionDays\": 30}");
		UUID kept = failedDaysAgo(1);
		UUID expired = failedDaysAgo(1);
		UUID stillQueued = accept("\"tags\": [\"invoice\"]");
		sql("UPDATE email_message SET created_at = now() - interval '29 days' WHERE id = ?", kept);
		sql("UPDATE email_message SET created_at = now() - interval '31 days', tags = ARRAY['invoice'] WHERE id = ?",
				expired);
		sql("INSERT INTO email_message_tag (tenant_id, message_id, tag, created_at) VALUES (?, ?, 'invoice', now())",
				a.tenant.id(), expired);
		sql("""
				INSERT INTO email_event (id, tenant_id, message_id, provider, provider_event_id, type, provider_type,
				    occurred_at)
				VALUES (gen_random_uuid(), ?, ?, 'resend', ?, 'BOUNCED', 'email.bounced', now() - interval '31 days')
				""", a.tenant.id(), expired, "evt-" + expired);
		// QUEUED or SENDING is never deleted, however old (owner, H7).
		sql("UPDATE email_message SET created_at = now() - interval '400 days' WHERE id = ?", stillQueued);

		purges.purgeExpiredData();

		assertThat(row(kept)).isNotNull();
		assertThat(row(expired)).isNull();
		assertThat(count("SELECT count(*) FROM email_event WHERE message_id = ?", expired)).isZero();
		assertThat(count("SELECT count(*) FROM email_message_tag WHERE message_id = ?", expired))
			.as("tag rows cascade with the message")
			.isZero();
		assertThat(row(stillQueued)).isNotNull();
		assertThat(count("SELECT count(*) FROM email_message_tag WHERE message_id = ?", stillQueued)).isOne();
	}

	@Test
	void ac_23_7_renderedContentAndOrphanEventsGoAfterThirtyDays() throws Exception {
		UUID recent = failedDaysAgo(1);
		UUID old = failedDaysAgo(1);
		sql("UPDATE email_message SET rendered_html = '<p>x</p>', created_at = now() - interval '29 days' WHERE id = ?",
				recent);
		sql("UPDATE email_message SET rendered_html = '<p>x</p>', created_at = now() - interval '31 days' WHERE id = ?",
				old);
		String recentOrphan = "orphan-" + UUID.randomUUID();
		String oldOrphan = "orphan-" + UUID.randomUUID();
		for (Object[] orphan : new Object[][] { { recentOrphan, 29 }, { oldOrphan, 31 } }) {
			sql("""
					INSERT INTO email_event (id, provider, provider_event_id, type, provider_type, occurred_at, received_at)
					VALUES (gen_random_uuid(), 'resend', ?, 'OTHER', 'email.sent', now(), now() - ? * interval '1 day')
					""", orphan[0], orphan[1]);
		}

		purges.purgeExpiredData();

		assertThat(row(recent).get("rendered_html").stringValue()).isEqualTo("<p>x</p>");
		assertThat(row(old).get("rendered_html").isNull()).isTrue();
		assertThat(row(old)).as("the row itself stays until its retention").isNotNull();
		assertThat(count("SELECT count(*) FROM email_event WHERE provider_event_id = ?", recentOrphan)).isOne();
		assertThat(count("SELECT count(*) FROM email_event WHERE provider_event_id = ?", oldOrphan)).isZero();
	}

	@Test
	void ac_23_4_auditKeepsAYearAndIsPurgedOnlyThroughItsFunction() throws Exception {
		String recent = "audit-" + UUID.randomUUID();
		String old = "audit-" + UUID.randomUUID();
		for (Object[] entry : new Object[][] { { recent, 364 }, { old, 366 } }) {
			sql("""
					INSERT INTO audit_log (id, actor_type, action, resource_type, resource_id, created_at)
					VALUES (gen_random_uuid(), 'SYSTEM', 'ADMIN_ACCESS', 'test', ?, now() - ? * interval '1 day')
					""", entry[0], entry[1]);
		}

		purges.purgeExpiredData();

		assertThat(count("SELECT count(*) FROM audit_log WHERE resource_id = ?", recent)).isOne();
		assertThat(count("SELECT count(*) FROM audit_log WHERE resource_id = ?", old)).isZero();
	}

	@Test
	void ac_23_3_suppressionsAndRecentCountersAreKept() throws Exception {
		String address = SendingTestSupport.uniqueAddress();
		SendingTestSupport.suppress(address, "GLOBAL", null, "HARD_BOUNCE");
		sql("UPDATE suppression SET created_at = now() - interval '3 years' WHERE email_hash = ?",
				SendingTestSupport.hmac(address));
		sql("""
				INSERT INTO rate_limit_counter (tenant_id, window_kind, window_start, count) VALUES
				    (?, 'DAY', now() - interval '1 day', 1), (?, 'DAY', now() - interval '3 days', 1)
				""", a.tenant.id(), a.tenant.id());

		purges.purgeExpiredData();

		assertThat(count("SELECT count(*) FROM suppression WHERE email_hash = ?", SendingTestSupport.hmac(address)))
			.isOne();
		assertThat(count("SELECT count(*) FROM rate_limit_counter WHERE tenant_id = ? AND window_kind = 'DAY'",
				a.tenant.id()))
			.isOne();
	}

	@Test
	void ac_23_2_purgesAreIdempotent() throws Exception {
		failedDaysAgo(10);
		purges.purgeVariables();
		purges.purgeExpiredData();
		purges.purgeIdempotencyKeys();

		assertThat(purges.purgeVariables()).isZero();
		assertThat(purges.purgeIdempotencyKeys()).isZero();
	}

}
