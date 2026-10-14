package com.emailservice.events;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.emailservice.sending.SendingTestSupport;
import com.emailservice.support.IntegrationTest;
import com.emailservice.support.PostgresTestDatabase;
import com.emailservice.support.PostgresTestDatabase.Role;
import com.emailservice.support.Webhooks;

import tools.jackson.databind.JsonNode;

/** FR-10, AC-17.4 to AC-17.6 and ADR-0012: suppressions created and mirrored from provider events. */
class SuppressionEventTest extends IntegrationTest {

	Webhooks webhooks;

	SendingTestSupport a;

	@BeforeEach
	void setUp() throws Exception {
		webhooks = new Webhooks(http());
		a = new SendingTestSupport(http(), "SECURITY");
	}

	record Sent(UUID id, String emailId, String to) {
	}

	Sent sent(SendingTestSupport tenant, String status, String extra) throws Exception {
		String to = SendingTestSupport.uniqueAddress();
		HttpResponse<String> accepted = tenant.send(tenant.bodyTo(to, extra), null);
		assertThat(accepted.statusCode()).as(accepted.body()).isEqualTo(202);
		UUID id = UUID.fromString(SendingTestSupport.json(accepted).get("id").stringValue());
		String emailId = UUID.randomUUID().toString();
		WebhookTest.sql("""
				UPDATE email_message SET status = ?, provider = 'resend', provider_message_id = ?, sent_at = now(),
				    attempts = 1, first_attempt_at = now(),
				    failure_code = CASE WHEN ? = 'FAILED' THEN 'PROVIDER_REJECTED' END
				WHERE id = ?
				""", status, emailId, status, id);
		return new Sent(id, emailId, to);
	}

	void event(Sent message, String type) throws Exception {
		assertThat(webhooks.post(Webhooks.newEventId(),
				Webhooks.emailEvent(type, message.emailId(), message.id(), "", Instant.now(), message.to()))
			.statusCode()).isEqualTo(204);
	}

	void suppressionEvent(String type, String email) throws Exception {
		assertThat(webhooks.post(Webhooks.newEventId(), Webhooks.suppressionEvent(type, email, null)).statusCode())
			.isEqualTo(204);
	}

	/** The GLOBAL row for an address, as JSON, or null. */
	static JsonNode global(String address) throws Exception {
		try (Connection system = PostgresTestDatabase.connect(Role.SYSTEM);
				PreparedStatement statement = system.prepareStatement(
						"SELECT row_to_json(s)::text FROM suppression s WHERE scope = 'GLOBAL' AND email_hash = ?")) {
			statement.setBytes(1, SendingTestSupport.hmac(address));
			try (ResultSet rs = statement.executeQuery()) {
				return rs.next() ? SendingTestSupport.JSON.readTree(rs.getString(1)) : null;
			}
		}
	}

	static String status(Sent message) throws Exception {
		return SendingTestSupport.row(message.id()).get("status").stringValue();
	}

	@Test
	void ac_17_5_aComplaintOnADeliveredMessageCompletesItAndSuppressesGlobally() throws Exception {
		Sent message = sent(a, "DELIVERED", "");

		event(message, "email.complained");

		assertThat(status(message)).isEqualTo("COMPLAINED");
		JsonNode suppression = global(message.to());
		assertThat(suppression.get("reason").stringValue()).isEqualTo("COMPLAINT");
		assertThat(suppression.get("tenant_id").isNull()).isTrue();
		assertThat(suppression.get("source_tenant_id").stringValue()).isEqualTo(a.tenant.id().toString());
		assertThat(suppression.get("source_message_id").stringValue()).isEqualTo(message.id().toString());
		assertThat(suppression.get("email").stringValue()).isEqualTo(message.to());
	}

	@Test
	void ac_17_4_aBounceSuppressesEvenWhenItDoesNotChangeTheState() throws Exception {
		Sent failed = sent(a, "FAILED", "");

		event(failed, "email.bounced");

		assertThat(status(failed)).isEqualTo("FAILED");
		assertThat(global(failed.to()).get("reason").stringValue()).isEqualTo("HARD_BOUNCE");
	}

