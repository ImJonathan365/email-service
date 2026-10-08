package com.emailservice.sending.worker;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.jsoup.Jsoup;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.emailservice.provider.OutboundEmail;
import com.emailservice.provider.SendException;
import com.emailservice.sending.SendingTestSupport;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import tools.jackson.databind.JsonNode;

/** FR-11, FR-12, FR-13 and AC-23.5 with a stub provider: the queue end to end, without any real email. */
class QueueWorkerTest extends WorkerTest {

	@Autowired
	MeterRegistry meters;

	SendingTestSupport a;

	@BeforeEach
	void setUp() throws Exception {
		a = new SendingTestSupport(http(), "SECURITY");
	}

	UUID accept(String extra) throws Exception {
		HttpResponse<String> response = a.send(a.body(extra), null);
		assertThat(response.statusCode()).as(response.body()).isEqualTo(202);
		return UUID.fromString(SendingTestSupport.json(response).get("id").stringValue());
	}

	JsonNode row(UUID id) throws Exception {
		return SendingTestSupport.row(id);
	}

	@Test
	void ac_12_2_sendsThePinnedVersionWithTheMessageIdAsIdempotencyKey() throws Exception {
		UUID id = accept("\"cc\": [\"cc@example.test\"], \"tags\": [\"reset\"]");

		List<QueueRepository.Claimed> claimed = worker.runOnce();

		assertThat(claimed).extracting(QueueRepository.Claimed::id).containsExactly(id);
		assertThat(sender.sent()).hasSize(1);
		OutboundEmail email = sender.sent().getFirst();
		assertThat(email.messageId()).isEqualTo(id);
		assertThat(email.subject()).isEqualTo("Hola Ana");
		// HTML escaping encodes '=' as &#x3D; inside the attribute; clients decode it back.
		assertThat(Jsoup.parse(email.html()).selectFirst("a").attr("href"))
			.isEqualTo("https://app.example.test/reset?t=1");
		assertThat(email.to().email()).isEqualTo("ana@example.test");
		assertThat(email.from().email()).isEqualTo("no-reply@sender.test");
		assertThat(email.cc()).containsExactly("cc@example.test");
		assertThat(email.tags()).containsExactly("reset");

		JsonNode row = row(id);
		assertThat(row.get("status").stringValue()).isEqualTo("SENT");
		assertThat(row.get("provider").stringValue()).isEqualTo("stub");
		assertThat(row.get("provider_message_id").stringValue()).isEqualTo("stub-" + id);
		assertThat(row.get("attempts").intValue()).isOne();
		assertThat(row.get("lock_token").isNull()).isTrue();
		assertThat(row.get("sent_at").isNull()).isFalse();
	}

	@Test
	void ac_23_5_sensitiveVariablesAreGoneOnceSentAndTheRestStays() throws Exception {
		UUID id = accept("");
		assertThat(row(id).get("variables").has("resetUrl")).isTrue();

		worker.runOnce();

		JsonNode row = row(id);
		assertThat(row.get("variables").has("resetUrl")).isFalse();
		assertThat(row.get("variables").get("firstName").stringValue()).isEqualTo("Ana");
		assertThat(row.get("finalized_at").isNull()).as("SENT is not terminal").isTrue();
		assertThat(row.get("variables_purged_at").isNull()).isTrue();
	}

	@Test
	void ac_12_3_aWorkerThatLostItsLockClosesNothingAndDoesNotResend() throws Exception {
		UUID id = accept("");
		UUID otherOwner = UUID.randomUUID();
		double lostBefore = meters.counter("email.lost.lock").count();
		// While the provider call is in flight, the sweep reclaimed the message and another worker took it.
		sender.behave(email -> {
			try {
				sql("UPDATE email_message SET lock_token = ?, locked_by = 'other-worker' WHERE id = ?", otherOwner, id);
			}
			catch (Exception ex) {
				throw new IllegalStateException(ex);
			}
			return RecordingEmailSender.accept(email);
		});

		worker.runOnce();

		JsonNode row = row(id);
		assertThat(row.get("status").stringValue()).isEqualTo("SENDING");
		assertThat(row.get("lock_token").stringValue()).isEqualTo(otherOwner.toString());
		assertThat(row.get("provider_message_id").isNull()).isTrue();
		assertThat(row.get("attempt_log").toString()).contains("LOST_LOCK");
		assertThat(meters.counter("email.lost.lock").count()).isEqualTo(lostBefore + 1);
		assertThat(sender.sent()).hasSize(1);
	}

	@Test
	void ac_11_3_expiredLocksGoBackToTheQueueKeepingTheirAttempts() throws Exception {
		UUID id = accept("");
		sender.behave(email -> {
			throw new IllegalStateException("worker crashed mid-send");
		});
		worker.runOnce();
		assertThat(row(id).get("status").stringValue()).isEqualTo("SENDING");
		sql("UPDATE email_message SET lock_expires_at = now() - interval '1 second' WHERE id = ?", id);

		sweeper.reclaim();

		JsonNode row = row(id);
		assertThat(row.get("status").stringValue()).isEqualTo("QUEUED");
		assertThat(row.get("attempts").intValue()).as("attempt counted at claim time is kept").isOne();
		assertThat(row.get("lock_token").isNull()).isTrue();
		assertThat(row.get("attempt_log").toString()).contains("LOCK_EXPIRED");

		sender.reset();
		worker.runOnce();
		assertThat(row(id).get("status").stringValue()).isEqualTo("SENT");
		assertThat(row(id).get("attempts").intValue()).isEqualTo(2);
	}

