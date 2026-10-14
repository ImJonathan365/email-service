package com.emailservice.sending.worker;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.emailservice.provider.SendException;
import com.emailservice.sending.SendingTestSupport;

import tools.jackson.databind.JsonNode;

/** FR-31: scheduled sending, measured with the application clock (docs/05, time source). */
class SendAtTest extends WorkerTest {

	/** Thirty seconds into a minute: every boundary below is exact. */
	static final Instant FIXED = Instant.parse("2031-03-04T10:20:30Z");

	SendingTestSupport a;

	@BeforeEach
	void setUp() throws Exception {
		a = new SendingTestSupport(http(), "SECURITY");
	}

	@AfterEach
	void restoreClock() {
		clock.reset();
	}

	HttpResponse<String> send(Object sendAt) throws Exception {
		return a.send(a.body("\"sendAt\": \"" + sendAt + "\""), null);
	}

	UUID accepted(HttpResponse<String> response) {
		assertThat(response.statusCode()).as(response.body()).isEqualTo(202);
		return UUID.fromString(SendingTestSupport.json(response).get("id").stringValue());
	}

	static void becomeDue(UUID id) throws Exception {
		// The queue compares next_attempt_at with the database clock: the scheduled time has come.
		sql("UPDATE email_message SET next_attempt_at = now() - interval '1 second' WHERE id = ?", id);
	}

	@Test
	void ac_31_1_theWindowIsNowToThirtyDaysWithSixtySecondsOfPastTolerance() throws Exception {
		clock.fixAt(FIXED);

		assertThat(send(FIXED).statusCode()).as("now").isEqualTo(202);
		assertThat(send(FIXED.minusSeconds(60)).statusCode()).as("60 s in the past counts as now").isEqualTo(202);
		assertThat(send(FIXED.plus(Duration.ofDays(30))).statusCode()).as("30 days ahead").isEqualTo(202);

		for (Instant outside : new Instant[] { FIXED.minusSeconds(61), FIXED.plus(Duration.ofDays(30)).plusSeconds(1) }) {
			HttpResponse<String> refused = send(outside);
			assertThat(refused.statusCode()).as(outside.toString()).isEqualTo(422);
			assertThat(refused.body()).contains("SEND_AT_OUT_OF_RANGE");
		}
	}

	@Test
	void ac_31_1_aTimeWithoutAZoneOrNotATimeIsInvalid() throws Exception {
		for (String value : new String[] { "2031-03-04T10:20:30", "tomorrow" }) {
			HttpResponse<String> response = send(value);
			assertThat(response.statusCode()).as(value).isEqualTo(422);
			assertThat(response.body()).contains("VALIDATION_ERROR", "sendAt");
		}
	}

	@Test
	void ac_31_1_aScheduledMessageWaitsForItsTimeAndIsVisibleAsSuch() throws Exception {
		OffsetDateTime at = OffsetDateTime.now().plusHours(2).truncatedTo(ChronoUnit.SECONDS);
		HttpResponse<String> response = send(at);
		UUID id = accepted(response);
		assertThat(SendingTestSupport.json(response).get("sendAt").stringValue()).isEqualTo(at.toInstant().toString());

		assertThat(worker.runOnce()).isEmpty();
		JsonNode detail = SendingTestSupport.json(
				http().get("/v1/emails/" + id).header("Authorization", a.product.bearer()).send());
		assertThat(detail.get("status").stringValue()).isEqualTo("QUEUED");
		assertThat(detail.get("sendAt").stringValue()).isEqualTo(at.toInstant().toString());

		becomeDue(id);
		assertThat(worker.runOnce()).extracting(QueueRepository.Claimed::id).containsExactly(id);
		assertThat(SendingTestSupport.row(id).get("status").stringValue()).isEqualTo("SENT");
	}

	@Test
	void ac_31_1_withinTheToleranceItGoesOutAtOnce() throws Exception {
		UUID id = accepted(send(Instant.now().minusSeconds(30)));

		assertThat(worker.runOnce()).extracting(QueueRepository.Claimed::id).containsExactly(id);
	}

	@Test
	void ac_31_2_theTemplateVersionIsPinnedAtAcceptance() throws Exception {
		UUID id = accepted(send(Instant.now().plus(Duration.ofHours(1))));
		int next = a.createVersion(a.templateKey, "es-CR", "Nuevo asunto {{firstName}}");
		HttpResponse<String> published = a.operator(http().post("/v1/templates/" + a.templateKey + "/publish"))
			.json("{\"versions\": {\"es-CR\": " + next + "}}")
			.send();
		assertThat(published.statusCode()).as(published.body()).isEqualTo(200);

		becomeDue(id);
		worker.runOnce();

		assertThat(sender.sent()).singleElement().satisfies(email -> assertThat(email.subject()).isEqualTo("Hola Ana"));
	}

	@Test
	void ac_31_4_theRetryWindowStartsAtTheFirstAttemptNotAtAcceptance() throws Exception {
		UUID id = accepted(send(Instant.now().plus(Duration.ofDays(2))));
		// Accepted two days ago, its time has come now.
		sql("UPDATE email_message SET created_at = now() - interval '2 days', next_attempt_at = now() WHERE id = ?", id);
		sender.behave(email -> {
			throw SendException.transientFailure("PROVIDER_UNAVAILABLE", "down", null);
		});

		worker.runOnce();

		JsonNode row = SendingTestSupport.row(id);
		assertThat(row.get("status").stringValue()).as("retried, not RETRY_WINDOW_EXCEEDED").isEqualTo("QUEUED");
		assertThat(row.get("attempts").intValue()).isOne();
	}

	@Test
	void ac_34_4_aScheduledMessageOfAPausedTenantWaitsAndLeavesWhenResumed() throws Exception {
		UUID id = accepted(send(Instant.now().plus(Duration.ofHours(1))));
		sql("UPDATE tenant SET sending_paused_at = now(), pause_reason = 'MANUAL' WHERE id = ?", a.tenant.id());
		becomeDue(id);

		assertThat(worker.runOnce()).isEmpty();
		assertThat(SendingTestSupport.row(id).get("status").stringValue()).as("held, never FAILED").isEqualTo("QUEUED");

		assertThat(a.admin.resumeSending(a.tenant, "{}").statusCode()).isEqualTo(200);
		assertThat(worker.runOnce()).extracting(QueueRepository.Claimed::id).containsExactly(id);
		assertThat(SendingTestSupport.row(id).get("status").stringValue()).isEqualTo("SENT");
	}

	@Test
	void fr_08_aRepeatedScheduledRequestAnswersWithItsSendAt() throws Exception {
		Instant at = Instant.now().plus(Duration.ofHours(3)).truncatedTo(ChronoUnit.SECONDS);
		String body = a.body("\"sendAt\": \"" + at + "\"");
		String key = "scheduled-" + UUID.randomUUID();
		UUID id = accepted(a.send(body, key));

		HttpResponse<String> repeat = a.send(body, key);

		assertThat(repeat.statusCode()).isEqualTo(200);
		assertThat(SendingTestSupport.json(repeat).get("id").stringValue()).isEqualTo(id.toString());
		assertThat(SendingTestSupport.json(repeat).get("sendAt").stringValue()).isEqualTo(at.toString());
		assertThat(a.send(a.body("\"sendAt\": \"" + at.plusSeconds(60) + "\""), key).statusCode())
			.as("same key, another time")
			.isEqualTo(409);
	}

}
