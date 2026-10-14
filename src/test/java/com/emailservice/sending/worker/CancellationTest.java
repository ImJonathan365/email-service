package com.emailservice.sending.worker;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.emailservice.sending.SendingTestSupport;
import com.emailservice.support.AdminApi;
import com.emailservice.support.PostgresTestDatabase;
import com.emailservice.support.PostgresTestDatabase.Role;

import tools.jackson.databind.JsonNode;

/** FR-26 and AC-31.3: cancelling a queued or scheduled message, and never one the worker claimed. */
class CancellationTest extends WorkerTest {

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

	UUID scheduled() throws Exception {
		return accept("\"sendAt\": \"" + Instant.now().plus(Duration.ofHours(1)) + "\"");
	}

	HttpResponse<String> cancel(AdminApi.Key key, Object id) throws Exception {
		return http().post("/v1/emails/" + id + "/cancel").header("Authorization", key.bearer()).send();
	}

	static String status(UUID id) throws Exception {
		return SendingTestSupport.row(id).get("status").stringValue();
	}

	@Test
	void ac_26_1_aQueuedMessageIsCanceledVisiblyAndLosesItsSensitiveVariables() throws Exception {
		UUID id = scheduled();

		HttpResponse<String> response = cancel(a.product, id);

		assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
		assertThat(SendingTestSupport.json(response).get("status").stringValue()).isEqualTo("CANCELED");
		JsonNode detail = SendingTestSupport
			.json(http().get("/v1/emails/" + id).header("Authorization", a.product.bearer()).send());
		assertThat(detail.get("status").stringValue()).isEqualTo("CANCELED");
		assertThat(detail.get("failureCode").stringValue()).isEqualTo("CANCELED_BY_CLIENT");
		JsonNode row = SendingTestSupport.row(id);
		assertThat(row.get("finalized_at").isNull()).isFalse();
		assertThat(row.get("variables").has("resetUrl")).as("x-sensitive gone (AC-23.5)").isFalse();
		assertThat(row.get("variables").has("firstName")).isTrue();

		becomeDue(id);
		assertThat(worker.runOnce()).isEmpty();
	}

	@Test
	void ac_26_1_onlyQueuedMessagesAndOnlyOnce() throws Exception {
		UUID id = accept("");
		assertThat(cancel(a.product, id).statusCode()).isEqualTo(200);

		HttpResponse<String> again = cancel(a.product, id);
		assertThat(again.statusCode()).isEqualTo(409);
		assertThat(again.body()).contains("MESSAGE_NOT_CANCELABLE");

		UUID sent = accept("");
		worker.runOnce();
		assertThat(cancel(a.product, sent).statusCode()).isEqualTo(409);
		assertThat(status(sent)).isEqualTo("SENT");
	}

	@Test
	void ac_26_1_anUnknownOrForeignIdIsNotFound() throws Exception {
		UUID mine = accept("");
		SendingTestSupport b = new SendingTestSupport(http(), "SECURITY");

		HttpResponse<String> foreign = cancel(b.product, mine);
		assertThat(foreign.statusCode()).isEqualTo(404);
		assertThat(foreign.body()).contains("MESSAGE_NOT_FOUND");
		assertThat(cancel(a.product, UUID.randomUUID()).statusCode()).isEqualTo(404);
		assertThat(cancel(a.product, "not-a-uuid").statusCode()).isEqualTo(404);
		assertThat(status(mine)).isEqualTo("QUEUED");

		AdminApi.Key readOnly = a.admin.issueKey(a.tenant, "{\"name\": \"read\", \"scopes\": [\"emails:read\"]}");
		assertThat(cancel(readOnly, mine).statusCode()).as("needs emails:send").isEqualTo(403);
	}

	@Test
	void ac_31_3_aScheduledMessageCanBeCanceledWhileTheTenantIsPaused() throws Exception {
		UUID id = scheduled();
		sql("UPDATE tenant SET sending_paused_at = now(), pause_reason = 'MANUAL' WHERE id = ?", a.tenant.id());

		assertThat(cancel(a.product, id).statusCode()).isEqualTo(200);
		assertThat(status(id)).isEqualTo("CANCELED");
	}