	@Test
	void ac_11_3_aMessageThatKeepsKillingTheWorkerEndsFailed() throws Exception {
		UUID id = accept("");
		sql("""
				UPDATE email_message SET status = 'SENDING', attempts = 6, lock_token = gen_random_uuid(),
				    lock_expires_at = now() - interval '1 second', first_attempt_at = now()
				WHERE id = ?
				""", id);

		sweeper.reclaim();

		JsonNode row = row(id);
		assertThat(row.get("status").stringValue()).isEqualTo("FAILED");
		assertThat(row.get("failure_code").stringValue()).isEqualTo("MAX_ATTEMPTS_EXCEEDED");
		assertThat(row.get("finalized_at").isNull()).isFalse();
		assertThat(row.get("variables").has("resetUrl")).as("terminal: sensitive variables removed").isFalse();
		assertThat(row.get("variables").has("firstName")).isTrue();
	}

	@Test
	void ac_13_1_transientErrorsRetryWithBackoff() throws Exception {
		UUID id = accept("");
		sender.behave(email -> {
			throw SendException.transientFailure("PROVIDER_UNAVAILABLE", "503 from the provider", null);
		});

		Instant before = Instant.now();
		worker.runOnce();

		JsonNode row = row(id);
		assertThat(row.get("status").stringValue()).isEqualTo("QUEUED");
		Instant next = OffsetDateTime.parse(row.get("next_attempt_at").stringValue()).toInstant();
		assertThat(Duration.between(before, next)).isBetween(Duration.ofSeconds(47), Duration.ofSeconds(73));
		assertThat(row.get("attempt_log").get(0).get("code").stringValue()).isEqualTo("PROVIDER_UNAVAILABLE");
		assertThat(row.get("variables").has("resetUrl")).as("still needed for the retry").isTrue();
		assertThat(worker.runOnce()).as("not eligible before next_attempt_at").isEmpty();
	}

	@Test
	void ac_13_5_retryAfterIsRespected() throws Exception {
		UUID id = accept("");
		sender.behave(email -> {
			throw new SendException(SendException.Kind.TRANSIENT, "PROVIDER_RATE_LIMITED", "429", Duration.ofSeconds(7),
					null);
		});
		Instant before = Instant.now();
		worker.runOnce();
		Instant next = OffsetDateTime.parse(row(id).get("next_attempt_at").stringValue()).toInstant();
		assertThat(Duration.between(before, next)).isBetween(Duration.ofSeconds(6), Duration.ofSeconds(9));
	}

	@Test
	void ac_13_2_permanentErrorsFailAtOnce() throws Exception {
		UUID id = accept("");
		sender.behave(email -> {
			throw SendException.permanentFailure("PROVIDER_REJECTED", "422 from the provider", null);
		});
		worker.runOnce();

		JsonNode row = row(id);
		assertThat(row.get("status").stringValue()).isEqualTo("FAILED");
		assertThat(row.get("failure_code").stringValue()).isEqualTo("PROVIDER_REJECTED");
		assertThat(row.get("failure_detail").stringValue()).isEqualTo("422 from the provider");
		assertThat(row.get("finalized_at").isNull()).isFalse();
		assertThat(row.get("variables").has("resetUrl")).isFalse();
	}

	@Test
	void ac_13_3_exhaustedAttemptsFail() throws Exception {
		UUID id = accept("");
		sql("UPDATE email_message SET attempts = 5, first_attempt_at = now() WHERE id = ?", id);
		sender.behave(email -> {
			throw SendException.transientFailure("PROVIDER_TIMEOUT", "timeout", null);
		});
		worker.runOnce();

		JsonNode row = row(id);
		assertThat(row.get("attempts").intValue()).isEqualTo(6);
		assertThat(row.get("status").stringValue()).isEqualTo("FAILED");
		assertThat(row.get("failure_code").stringValue()).isEqualTo("MAX_ATTEMPTS_EXCEEDED");
	}

	@Test
	void ac_13_7_attemptsStopAfterTheRetryWindow() throws Exception {
		UUID id = accept("");
		sql("UPDATE email_message SET attempts = 2, first_attempt_at = now() - interval '24 hours' WHERE id = ?", id);
		worker.runOnce();

		assertThat(row(id).get("failure_code").stringValue()).isEqualTo("RETRY_WINDOW_EXCEEDED");
		assertThat(sender.sent()).isEmpty();
	}

	@Test
	void ac_13_6_aMessageWithAProviderIdIsNeverSentAgain() throws Exception {
		UUID id = accept("");
		sql("UPDATE email_message SET provider_message_id = 'already-accepted' WHERE id = ?", id);
		worker.runOnce();

		assertThat(sender.sent()).isEmpty();
		assertThat(row(id).get("status").stringValue()).isEqualTo("SENT");
		assertThat(row(id).get("provider_message_id").stringValue()).isEqualTo("already-accepted");
	}

