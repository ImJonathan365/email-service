package com.emailservice.sending;

import static com.emailservice.sending.SendingTestSupport.json;
import static com.emailservice.sending.SendingTestSupport.row;
import static com.emailservice.sending.SendingTestSupport.suppress;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.emailservice.support.IntegrationTest;

import tools.jackson.databind.JsonNode;

/**
 * FR-10 at acceptance (ADR-0012). Suppressions are created by webhooks and the API in H6, so
 * these tests insert them directly with the system role.
 */
class SuppressionAcceptanceTest extends IntegrationTest {

	SendingTestSupport a;

	String to;

	@BeforeEach
	void setUp() throws Exception {
		a = new SendingTestSupport(http(), "SECURITY");
		to = "user-" + UUID.randomUUID().toString().substring(0, 8) + "@example.test";
	}

	String bodyTo(String address, String extra) {
		return a.body(extra).replace("ana@example.test", address);
	}

	@Test
	void ac_10_1_suppressedToIsAcceptedAsFailedWithTheReason() throws Exception {
		suppress(to, "GLOBAL", null, "HARD_BOUNCE");
		String key = "k-" + UUID.randomUUID();

		HttpResponse<String> response = a.send(bodyTo(to, ""), key);

		assertThat(response.statusCode()).isEqualTo(202);
		JsonNode body = json(response);
		assertThat(body.get("status").stringValue()).isEqualTo("FAILED");
		assertThat(body.get("failureCode").stringValue()).isEqualTo("SUPPRESSED");
		assertThat(body.get("suppressionReason").stringValue()).isEqualTo("HARD_BOUNCE");
		JsonNode row = row(UUID.fromString(body.get("id").stringValue()));
		assertThat(row.get("status").stringValue()).isEqualTo("FAILED");
		assertThat(row.get("finalized_at").isNull()).isFalse();
		assertThat(row.get("variables").size()).as("variables of a never-sent message are not kept").isZero();

		HttpResponse<String> repeat = a.send(bodyTo(to, ""), key);
		assertThat(repeat.statusCode()).isEqualTo(200);
		assertThat(json(repeat).get("suppressionReason").stringValue()).isEqualTo("HARD_BOUNCE");
	}

	@Test
	void ac_10_1_suppressedCcAndBccAreDroppedAndReported() throws Exception {
		String cc = "cc-" + UUID.randomUUID().toString().substring(0, 8) + "@example.test";
		suppress(cc, "TENANT", a.tenant.id(), "MANUAL");
		String key = "k-" + UUID.randomUUID();
		String extra = "\"cc\": [\"" + cc.toUpperCase() + "\", \"ok@example.test\"]";

		HttpResponse<String> response = a.send(a.body(extra), key);

		assertThat(response.statusCode()).isEqualTo(202);
		JsonNode body = json(response);
		assertThat(body.get("status").stringValue()).isEqualTo("QUEUED");
		assertThat(body.get("droppedRecipients").get(0).stringValue()).isEqualTo(cc.toUpperCase());
		assertThat(row(UUID.fromString(body.get("id").stringValue())).get("cc").toString()).isEqualTo("[\"ok@example.test\"]");
		assertThat(json(a.send(a.body(extra), key)).get("droppedRecipients").get(0).stringValue())
			.isEqualTo(cc.toUpperCase());
	}

	@Test
	void ac_10_2_globalSuppressionsApplyToEveryTenantAndManualOnesOnlyToTheirOwn() throws Exception {
		SendingTestSupport b = new SendingTestSupport(http(), "SECURITY");
		String manual = "manual-" + UUID.randomUUID().toString().substring(0, 8) + "@example.test";
		suppress(to, "GLOBAL", null, "COMPLAINT");
		suppress(manual, "TENANT", b.tenant.id(), "MANUAL");

		assertThat(json(a.send(bodyTo(to, ""), null)).get("suppressionReason").stringValue()).isEqualTo("COMPLAINT");
		assertThat(json(a.send(bodyTo(manual, ""), null)).get("status").stringValue()).isEqualTo("QUEUED");
		assertThat(json(b.send(b.body("").replace("ana@example.test", manual), null)).get("status").stringValue())
			.isEqualTo("FAILED");
	}

	@Test
	void ac_10_5_matchingIgnoresCase() throws Exception {
		suppress(to, "GLOBAL", null, "PROVIDER");
		assertThat(json(a.send(bodyTo(to.toUpperCase(), ""), null)).get("status").stringValue()).isEqualTo("FAILED");
	}

}