	/**
	 * The worker's claim holds the row lock (simulated here exactly as the claim takes it); the
	 * cancellation must wait for it, then see SENDING and refuse. The claim wins, coherently.
	 */
	@Test
	void ac_26_1_aCancellationWaitingOnAClaimLosesAndTheClaimStands() throws Exception {
		UUID id = accept("");
		// The executor outlives the claim's connection: closing the connection releases the lock the
		// cancellation may be waiting on, so the executor can then finish.
		try (var executor = Executors.newVirtualThreadPerTaskExecutor();
				Connection claim = PostgresTestDatabase.connect(Role.SYSTEM)) {
			claim.setAutoCommit(false);
			try (PreparedStatement lock = claim
				.prepareStatement("SELECT id FROM email_message WHERE id = ? FOR UPDATE SKIP LOCKED")) {
				lock.setObject(1, id);
				lock.executeQuery();
			}
			Future<HttpResponse<String>> canceling = executor.submit(() -> cancel(a.product, id));
			awaitWaitingOnALock();
			try (PreparedStatement take = claim.prepareStatement("""
					UPDATE email_message SET status = 'SENDING', attempts = attempts + 1, lock_token = gen_random_uuid(),
					    lock_expires_at = now() + interval '1 minute', first_attempt_at = now()
					WHERE id = ?
					""")) {
				take.setObject(1, id);
				take.executeUpdate();
			}
			claim.commit();

			assertThat(canceling.get(10, TimeUnit.SECONDS).statusCode()).isEqualTo(409);
		}
		assertThat(status(id)).isEqualTo("SENDING");
	}

	@Test
	void ac_26_1_aCanceledMessageIsNeverClaimed() throws Exception {
		UUID id = accept("");
		assertThat(cancel(a.product, id).statusCode()).isEqualTo(200);

		assertThat(worker.runOnce()).isEmpty();
		assertThat(sender.sent()).isEmpty();
		assertThat(status(id)).isEqualTo("CANCELED");
	}

	/** Claims and cancellations at the same time: each message ends either SENT or CANCELED, never both. */
	@Test
	void ac_26_1_concurrentClaimsAndCancellationsAlwaysEndCoherently() throws Exception {
		List<UUID> ids = new ArrayList<>();
		for (int i = 0; i < 20; i++) {
			ids.add(accept(""));
		}
		Map<UUID, Integer> answers = new ConcurrentHashMap<>();
		try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
			Future<?> claiming = executor.submit(() -> {
				for (int i = 0; i < 10; i++) {
					worker.runOnce();
				}
				return null;
			});
			for (UUID id : ids) {
				executor.submit(() -> answers.put(id, cancel(a.product, id).statusCode()));
			}
			claiming.get(60, TimeUnit.SECONDS);
		}
		worker.runOnce();

		List<UUID> sentIds = sender.sent().stream().map(email -> email.messageId()).toList();
		for (UUID id : ids) {
			String status = status(id);
			if (answers.get(id) == 200) {
				assertThat(status).as(id + " canceled").isEqualTo("CANCELED");
				assertThat(sentIds).as(id + " never sent").doesNotContain(id);
			}
			else {
				assertThat(answers.get(id)).isEqualTo(409);
				assertThat(status).as(id + " claimed").isEqualTo("SENT");
				assertThat(sentIds).contains(id);
			}
		}
	}

	static void becomeDue(UUID id) throws Exception {
		sql("UPDATE email_message SET next_attempt_at = now() - interval '1 second' WHERE id = ?", id);
	}

	/**
	 * Polls the server, not the clock: returns once a lock request is waiting. pg_locks is readable
	 * by every role, unlike other sessions' query text; nothing else runs in the database meanwhile.
	 */
	static void awaitWaitingOnALock() throws Exception {
		long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
		while (System.nanoTime() < deadline) {
			try (Connection system = PostgresTestDatabase.connect(Role.SYSTEM);
					ResultSet rs = system.createStatement().executeQuery("SELECT count(*) FROM pg_locks WHERE NOT granted")) {
				rs.next();
				if (rs.getLong(1) > 0) {
					return;
				}
			}
			Thread.onSpinWait();
		}
		throw new AssertionError("The cancellation never waited on the claim's lock");
	}

}
