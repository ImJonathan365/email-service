package com.emailservice.abuse;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import com.emailservice.sending.SendingTestSupport;
import com.emailservice.support.AuditRows;
import com.emailservice.support.IntegrationTest;
import com.emailservice.support.IntegrationTestEnvironment;
import com.emailservice.support.MessageSeeder;

import io.micrometer.core.instrument.MeterRegistry;
import tools.jackson.databind.JsonNode;

/** FR-34 end to end: real rows, a real pause, its audit, metric and alert, and the held queue. */
@ExtendWith(OutputCaptureExtension.class)
class AnomalyDetectorTest extends IntegrationTest {

	@Autowired
	AnomalyDetector detector;

	@Autowired
	MeterRegistry meters;

	SendingTestSupport a;

	@BeforeEach
	void setUp() throws Exception {
		a = new SendingTestSupport(http(), "TRANSACTIONAL");
	}

	JsonNode tenant() throws Exception {
		return SendingTestSupport.tenantRow(a.tenant.id());
	}

	@Test
	void ac_34_1_aVolumeSpikePausesTheTenantAuditsCountsAndAlerts(CapturedOutput output) throws Exception {
		// Two weeks at about 5 an hour, then 120 in the last hour.
		MessageSeeder.seed(a.tenant.id(), 14 * 24 * 5, "DELIVERED", "interval '1 hour' + g * interval '12 minutes'");
		MessageSeeder.seed(a.tenant.id(), 120, "QUEUED", "g * interval '20 seconds'");
		double before = meters.counter("email.tenant.paused", "reason", "VOLUME_ANOMALY").count();

		assertThat(detector.evaluate()).contains(a.tenant.slug());

		assertThat(tenant().get("pause_reason").stringValue()).isEqualTo("VOLUME_ANOMALY");
		assertThat(AuditRows.forTenant(a.tenant.id())).filteredOn(row -> row.action().equals("TENANT_PAUSED"))
			.singleElement()
			.satisfies(row -> {
				assertThat(row.actorType()).isEqualTo("SYSTEM");
				assertThat(row.metadata()).contains("VOLUME_ANOMALY", "120 accepted in the last hour");
			});
		assertThat(meters.counter("email.tenant.paused", "reason", "VOLUME_ANOMALY").count() - before).isEqualTo(1.0);
		assertThat(output.getAll()).contains("\"alert\":\"TENANT_PAUSED\"", "\"alertSubject\":\"" + a.tenant.slug() + "\"");
	}

	@Test
	void ac_34_4_aPausedTenantIsRefusedNewSendsUntilAnAdministratorResumesIt() throws Exception {
		MessageSeeder.seed(a.tenant.id(), 10, "DELIVERED", "interval '2 hours'");
		MessageSeeder.seed(a.tenant.id(), 3, "COMPLAINED", "g * interval '1 hour'");
		assertThat(detector.evaluate()).contains(a.tenant.slug());
		assertThat(tenant().get("pause_reason").stringValue()).isEqualTo("COMPLAINT_RATE");

		HttpResponse<String> refused = a.send(a.body(""), null);
		assertThat(refused.statusCode()).isEqualTo(403);
		assertThat(refused.body()).contains("TENANT_SENDING_PAUSED");

		assertThat(a.admin.resumeSending(a.tenant, "{\"note\": \"checked with the product\"}").statusCode())
			.isEqualTo(200);
		assertThat(a.send(a.body(""), null).statusCode()).isEqualTo(202);
	}

	@Test
	void ac_34_2_aHighBounceRateWithEnoughResultsPauses() throws Exception {
		MessageSeeder.seed(a.tenant.id(), 280, "DELIVERED", "interval '2 hours'");
		MessageSeeder.seed(a.tenant.id(), 20, "BOUNCED", "interval '3 hours'");

		assertThat(detector.evaluate()).contains(a.tenant.slug());
		assertThat(tenant().get("pause_reason").stringValue()).isEqualTo("BOUNCE_RATE");
	}

	@Test
	void ac_34_5_belowTheMinimumsNothingPausesWhateverTheRate() throws Exception {
		MessageSeeder.seed(a.tenant.id(), 40, "BOUNCED", "interval '3 hours'");
		MessageSeeder.seed(a.tenant.id(), 2, "COMPLAINED", "interval '3 hours'");
		MessageSeeder.seed(a.tenant.id(), 45, "QUEUED", "g * interval '30 seconds'");

		assertThat(detector.evaluate()).doesNotContain(a.tenant.slug());
		assertThat(tenant().get("pause_reason").isNull()).isTrue();
	}

	@Test
	void ac_34_5_thresholdsAreConfigurablePerTenant() throws Exception {
		a.admin.patchTenant(a.tenant, "{\"anomalyOverrides\": {\"bouncePauseRate\": 0.10, \"minHourly\": 500}}");
		MessageSeeder.seed(a.tenant.id(), 280, "DELIVERED", "interval '2 hours'");
		MessageSeeder.seed(a.tenant.id(), 20, "BOUNCED", "interval '3 hours'");
		MessageSeeder.seed(a.tenant.id(), 200, "QUEUED", "g * interval '10 seconds'");

		assertThat(detector.evaluate()).doesNotContain(a.tenant.slug());

		a.admin.patchTenant(a.tenant, "{\"anomalyOverrides\": {\"bouncePauseRate\": 0.05}}");
		assertThat(detector.evaluate()).contains(a.tenant.slug());
		assertThat(tenant().get("pause_reason").stringValue()).isEqualTo("BOUNCE_RATE");
	}

	@Test
	void ac_34_5_anOverrideCannotLowerTheHourlyMinimumAndBadValuesAreRejected() throws Exception {
		a.admin.patchTenant(a.tenant, "{\"anomalyOverrides\": {\"minHourly\": 5, \"volumeMultiplier\": 1}}");
		MessageSeeder.seed(a.tenant.id(), 40, "QUEUED", "g * interval '30 seconds'");
		assertThat(detector.evaluate()).doesNotContain(a.tenant.slug());

		for (String invalid : new String[] { "{\"bouncePauseRate\": 1.5}", "{\"volumeMultiplier\": 0.5}",
				"{\"minHourly\": 0}" }) {
			HttpResponse<String> response = http().patch("/admin/v1/tenants/" + a.tenant.slug())
				.header("X-Admin-Key", IntegrationTestEnvironment.ADMIN_API_KEY)
				.json("{\"anomalyOverrides\": " + invalid + "}")
				.send();
			assertThat(response.statusCode()).as(invalid).isEqualTo(422);
		}
	}

}
