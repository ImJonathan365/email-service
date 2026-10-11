package com.emailservice.events;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.emailservice.sending.SendingTestSupport;
import com.emailservice.support.IntegrationTest;
import com.emailservice.support.Webhooks;

import io.micrometer.core.instrument.MeterRegistry;
import tools.jackson.databind.JsonNode;

/** FR-17 and docs/02 §2: state changes by precedence, from signed webhooks against real rows. */
class EventStateTest extends IntegrationTest {

	@Autowired
	MeterRegistry meters;

	Webhooks webhooks;

	SendingTestSupport tenant;

	@BeforeEach
	void setUp() throws Exception {
		webhooks = new Webhooks(http());
		tenant = new SendingTestSupport(http(), "SECURITY");
	}

	record Sent(UUID id, String emailId) {
	}

	Sent message(String status) throws Exception {
		HttpResponse<String> accepted = tenant.send(tenant.body(""), null);
		assertThat(accepted.statusCode()).as(accepted.body()).isEqualTo(202);
		UUID id = UUID.fromString(SendingTestSupport.json(accepted).get("id").stringValue());
		String emailId = UUID.randomUUID().toString();
		WebhookTest.sql("""
				UPDATE email_message SET status = ?, provider = 'resend', provider_message_id = ?, sent_at = now(),
				    attempts = 1, first_attempt_at = now(),
				    delivered_at = CASE WHEN ? = 'DELIVERED' THEN now() END
				WHERE id = ?
				""", status, emailId, status, id);
		return new Sent(id, emailId);
	}

	void deliver(Sent message, String type, String extra) throws Exception {
		deliver(message, type, extra, Instant.now());
	}

	void deliver(Sent message, String type, String extra, Instant at) throws Exception {
		HttpResponse<String> response = webhooks.post(Webhooks.newEventId(),
				Webhooks.emailEvent(type, message.emailId(), message.id(), extra, at));
		assertThat(response.statusCode()).as(response.body()).isEqualTo(204);
	}

	static JsonNode row(Sent message) throws Exception {
		return SendingTestSupport.row(message.id());
	}

	static String status(Sent message) throws Exception {
		return row(message).get("status").stringValue();
	}

	static long events(Sent message) throws Exception {
		try (var system = com.emailservice.support.PostgresTestDatabase
			.connect(com.emailservice.support.PostgresTestDatabase.Role.SYSTEM);
				var statement = system.prepareStatement("SELECT count(*) FROM email_event WHERE message_id = ?")) {
			statement.setObject(1, message.id());
			try (var rs = statement.executeQuery()) {
				rs.next();
				return rs.getLong(1);
			}
		}
	}

	@Test
	void docs02_21_deliveredMovesSentForwardAndRecordsWhen() throws Exception {
		Sent message = message("SENT");
		Instant at = Instant.parse("2026-10-11T10:00:01.123456Z");

		deliver(message, "email.delivered", "", at);

		JsonNode row = row(message);
		assertThat(row.get("status").stringValue()).isEqualTo("DELIVERED");
		assertThat(OffsetDateTime.parse(row.get("delivered_at").stringValue()).toInstant()).isEqualTo(at);
		assertThat(row.get("finalized_at").isNull()).as("DELIVERED is stable, not terminal").isTrue();
	}

	@Test
	void ac_17_2_aDeliveredAfterABounceIsStoredButDoesNotGoBack() throws Exception {
		Sent message = message("SENT");

		deliver(message, "email.bounced", "\"bounce\": {\"type\": \"Permanent\", \"subType\": \"General\"}");
		deliver(message, "email.delivered", "");

		assertThat(status(message)).isEqualTo("BOUNCED");
		assertThat(row(message).get("delivered_at").isNull()).isTrue();
		assertThat(row(message).get("finalized_at").isNull()).isFalse();
		assertThat(events(message)).isEqualTo(2);
	}

	@Test
	void ac_17_5_aComplaintOnADeliveredMessageMovesItToComplained() throws Exception {
		Sent message = message("DELIVERED");
		double before = meters.counter("email.complaints", "tenant", tenant.tenant.slug()).count();

		deliver(message, "email.complained", "");

		assertThat(status(message)).isEqualTo("COMPLAINED");
		assertThat(meters.counter("email.complaints", "tenant", tenant.tenant.slug()).count() - before).isEqualTo(1.0);
	}

