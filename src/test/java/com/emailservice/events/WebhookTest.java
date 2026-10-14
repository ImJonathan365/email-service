package com.emailservice.events;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.emailservice.sending.SendingTestSupport;
import com.emailservice.support.IntegrationTest;
import com.emailservice.support.IntegrationTestEnvironment;
import com.emailservice.support.PostgresTestDatabase;
import com.emailservice.support.PostgresTestDatabase.Role;
import com.emailservice.support.Webhooks;

import tools.jackson.databind.JsonNode;

/** FR-16 and AC-17.1 end to end: signed requests built in the test with the fictional secret. */
class WebhookTest extends IntegrationTest {

	Webhooks webhooks;

	@BeforeEach
	void setUp() {
		webhooks = new Webhooks(http());
	}

	static String unknownEmailId() {
		return UUID.randomUUID().toString();
	}

	/** A message of a fresh tenant, already SENT with the given provider id. */
	UUID sentMessage(String providerMessageId) throws Exception {
		SendingTestSupport tenant = new SendingTestSupport(http(), "SECURITY");
		HttpResponse<String> accepted = tenant.send(tenant.body(""), null);
		assertThat(accepted.statusCode()).as(accepted.body()).isEqualTo(202);
		UUID id = UUID.fromString(SendingTestSupport.json(accepted).get("id").stringValue());
		sql("""
				UPDATE email_message SET status = 'SENT', provider = 'resend', provider_message_id = ?, sent_at = now(),
				    attempts = 1, first_attempt_at = now()
				WHERE id = ?
				""", providerMessageId, id);
		return id;
	}

	static void sql(String statement, Object... params) throws Exception {
		try (var system = PostgresTestDatabase.connect(Role.SYSTEM); var prepared = system.prepareStatement(statement)) {
			for (int i = 0; i < params.length; i++) {
				prepared.setObject(i + 1, params[i]);
			}
			prepared.executeUpdate();
		}
	}

	@Test
	void ac_16_1_aValidSignatureIsAcceptedAndTheEventStored() throws Exception {
		String emailId = unknownEmailId();
		UUID message = sentMessage(emailId);
		String eventId = Webhooks.newEventId();

		HttpResponse<String> response = webhooks.post(eventId, Webhooks.emailEvent("email.sent", emailId, null, ""));

		assertThat(response.statusCode()).as(response.body()).isEqualTo(204);
		JsonNode stored = Webhooks.storedEvent(eventId);
		assertThat(stored.get("type").stringValue()).isEqualTo("SENT");
		assertThat(stored.get("provider_type").stringValue()).isEqualTo("email.sent");
		assertThat(stored.get("provider").stringValue()).isEqualTo("resend");
		assertThat(stored.get("message_id").stringValue()).isEqualTo(message.toString());
	}

	@Test
	void ac_16_2_anInvalidOrMissingSignatureIsRejectedAndNothingStored() throws Exception {
		String body = Webhooks.emailEvent("email.delivered", unknownEmailId(), null, "");
		long now = Instant.now().getEpochSecond();

		String forged = Webhooks.newEventId();
		assertThat(webhooks.post(forged, now, "v1,bm90LXRoZS1zaWduYXR1cmU=", body).statusCode()).isEqualTo(401);
		String unsigned = Webhooks.newEventId();
		HttpResponse<String> missing = webhooks.post(unsigned, now, null, body);
		assertThat(missing.statusCode()).isEqualTo(401);
		assertThat(missing.body()).contains("UNAUTHENTICATED");
		// Signed for a different body: verification is over the raw bytes received.
		String tampered = Webhooks.newEventId();
		String signature = Webhooks.sign(IntegrationTestEnvironment.WEBHOOK_SECRET, tampered, now, body);
		assertThat(webhooks.post(tampered, now, signature, body.replace("email.delivered", "email.bounced"))
			.statusCode()).isEqualTo(401);

		assertThat(Webhooks.countEvents(forged) + Webhooks.countEvents(unsigned) + Webhooks.countEvents(tampered))
			.isZero();
	}

	@Test
	void ac_16_3_aSignatureOutsideTheWindowIsRejected() throws Exception {
		String body = Webhooks.emailEvent("email.delivered", unknownEmailId(), null, "");
		// Far outside the window, so request latency cannot matter; the exact 300/301 s boundary is
		// SvixWebhookVerifierTest's, on a fixed clock.
		long now = Instant.now().getEpochSecond();
		for (long timestamp : new long[] { now - 600, now + 600 }) {
			String eventId = Webhooks.newEventId();
			String signature = Webhooks.sign(IntegrationTestEnvironment.WEBHOOK_SECRET, eventId, timestamp, body);
			assertThat(webhooks.post(eventId, timestamp, signature, body).statusCode()).isEqualTo(401);
			assertThat(Webhooks.countEvents(eventId)).isZero();
		}
	}

