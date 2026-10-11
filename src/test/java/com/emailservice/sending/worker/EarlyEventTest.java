package com.emailservice.sending.worker;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.emailservice.provider.SendException;
import com.emailservice.provider.SendResult;
import com.emailservice.sending.SendingTestSupport;
import com.emailservice.support.Webhooks;

import io.micrometer.core.instrument.MeterRegistry;
import tools.jackson.databind.JsonNode;

/**
 * An event that arrives while the worker still owns the message (owner decision, H6): it is stored
 * without touching the state and applied by the worker right after its own fenced close.
 */
class EarlyEventTest extends WorkerTest {

	@Autowired
	MeterRegistry meters;

	SendingTestSupport a;

	Webhooks webhooks;

	@BeforeEach
	void setUp() throws Exception {
		a = new SendingTestSupport(http(), "SECURITY");
		webhooks = new Webhooks(http());
	}

	UUID accept() throws Exception {
		HttpResponse<String> response = a.send(a.body(""), null);
		assertThat(response.statusCode()).as(response.body()).isEqualTo(202);
		return UUID.fromString(SendingTestSupport.json(response).get("id").stringValue());
	}

	/** The provider's webhook, posted while the message is SENDING (inside the provider call). */
	void webhookDuringSend(UUID id, String type, Instant at) throws Exception {
		assertThat(SendingTestSupport.row(id).get("status").stringValue()).isEqualTo("SENDING");
		HttpResponse<String> response = webhooks.post(Webhooks.newEventId(),
				Webhooks.emailEvent(type, "stub-" + id, id, "", at));
		assertThat(response.statusCode()).isEqualTo(204);
		assertThat(SendingTestSupport.row(id).get("status").stringValue()).as("the webhook left it alone")
			.isEqualTo("SENDING");
	}

	@Test
	void ownerH6_aDeliveredReceivedWhileSendingIsAppliedWhenTheWorkerCloses() throws Exception {
		UUID id = accept();
		Instant deliveredAt = Instant.parse("2026-10-11T10:00:02.250Z");
		sender.behave(email -> {
			try {
				webhookDuringSend(id, "email.delivered", deliveredAt);
			}
			catch (Exception ex) {
				throw new IllegalStateException(ex);
			}
			return new SendResult("stub", "stub-" + id);
		});

		worker.runOnce();

		JsonNode row = SendingTestSupport.row(id);
		assertThat(row.get("status").stringValue()).isEqualTo("DELIVERED");
		assertThat(OffsetDateTime.parse(row.get("delivered_at").stringValue()).toInstant()).isEqualTo(deliveredAt);
		assertThat(row.get("provider_message_id").stringValue()).isEqualTo("stub-" + id);
		assertThat(row.get("lock_token").isNull()).isTrue();
	}

	@Test
	void ownerH6_aBounceReceivedWhileSendingLeavesTheMessageBounced() throws Exception {
		UUID id = accept();
		sender.behave(email -> {
			try {
				webhookDuringSend(id, "email.delivered", Instant.now());
				webhookDuringSend(id, "email.bounced", Instant.now().plusMillis(5));
			}
			catch (Exception ex) {
				throw new IllegalStateException(ex);
			}
			return new SendResult("stub", "stub-" + id);
		});

		worker.runOnce();

		assertThat(SendingTestSupport.row(id).get("status").stringValue()).isEqualTo("BOUNCED");
	}

	@Test
	void ownerH6_aMessageFailedByUsThatTheProviderHandledIsFlagged() throws Exception {
		UUID id = accept();
		// The response is lost, but the provider sent it and reported delivery.
		sender.behave(email -> {
			try {
				webhookDuringSend(id, "email.delivered", Instant.now());
			}
			catch (Exception ex) {
				throw new IllegalStateException(ex);
			}
			throw SendException.transientFailure("PROVIDER_TIMEOUT", "timeout", null);
		});
		worker.runOnce();
		assertThat(SendingTestSupport.row(id).get("status").stringValue()).isEqualTo("QUEUED");

		// Then the retry window runs out before the next attempt.
		sql("UPDATE email_message SET first_attempt_at = now() - interval '24 hours', next_attempt_at = now() "
				+ "WHERE id = ?", id);
		double before = meters.counter("email.event.after.failure").count();
		worker.runOnce();

		JsonNode row = SendingTestSupport.row(id);
		assertThat(row.get("status").stringValue()).isEqualTo("FAILED");
		assertThat(row.get("failure_code").stringValue()).isEqualTo("RETRY_WINDOW_EXCEEDED");
		assertThat(meters.counter("email.event.after.failure").count() - before).isEqualTo(1.0);
	}

}
