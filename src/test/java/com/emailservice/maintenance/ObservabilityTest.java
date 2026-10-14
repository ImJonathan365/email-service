package com.emailservice.maintenance;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.simple.JdbcClient;

import com.emailservice.common.alert.Alerts;
import com.emailservice.common.config.AppProperties;
import com.emailservice.common.persistence.SystemDataSource;
import com.emailservice.sending.SendingTestSupport;
import com.emailservice.support.IntegrationTest;
import com.emailservice.support.MessageSeeder;
import com.emailservice.support.MutableClock;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import tools.jackson.databind.JsonNode;

/**
 * AC-24.2 and AC-24.4, NFR-11: the queue indicator, the queue gauges and the queue alerts. Each
 * test builds its own metrics task with its own alerts and meter registry, so no alert
 * deduplication or gauge state is shared with other tests.
 */
@ExtendWith(OutputCaptureExtension.class)
class ObservabilityTest extends IntegrationTest {

	@Autowired
	@SystemDataSource
	JdbcClient systemJdbc;

	@Autowired
	QueueState queue;

	@Autowired
	AppProperties properties;

	OperationalMetrics metrics;

	MeterRegistry meters;

	SendingTestSupport a;

	@BeforeEach
	void setUp() throws Exception {
		a = new SendingTestSupport(http(), "SECURITY");
		cancelPending();
		meters = new SimpleMeterRegistry();
		metrics = new OperationalMetrics(systemJdbc, queue, new Alerts(new MutableClock()), meters, properties);
	}

	@AfterEach
	void tearDown() throws Exception {
		cancelPending();
	}

	static void cancelPending() throws Exception {
		RetentionPurgeTest.sql("""
				UPDATE email_message SET status = 'CANCELED', finalized_at = now(), lock_token = NULL,
				    lock_expires_at = NULL, locked_by = NULL
				WHERE status IN ('QUEUED', 'SENDING')
				""");
	}

	UUID waitingSince(String interval) throws Exception {
		HttpResponse<String> response = a.send(a.body(""), null);
		UUID id = UUID.fromString(SendingTestSupport.json(response).get("id").stringValue());
		RetentionPurgeTest.sql("UPDATE email_message SET next_attempt_at = now() - CAST(? AS interval) WHERE id = ?",
				interval, id);
		return id;
	}

	JsonNode queueHealth(int expectedStatus) throws Exception {
		HttpResponse<String> response = http().get("/actuator/health/queue").send();
		assertThat(response.statusCode()).as(response.body()).isEqualTo(expectedStatus);
		return SendingTestSupport.json(response);
	}

	@Test
	void ac_24_4_anOldEligibleMessageDegradesTheQueueButStillAnswers200(CapturedOutput output) throws Exception {
		assertThat(queueHealth(200).get("status").stringValue()).isEqualTo("UP");

		waitingSince("10 minutes");

		assertThat(queueHealth(200).get("status").stringValue()).isEqualTo("DEGRADED");
		metrics.run();
		assertThat(output.getAll()).contains("\"alert\":\"QUEUE_AGE\"");
		assertThat(meters.get("email.queue.oldest.seconds").gauge().value()).isGreaterThanOrEqualTo(600);
		assertThat(meters.get("email.queue.depth").gauge().value()).isOne();
	}

	@Test
	void ac_24_4_aHeldTenantsQueueIsNeitherDegradationNorDepth() throws Exception {
		waitingSince("10 minutes");
		a.suspend();

		assertThat(queueHealth(200).get("status").stringValue()).isEqualTo("UP");
		metrics.run();
		assertThat(meters.get("email.queue.depth").gauge().value()).isZero();
		assertThat(meters.get("email.queue.held").tag("tenant", a.tenant.slug()).gauge().value()).isOne();
		a.reactivate();
	}

	@Test
	void nfr_11_aHighShareOfFailedMessagesAlertsOnce(CapturedOutput output) throws Exception {
		MessageSeeder.seed(a.tenant.id(), 100, "FAILED", "interval '1 minute'");

		metrics.run();
		metrics.run();

		assertThat(output.getAll().split("\"alert\":\"FAILED_RATE\"", -1)).as("emitted once, then deduplicated")
			.hasSize(2);
		assertThat(meters.get("email.messages").tag("status", "FAILED").gauge().value()).isGreaterThanOrEqualTo(100);
	}

}
