package com.emailservice.sending.worker;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.sql.Connection;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.emailservice.sending.SendingTestSupport;
import com.emailservice.support.PostgresTestDatabase;
import com.emailservice.support.PostgresTestDatabase.Role;

import tools.jackson.databind.JsonNode;

/** FR-35: one result per item, items independent, one transaction, quota and priority per item. */
class BatchTest extends WorkerTest {

	SendingTestSupport a;

	@BeforeEach
	void setUp() throws Exception {
		a = new SendingTestSupport(http(), "SECURITY");
	}

	/** A batch item: a POST /v1/emails body plus its idempotencyKey. */
	String item(String key, String extra) {
		String body = a.body(extra);
		return "{\"idempotencyKey\": \"" + key + "\", " + body.substring(1);
	}

	String item(String extra) {
		return item("k-" + UUID.randomUUID(), extra);
	}

	HttpResponse<String> batch(List<String> items) throws Exception {
		return http().post("/v1/emails/batch")
			.header("Authorization", a.product.bearer())
			.json("{\"items\": [" + String.join(", ", items) + "]}")
			.send();
	}

	List<JsonNode> results(HttpResponse<String> response) {
		assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
		List<JsonNode> results = new ArrayList<>();
		SendingTestSupport.json(response).get("results").forEach(results::add);
		return results;
	}

	static List<Integer> statuses(List<JsonNode> results) {
		return results.stream().map(result -> result.get("httpStatus").intValue()).toList();
	}

	@Test
	void ac_35_2_eachItemIsJudgedAloneAndAnsweredInOrder() throws Exception {
		List<JsonNode> results = results(batch(List.of(item(""),
				item("").replace("ana@example.test", "not-an-address"), "{\"templateKey\": \"x\"}", item(""),
				item("").replace("\"templateKey\": \"" + a.templateKey + "\"", "\"templateKey\": \"\""))));

		assertThat(statuses(results)).containsExactly(202, 422, 422, 202, 422);
		assertThat(results.get(1).get("code").stringValue()).isEqualTo("INVALID_EMAIL_ADDRESS");
		assertThat(results.get(2).get("code").stringValue()).as("no idempotencyKey").isEqualTo("VALIDATION_ERROR");
		assertThat(results.get(4).get("code").stringValue()).as("blank templateKey").isEqualTo("VALIDATION_ERROR");
		for (int i = 0; i < results.size(); i++) {
			assertThat(results.get(i).get("index").intValue()).isEqualTo(i);
		}
		assertThat(SendingTestSupport.countMessages(a.tenant.id())).isEqualTo(2);
		assertThat(results.get(0).get("status").stringValue()).isEqualTo("QUEUED");
	}

	@Test
	void ac_35_1_upToOneHundredItemsAndOneMebibyte() throws Exception {
		a.admin.patchTenant(a.tenant, "{\"rateLimitPerMinute\": 1000}");
		List<String> hundred = new ArrayList<>();
		for (int i = 0; i < 100; i++) {
			hundred.add(item(""));
		}
		assertThat(statuses(results(batch(hundred)))).hasSize(100).containsOnly(202);

		hundred.add(item(""));
		HttpResponse<String> tooMany = batch(hundred);
		assertThat(tooMany.statusCode()).isEqualTo(422);
		assertThat(tooMany.body()).contains("VALIDATION_ERROR");
		assertThat(batch(List.of()).statusCode()).isEqualTo(422);

		HttpResponse<String> tooBig = batch(List.of(item("\"metadata\": {\"blob\": \"" + "x".repeat(1_100_000) + "\"}")));
		assertThat(tooBig.statusCode()).isEqualTo(413);
		assertThat(SendingTestSupport.countMessages(a.tenant.id())).as("only the first batch").isEqualTo(100);
	}

	@Test
	void ac_08_1_repeatsInsideABatchAndAcrossBatches() throws Exception {
		String key = "same-" + UUID.randomUUID();
		List<JsonNode> first = results(batch(List.of(item(key, ""), item(key, ""), item(key, "\"tags\": [\"other\"]"))));

		assertThat(statuses(first)).containsExactly(202, 200, 409);
		assertThat(first.get(1).get("id")).isEqualTo(first.get(0).get("id"));
		assertThat(first.get(2).get("code").stringValue()).isEqualTo("IDEMPOTENCY_KEY_REUSED");

		List<JsonNode> second = results(batch(List.of(item(key, ""))));
		assertThat(statuses(second)).containsExactly(200);
		assertThat(second.get(0).get("id")).isEqualTo(first.get(0).get("id"));
		assertThat(SendingTestSupport.countMessages(a.tenant.id())).isOne();
	}