	@Test
	void ac_12_5_renderErrorsFailWithoutCallingTheProvider() throws Exception {
		String key = a.publishTemplate("TRANSACTIONAL", "<p>{{formatMoney amount \"CRC\"}}</p>",
				"{\"required\": [\"amount\"], \"properties\": {\"amount\": {\"type\": \"number\"}}}");
		HttpResponse<String> accepted = a.send(
				"{\"templateKey\": \"" + key + "\", \"to\": {\"email\": \"ana@example.test\"}, \"variables\": {\"amount\": 1500}}",
				null);
		UUID id = UUID.fromString(SendingTestSupport.json(accepted).get("id").stringValue());
		// Variables that rendered at acceptance but no longer do, e.g. after a manual repair gone wrong.
		sql("UPDATE email_message SET variables = '{\"amount\": \"not a number\"}' WHERE id = ?", id);

		worker.runOnce();

		JsonNode row = row(id);
		assertThat(row.get("status").stringValue()).isEqualTo("FAILED");
		assertThat(row.get("failure_code").stringValue()).isEqualTo("RENDER_ERROR");
		assertThat(row.get("failure_detail").stringValue()).contains("formatMoney").doesNotContain("not a number");
		assertThat(sender.sent()).isEmpty();
	}

	@Test
	void ac_10_6_suppressionsCreatedAfterAcceptanceAreRespected() throws Exception {
		UUID id = accept("\"cc\": [\"keep@example.test\", \"late@example.test\"]");
		SendingTestSupport.suppress("late@example.test", "GLOBAL", null, "COMPLAINT");
		worker.runOnce();
		assertThat(sender.sent().getFirst().cc()).containsExactly("keep@example.test");

		UUID second = accept("");
		SendingTestSupport.suppress("ana@example.test", "TENANT", a.tenant.id(), "MANUAL");
		worker.runOnce();
		assertThat(row(second).get("status").stringValue()).isEqualTo("FAILED");
		assertThat(row(second).get("failure_code").stringValue()).isEqualTo("SUPPRESSED");
		assertThat(sender.sent()).hasSize(1);
		assertThat(row(id).get("status").stringValue()).isEqualTo("SENT");
	}

	@Test
	void ac_02_3_messagesOfASuspendedOrPausedTenantStayQueued() throws Exception {
		UUID id = accept("");
		a.suspend();
		assertThat(worker.runOnce()).isEmpty();
		assertThat(row(id).get("status").stringValue()).isEqualTo("QUEUED");
		assertThat(row(id).get("attempts").intValue()).isZero();

		a.reactivate();
		sql("UPDATE tenant SET sending_paused_at = now(), pause_reason = 'MANUAL' WHERE id = ?", a.tenant.id());
		assertThat(worker.runOnce()).isEmpty();
		sql("UPDATE tenant SET sending_paused_at = NULL, pause_reason = NULL WHERE id = ?", a.tenant.id());

		worker.runOnce();
		assertThat(row(id).get("status").stringValue()).isEqualTo("SENT");
	}

	@Test
	void ac_36_3_securityIsTakenBeforeTwoThousandEligibleNotices() throws Exception {
		SendingTestSupport notices = new SendingTestSupport(http(), "NOTICE");
		UUID notice = UUID.fromString(SendingTestSupport.json(notices.send(notices.body(""), null)).get("id").stringValue());
		sql("""
				INSERT INTO email_message (id, tenant_id, template_id, template_version_id, category, priority, locale,
				    to_email, from_email, from_name, variables, status, next_attempt_at)
				SELECT gen_random_uuid(), tenant_id, template_id, template_version_id, category, priority, locale,
				    'bulk' || g || '@example.test', from_email, from_name, variables, 'QUEUED',
				    now() - interval '1 hour'
				FROM email_message, generate_series(1, 1999) g WHERE id = ?
				""", notice);
		UUID security = accept("");
		double sentBefore = timer("SECURITY") == null ? 0 : timer("SECURITY").count();

		List<QueueRepository.Claimed> claimed = worker.runOnce();

		assertThat(claimed).hasSize(4);
		assertThat(claimed.getFirst().id()).isEqualTo(security);
		assertThat(row(security).get("status").stringValue()).isEqualTo("SENT");
		// NFR-19: time from acceptance to SENT is measured per category.
		assertThat(timer("SECURITY").count()).isEqualTo((long) sentBefore + 1);
		assertThat(timer("NOTICE").count()).isGreaterThanOrEqualTo(3);
		assertThat(http().get("/actuator/prometheus").send().body())
			.contains("email_time_to_sent_seconds_count{category=\"SECURITY\"}");
	}

	private Timer timer(String category) {
		return meters.find("email.time.to.sent").tag("category", category).timer();
	}

	@Test
	void stubProviderIsTheActiveSender() {
		assertThat(activeSender).isSameAs(sender);
	}

}
