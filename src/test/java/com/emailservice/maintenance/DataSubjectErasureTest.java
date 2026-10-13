package com.emailservice.maintenance;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.HexFormat;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.emailservice.sending.SendingTestSupport;
import com.emailservice.support.AuditRows;
import com.emailservice.support.IntegrationTest;
import com.emailservice.support.IntegrationTestEnvironment;
import com.emailservice.support.TestHttp;

import tools.jackson.databind.JsonNode;

/** FR-29: a data subject's erasure across every tenant, idempotent and audited without the address. */
class DataSubjectErasureTest extends IntegrationTest {

	SendingTestSupport a;

	SendingTestSupport b;

	String subject;

	@BeforeEach
	void setUp() throws Exception {
		a = new SendingTestSupport(http(), "SECURITY");
		b = new SendingTestSupport(http(), "SECURITY");
		subject = SendingTestSupport.uniqueAddress();
	}

	UUID accept(SendingTestSupport tenant, String to, String extra) throws Exception {
		HttpResponse<String> response = tenant.send(tenant.bodyTo(to, extra), null);
		assertThat(response.statusCode()).as(response.body()).isEqualTo(202);
		return UUID.fromString(SendingTestSupport.json(response).get("id").stringValue());
	}

	TestHttp.Request admin(TestHttp.Request request) {
		return request.header("X-Admin-Key", IntegrationTestEnvironment.ADMIN_API_KEY);
	}

	HttpResponse<String> erase(String email) throws Exception {
		return admin(http().post("/admin/v1/data-subjects/erase"))
			.json("{\"email\": \"" + email + "\", \"reference\": \"ARCO-2026-014\"}")
			.send();
	}

	@Test
	void ac_29_1_erasesMessagesEventsTagsAndCopiesInEveryTenant() throws Exception {
		UUID inA = accept(a, subject, "\"tags\": [\"invoice\", \"reset\"]");
		UUID inB = accept(b, subject.toUpperCase(), "");
		UUID copied = accept(a, SendingTestSupport.uniqueAddress(),
				"\"cc\": [\"" + subject.toUpperCase() + "\", \"keep@example.test\"], \"bcc\": [\"" + subject + "\"]");
		RetentionPurgeTest.sql("""
				INSERT INTO email_event (id, tenant_id, message_id, provider, provider_event_id, type, provider_type,
				    occurred_at)
				VALUES (gen_random_uuid(), ?, ?, 'resend', ?, 'DELIVERED', 'email.delivered', now())
				""", a.tenant.id(), inA, "evt-" + inA);

		HttpResponse<String> response = erase(subject);

		assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
		JsonNode result = SendingTestSupport.json(response);
		assertThat(result.at("/deleted/email_message").longValue()).isEqualTo(2);
		assertThat(result.at("/deleted/email_event").longValue()).isOne();
		assertThat(result.at("/deleted/email_message_tag").longValue()).isEqualTo(2);
		assertThat(result.get("copiesRemoved").longValue()).isOne();
		assertThat(SendingTestSupport.row(inA)).isNull();
		assertThat(SendingTestSupport.row(inB)).isNull();
		assertThat(RetentionPurgeTest.count("SELECT count(*) FROM email_message_tag WHERE message_id = ?", inA))
			.as("tag rows cascade with the message")
			.isZero();
		JsonNode other = SendingTestSupport.row(copied);
		assertThat(other.get("cc").toString()).isEqualTo("[\"keep@example.test\"]");
		assertThat(other.get("bcc").toString()).isEqualTo("[]");
		assertThat(result.has("email_attachment")).isFalse();
	}

	@Test
	void ac_29_1_suppressionsKeepOnlyTheHashAndStillBlock() throws Exception {
		SendingTestSupport.suppress(subject, "GLOBAL", null, "COMPLAINT");

		JsonNode result = SendingTestSupport.json(erase(subject));

		assertThat(result.get("suppressionsAnonymized").longValue()).isOne();
		assertThat(RetentionPurgeTest.count(
				"SELECT count(*) FROM suppression WHERE email_hash = ? AND email IS NULL", SendingTestSupport.hmac(subject)))
			.isOne();
		HttpResponse<String> blocked = a.send(a.bodyTo(subject, ""), null);
		assertThat(SendingTestSupport.json(blocked).get("failureCode").stringValue()).isEqualTo("SUPPRESSED");
	}

	@Test
	void ac_29_2_auditedWithTheHashAndReferenceNeverTheAddress() throws Exception {
		accept(a, subject, "");

		erase(subject);

		String hash = HexFormat.of().formatHex(SendingTestSupport.hmac(subject));
		assertThat(RetentionPurgeTest.count("""
				SELECT count(*) FROM audit_log
				WHERE action = 'DATA_SUBJECT_ERASED' AND resource_id = ? AND metadata ->> 'reference' = 'ARCO-2026-014'
				  AND metadata::text NOT LIKE ?
				""", hash, "%" + subject + "%")).isOne();
		assertThat(AuditRows.forTenant(a.tenant.id()).toString()).doesNotContain(subject);
	}

	@Test
	void ac_29_3_aSecondErasureFindsNothingLeft() throws Exception {
		accept(a, subject, "");
		erase(subject);

		JsonNode again = SendingTestSupport.json(erase(subject));

		assertThat(again.at("/deleted/email_message").longValue()).isZero();
		assertThat(again.at("/deleted/email_event").longValue()).isZero();
		assertThat(again.get("copiesRemoved").longValue()).isZero();
		assertThat(again.get("suppressionsAnonymized").longValue()).isZero();
	}

	@Test
	void onlyAdministratorsWithAValidAddress() throws Exception {
		assertThat(erase("not-an-address").statusCode()).isEqualTo(422);
		HttpResponse<String> asTenant = http().post("/admin/v1/data-subjects/erase")
			.header("Authorization", a.product.bearer())
			.json("{\"email\": \"" + subject + "\", \"reference\": \"x\"}")
			.send();
		assertThat(asTenant.statusCode()).isEqualTo(403);
		assertThat(admin(http().post("/admin/v1/data-subjects/erase")).json("{\"email\": \"" + subject + "\"}").send()
			.statusCode()).as("the reference is required").isEqualTo(422);
	}

}