	@Test
	void ac_35_3_whenTheLimitRunsOutTheRestGet429AndConsumeNothing() throws Exception {
		a.admin.patchTenant(a.tenant, "{\"rateLimitPerMinute\": 3}");

		List<JsonNode> results = results(batch(List.of(item(""), item(""), item(""), item(""), item(""))));

		assertThat(statuses(results)).containsExactly(202, 202, 202, 429, 429);
		assertThat(results.get(3).get("code").stringValue()).isEqualTo("RATE_LIMITED");
		assertThat(SendingTestSupport.countMessages(a.tenant.id())).isEqualTo(3);
	}

	@Test
	void ac_08_7_afterTheLimitAnIdempotentRepeatStillGetsItsOriginalResult() throws Exception {
		a.admin.patchTenant(a.tenant, "{\"rateLimitPerMinute\": 2}");
		String key = "earlier-" + UUID.randomUUID();
		JsonNode earlier = results(batch(List.of(item(key, "")))).getFirst();

		List<JsonNode> results = results(batch(List.of(item(""), item(""), item(key, ""), item(""))));

		assertThat(statuses(results)).containsExactly(202, 429, 200, 429);
		assertThat(results.get(2).get("id")).isEqualTo(earlier.get("id"));
		assertThat(SendingTestSupport.countMessages(a.tenant.id())).isEqualTo(2);
	}

	@Test
	void ac_34_4_aPausedTenantsBatchIsRefusedWhole() throws Exception {
		sql("UPDATE tenant SET sending_paused_at = now(), pause_reason = 'MANUAL' WHERE id = ?", a.tenant.id());

		HttpResponse<String> response = batch(List.of(item(""), item("")));

		assertThat(response.statusCode()).isEqualTo(403);
		assertThat(response.body()).contains("TENANT_SENDING_PAUSED");
		assertThat(SendingTestSupport.countMessages(a.tenant.id())).isZero();
	}

	@Test
	void ac_35_4_aNoticeBatchNeverOvertakesASecurityMessage() throws Exception {
		String notice = a.publishTemplate("NOTICE", "<p>Aviso {{firstName}}</p>",
				"{\"required\": [\"firstName\"], \"properties\": {\"firstName\": {\"type\": \"string\"}}}");
		List<String> items = new ArrayList<>();
		for (int i = 0; i < 6; i++) {
			items.add("{\"idempotencyKey\": \"n-" + UUID.randomUUID() + "\", \"templateKey\": \"" + notice
					+ "\", \"to\": {\"email\": \"ana@example.test\"}, \"variables\": {\"firstName\": \"Ana\"}}");
		}
		assertThat(statuses(results(batch(items)))).containsOnly(202);
		HttpResponse<String> security = a.send(a.body(""), null);
		UUID securityId = UUID.fromString(SendingTestSupport.json(security).get("id").stringValue());

		List<QueueRepository.Claimed> claimed = worker.runOnce();

		assertThat(claimed.getFirst().id()).as("SECURITY first despite arriving last").isEqualTo(securityId);
		assertThat(sender.sent().getFirst().messageId()).isEqualTo(securityId);
	}

	@Test
	void ac_35_1_eachItemMayCarryItsOwnSendAt() throws Exception {
		List<JsonNode> results = results(batch(List.of(item(""),
				item("\"sendAt\": \"" + Instant.now().plus(Duration.ofHours(1)) + "\""))));

		List<UUID> claimed = worker.runOnce().stream().map(QueueRepository.Claimed::id).toList();

		assertThat(claimed).containsExactly(UUID.fromString(results.get(0).get("id").stringValue()));
		assertThat(SendingTestSupport.row(UUID.fromString(results.get(1).get("id").stringValue())).get("status")
			.stringValue()).isEqualTo("QUEUED");
	}

	/** A database failure is not an item's problem: nothing of the batch is kept. */
	@Test
	void ac_35_3_aDatabaseFailureRollsTheWholeBatchBack() throws Exception {
		String failing = "boom-" + UUID.randomUUID().toString().substring(0, 8) + "@example.test";
		String constraint = "test_batch_failure";
		asOwner("ALTER TABLE email_message ADD CONSTRAINT " + constraint + " CHECK (to_email <> '" + failing
				+ "') NOT VALID");
		HttpResponse<String> response;
		try {
			response = batch(List.of(item(""), item("").replace("ana@example.test", failing), item("")));
		}
		finally {
			asOwner("ALTER TABLE email_message DROP CONSTRAINT " + constraint);
		}

		assertThat(response.statusCode()).isGreaterThanOrEqualTo(500);
		assertThat(SendingTestSupport.countMessages(a.tenant.id())).isZero();
	}

	static void asOwner(String statement) throws Exception {
		try (Connection owner = PostgresTestDatabase.connect(Role.OWNER)) {
			owner.createStatement().execute(statement);
		}
	}

}