	@Test
	void docs02_21_bounceAlsoLeavesDeliveredButNothingLeavesATerminalState() throws Exception {
		Sent delivered = message("DELIVERED");
		deliver(delivered, "email.bounced", "");
		assertThat(status(delivered)).isEqualTo("BOUNCED");

		deliver(delivered, "email.complained", "");
		deliver(delivered, "email.failed", "");
		assertThat(status(delivered)).isEqualTo("BOUNCED");
	}

	@Test
	void docs02_21_failedAndSuppressedFailASentMessageWithTheirCodes() throws Exception {
		Sent failed = message("SENT");
		deliver(failed, "email.failed", "\"failed\": {\"reason\": \"reached_daily_quota\"}");
		assertThat(status(failed)).isEqualTo("FAILED");
		assertThat(row(failed).get("failure_code").stringValue()).isEqualTo("PROVIDER_FAILED");

		Sent suppressed = message("SENT");
		deliver(suppressed, "email.suppressed", "\"suppressed\": {\"type\": \"OnAccountSuppressionList\"}");
		assertThat(status(suppressed)).isEqualTo("FAILED");
		assertThat(row(suppressed).get("failure_code").stringValue()).isEqualTo("PROVIDER_SUPPRESSED");
		assertThat(row(suppressed).get("finalized_at").isNull()).isFalse();

		Sent delivered = message("DELIVERED");
		deliver(delivered, "email.failed", "");
		assertThat(status(delivered)).as("failed only leaves SENT").isEqualTo("DELIVERED");
	}

	@Test
	void ac_17_3_opensAndClicksAreCountersOnlyWhenTrackingIsOn() throws Exception {
		Sent untracked = message("DELIVERED");
		deliver(untracked, "email.opened", "\"open\": {\"timestamp\": \"2026-10-11T10:00:00Z\"}");
		assertThat(row(untracked).get("open_count").intValue()).isZero();

		Sent tracked = message("DELIVERED");
		WebhookTest.sql("UPDATE email_message SET tracking_enabled = true WHERE id = ?", tracked.id());
		Instant first = Instant.now().truncatedTo(ChronoUnit.MILLIS);
		deliver(tracked, "email.opened", "", first);
		deliver(tracked, "email.opened", "", first.plusSeconds(60));
		deliver(tracked, "email.clicked", "\"click\": {\"link\": \"https://app.example.test/x?t=1\"}");

		JsonNode row = row(tracked);
		assertThat(row.get("status").stringValue()).isEqualTo("DELIVERED");
		assertThat(row.get("open_count").intValue()).isEqualTo(2);
		assertThat(OffsetDateTime.parse(row.get("first_opened_at").stringValue()).toInstant()).isEqualTo(first);
		assertThat(row.get("click_count").intValue()).isOne();
	}

	@Test
	void anEventForAMessageTheWorkerStillOwnsLeavesItsStateAlone() throws Exception {
		Sent sending = message("SENT");
		WebhookTest.sql("""
				UPDATE email_message SET status = 'SENDING', provider_message_id = NULL, provider = NULL,
				    sent_at = NULL, lock_token = gen_random_uuid(), lock_expires_at = now() + interval '1 minute'
				WHERE id = ?
				""", sending.id());

		deliver(sending, "email.delivered", "");

		assertThat(status(sending)).isEqualTo("SENDING");
		assertThat(events(sending)).isOne();
	}

	@Test
	void ownerH6_anEventForAMessageWeFailedIsFlaggedNotApplied() throws Exception {
		Sent message = message("SENT");
		WebhookTest.sql("""
				UPDATE email_message SET status = 'FAILED', failure_code = 'RETRY_WINDOW_EXCEEDED',
				    finalized_at = now()
				WHERE id = ?
				""", message.id());
		double before = meters.counter("email.event.after.failure").count();

		deliver(message, "email.delivered", "");

		assertThat(status(message)).isEqualTo("FAILED");
		assertThat(meters.counter("email.event.after.failure").count() - before).isEqualTo(1.0);
	}

}