	@Test
	void ac_10_2_aGlobalSuppressionFromOneTenantBlocksAnother() throws Exception {
		Sent bounced = sent(a, "SENT", "");
		event(bounced, "email.bounced");
		SendingTestSupport b = new SendingTestSupport(http(), "SECURITY");

		HttpResponse<String> response = b.send(b.bodyTo(bounced.to(), ""), null);

		assertThat(response.statusCode()).as(response.body()).isEqualTo(202);
		JsonNode body = SendingTestSupport.json(response);
		assertThat(body.get("status").stringValue()).isEqualTo("FAILED");
		assertThat(body.get("failureCode").stringValue()).isEqualTo("SUPPRESSED");
		assertThat(body.get("suppressionReason").stringValue()).isEqualTo("HARD_BOUNCE");
	}

	@Test
	void ownerH6_withCopiesTheToIsNotGuessedButTheMessageStillBounces() throws Exception {
		String cc = SendingTestSupport.uniqueAddress();
		Sent message = sent(a, "SENT", "\"cc\": [\"" + cc + "\"]");

		event(message, "email.bounced");

		assertThat(status(message)).isEqualTo("BOUNCED");
		assertThat(global(message.to())).isNull();
		assertThat(global(cc)).isNull();

		// The provider names the exact address in its own list event.
		suppressionEvent("suppression.added", cc);
		assertThat(global(cc).get("reason").stringValue()).isEqualTo("PROVIDER");
		assertThat(global(message.to())).isNull();
	}

	@Test
	void ac_17_6_theProviderListIsMirroredAndOnlyThatMirrorIsLifted() throws Exception {
		String mirrored = SendingTestSupport.uniqueAddress();
		suppressionEvent("suppression.added", mirrored.toUpperCase());
		assertThat(global(mirrored).get("reason").stringValue()).isEqualTo("PROVIDER");
		assertThat(global(mirrored).get("email").stringValue()).isEqualTo(mirrored);

		suppressionEvent("suppression.removed", mirrored);
		assertThat(global(mirrored)).isNull();

		Sent bounced = sent(a, "SENT", "");
		event(bounced, "email.bounced");
		suppressionEvent("suppression.removed", bounced.to());
		assertThat(global(bounced.to()).get("reason").stringValue()).as("a hard bounce needs an administrator")
			.isEqualTo("HARD_BOUNCE");
	}

	@Test
	void docs02_21_providerSuppressedFailsTheMessageAndMirrorsTheAddress() throws Exception {
		Sent message = sent(a, "SENT", "");

		event(message, "email.suppressed");

		assertThat(status(message)).isEqualTo("FAILED");
		assertThat(global(message.to()).get("reason").stringValue()).isEqualTo("PROVIDER");
	}

	@Test
	void ac_10_4_aDeliveryDelayIsNotASuppression() throws Exception {
		Sent message = sent(a, "SENT", "");

		event(message, "email.delivery_delayed");

		assertThat(status(message)).isEqualTo("SENT");
		assertThat(global(message.to())).isNull();
	}

	@Test
	void ac_16_5_anOrphanBounceStillSuppressesTheReportedAddress() throws Exception {
		String to = SendingTestSupport.uniqueAddress();

		assertThat(webhooks.post(Webhooks.newEventId(), Webhooks.emailEvent("email.bounced",
				UUID.randomUUID().toString(), null, "", Instant.now(), to))
			.statusCode()).isEqualTo(204);

		JsonNode suppression = global(to);
		assertThat(suppression.get("reason").stringValue()).isEqualTo("HARD_BOUNCE");
		assertThat(suppression.get("source_tenant_id").isNull()).isTrue();
	}

	@Test
	void ac_16_4_aReplayedComplaintDoesNotDuplicateAnything() throws Exception {
		Sent message = sent(a, "DELIVERED", "");
		String eventId = Webhooks.newEventId();
		String body = Webhooks.emailEvent("email.complained", message.emailId(), message.id(), "", Instant.now(),
				message.to());

		webhooks.post(eventId, body);
		WebhookTest.sql("DELETE FROM suppression WHERE scope = 'GLOBAL' AND email_hash = ?",
				SendingTestSupport.hmac(message.to()));
		webhooks.post(eventId, body);

		assertThat(global(message.to())).as("a duplicate has no effects").isNull();
	}

}