	@Test
	void ac_16_4_aReplayedEventIdIsAcknowledgedAndStoredOnce() throws Exception {
		String eventId = Webhooks.newEventId();
		String body = Webhooks.emailEvent("email.delivery_delayed", unknownEmailId(), null, "");

		assertThat(webhooks.post(eventId, body).statusCode()).isEqualTo(204);
		assertThat(webhooks.post(eventId, body).statusCode()).isEqualTo(204);

		assertThat(Webhooks.countEvents(eventId)).isOne();
	}

	@Test
	void ac_16_5_anUncorrelatableEventIsStoredAsAnOrphanWith204() throws Exception {
		String eventId = Webhooks.newEventId();

		HttpResponse<String> response = webhooks.post(eventId,
				Webhooks.emailEvent("email.delivered", unknownEmailId(), UUID.randomUUID(), ""));

		assertThat(response.statusCode()).isEqualTo(204);
		JsonNode stored = Webhooks.storedEvent(eventId);
		assertThat(stored.get("message_id").isNull()).isTrue();
		assertThat(stored.get("tenant_id").isNull()).isTrue();
		assertThat(stored.get("type").stringValue()).isEqualTo("DELIVERED");
	}

	@Test
	void anAuthenticButUnreadableBodyIs400() throws Exception {
		assertThat(webhooks.post(Webhooks.newEventId(), "not json").statusCode()).isEqualTo(400);
		assertThat(webhooks.post(Webhooks.newEventId(), "{\"data\": {}}").statusCode()).isEqualTo(400);
	}

	@Test
	void unknownProvidersDoNotExist() throws Exception {
		assertThat(http().post("/webhooks/postmark").json("{}").send().statusCode()).isEqualTo(404);
	}

	@Test
	void ac_17_1_onlyTheMinimizedPayloadIsStored() throws Exception {
		String eventId = Webhooks.newEventId();
		String body = Webhooks.emailEvent("email.clicked", unknownEmailId(), null, """
				"click": {"ipAddress": "122.115.53.11", "link": "https://app.example.test/reset?t=secret-token#top",
				          "timestamp": "2026-10-11T10:05:00.000Z", "userAgent": "Mozilla/5.0"}""");

		assertThat(webhooks.post(eventId, body).statusCode()).isEqualTo(204);

		JsonNode payload = Webhooks.storedEvent(eventId).get("payload");
		assertThat(payload.toString()).doesNotContain("ana@example.test", "Hola Ana", "no-reply@sender.test",
				"122.115.53.11", "Mozilla", "secret-token", "111-222@", "category");
		assertThat(payload.at("/data/click/link").stringValue()).isEqualTo("https://app.example.test/reset");
		assertThat(payload.at("/data/click/timestamp").stringValue()).isEqualTo("2026-10-11T10:05:00.000Z");
		assertThat(payload.get("type").stringValue()).isEqualTo("email.clicked");
	}

	@Test
	void theMessageIsFoundByOurTagEvenBeforeTheProviderIdIsStored() throws Exception {
		UUID message = sentMessage(unknownEmailId());
		sql("UPDATE email_message SET provider_message_id = NULL, provider = NULL, status = 'QUEUED', sent_at = NULL "
				+ "WHERE id = ?", message);
		String eventId = Webhooks.newEventId();

		assertThat(webhooks.post(eventId, Webhooks.emailEvent("email.sent", unknownEmailId(), message, ""))
			.statusCode()).isEqualTo(204);

		assertThat(Webhooks.storedEvent(eventId).get("message_id").stringValue()).isEqualTo(message.toString());
	}

	@Test
	void tagAndProviderIdNamingDifferentMessagesAreStoredAsAnOrphan() throws Exception {
		String emailIdOfA = unknownEmailId();
		sentMessage(emailIdOfA);
		UUID b = sentMessage(unknownEmailId());
		String eventId = Webhooks.newEventId();

		assertThat(webhooks.post(eventId, Webhooks.emailEvent("email.delivered", emailIdOfA, b, "")).statusCode())
			.isEqualTo(204);

		assertThat(Webhooks.storedEvent(eventId).get("message_id").isNull()).isTrue();
	}

}
