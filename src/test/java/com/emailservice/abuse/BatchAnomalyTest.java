package com.emailservice.abuse;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.emailservice.sending.SendingTestSupport;
import com.emailservice.support.IntegrationTest;

/** AC-34.1 with FR-35: every item a batch accepts counts for the volume anomaly. */
class BatchAnomalyTest extends IntegrationTest {

	@Autowired
	AnomalyDetector detector;

	@Test
	void ac_34_1_aBatchThatSpikesTheVolumePausesTheTenant() throws Exception {
		SendingTestSupport a = new SendingTestSupport(http(), "TRANSACTIONAL");
		List<String> items = new ArrayList<>();
		for (int i = 0; i < 55; i++) {
			String body = a.bodyTo(SendingTestSupport.uniqueAddress(), "");
			items.add("{\"idempotencyKey\": \"b-" + UUID.randomUUID() + "\", " + body.substring(1));
		}
		HttpResponse<String> response = http().post("/v1/emails/batch")
			.header("Authorization", a.product.bearer())
			.json("{\"items\": [" + String.join(", ", items) + "]}")
			.send();
		assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
		assertThat(SendingTestSupport.countMessages(a.tenant.id())).isEqualTo(55);

		assertThat(detector.evaluate()).as("55 in an hour, no history: above the minimum of 50")
			.contains(a.tenant.slug());
		assertThat(SendingTestSupport.tenantRow(a.tenant.id()).get("pause_reason").stringValue())
			.isEqualTo("VOLUME_ANOMALY");
	}

}
