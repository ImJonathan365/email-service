package com.emailservice.sending.worker;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static com.github.tomakehurst.wiremock.stubbing.Scenario.STARTED;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;

import com.emailservice.provider.EmailSender;
import com.emailservice.provider.resend.ResendEmailSender;
import com.emailservice.sending.SendingTestSupport;
import com.emailservice.support.AdminApi;
import com.emailservice.support.AuditRows;
import com.emailservice.support.IntegrationTest;
import com.emailservice.support.MutableClock;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.http.Fault;
import com.github.tomakehurst.wiremock.verification.LoggedRequest;

import io.micrometer.core.instrument.MeterRegistry;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The worker with the real Resend adapter against WireMock (NFR-04, AC-13.9, docs/05 §4): lost
 * responses, the circuit breaker and configuration errors. No network beyond localhost, no email.
 */
@Import(ResendWorkerTest.WireMockResend.class)
class ResendWorkerTest extends IntegrationTest {

	static final WireMockServer RESEND = new WireMockServer(wireMockConfig().dynamicPort());

	static {
		RESEND.start();
	}

	@TestConfiguration
	static class WireMockResend {

		@Bean
		@Primary
		EmailSender wireMockResendSender(JsonMapper jsonMapper) {
			return new ResendEmailSender(RESEND.baseUrl(), "re_test_key", Duration.ofSeconds(2), Duration.ofSeconds(2),
					jsonMapper);
		}

		@Bean
		@Primary
		MutableClock mutableClock() {
			return new MutableClock();
		}

	}

	@Autowired
	QueueWorker worker;

	@Autowired
	CircuitBreaker breaker;

	@Autowired
	MutableClock clock;

	@Autowired
	MeterRegistry meters;

	SendingTestSupport a;

	@AfterAll
	static void stopResend() {
		RESEND.stop();
	}

	@BeforeEach
	void setUp() throws Exception {
		RESEND.resetAll();
		breaker.onSuccess();
		WorkerTest.cancelPendingMessages();
		a = new SendingTestSupport(http(), "SECURITY");
	}

	UUID accept(SendingTestSupport tenant) throws Exception {
		HttpResponse<String> response = tenant.send(tenant.body(""), null);
		assertThat(response.statusCode()).as(response.body()).isEqualTo(202);
		return UUID.fromString(SendingTestSupport.json(response).get("id").stringValue());
	}

	List<UUID> accept(SendingTestSupport tenant, int count) throws Exception {
		List<UUID> ids = new ArrayList<>();
		for (int i = 0; i < count; i++) {
			ids.add(accept(tenant));
		}
		return ids;
	}

	void respond(int status, String errorName) {
		RESEND.stubFor(post("/emails").willReturn(aResponse().withStatus(status)
			.withHeader("Content-Type", "application/json")
			.withBody("{\"statusCode\": %d, \"name\": \"%s\", \"message\": \"...\"}".formatted(status, errorName))));
	}

	void accepted() {
		RESEND.stubFor(post("/emails").willReturn(aResponse().withStatus(200)
			.withHeader("Content-Type", "application/json")
			.withBody("{\"id\": \"resend-{{request.headers.[Idempotency-Key]}}\"}")
			.withTransformers("response-template")));
	}

	/** Drives the worker until it claims nothing more. */
	int drain() {
		int claimed = 0;
		for (int i = 0; i < 50; i++) {
			int batch = worker.runOnce().size();
			if (batch == 0) {
				break;
			}
			claimed += batch;
		}
		return claimed;
	}

	static JsonNode row(UUID id) throws Exception {
		return SendingTestSupport.row(id);
	}

	static String status(UUID id) throws Exception {
		return row(id).get("status").stringValue();
	}

	static int requestsFor(UUID id) {
		return RESEND.findAll(postRequestedFor(urlEqualTo("/emails"))
			.withHeader("Idempotency-Key", equalTo(id.toString()))).size();
	}

	@Test
	void nfr_04_aLostResponseIsRetriedWithTheSameKeyAndBodyAndSentOnce() throws Exception {
		UUID id = accept(a);
		RESEND.stubFor(post("/emails").inScenario("lost")
			.whenScenarioStateIs(STARTED)
			.willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER))
			.willSetStateTo("accepted"));
		RESEND.stubFor(post("/emails").inScenario("lost")
			.whenScenarioStateIs("accepted")
			.willReturn(aResponse().withStatus(200).withBody("{\"id\": \"resend-1\"}")));

		worker.runOnce();
		assertThat(status(id)).isEqualTo("QUEUED");
		assertThat(row(id).get("attempt_log").get(0).get("code").stringValue()).isEqualTo("PROVIDER_UNAVAILABLE");
		WorkerTest.sql("UPDATE email_message SET next_attempt_at = now() WHERE id = ?", id);
		worker.runOnce();

		List<LoggedRequest> requests = RESEND.findAll(
				postRequestedFor(urlEqualTo("/emails")));
		assertThat(requests).hasSize(2);
		// Resend deduplicates on this key for 24 h: the second call returns the first email's id.
		assertThat(requests).allSatisfy(r -> assertThat(r.getHeader("Idempotency-Key")).isEqualTo(id.toString()));
		assertThat(requests.get(1).getBodyAsString()).isEqualTo(requests.get(0).getBodyAsString());
		JsonNode sent = row(id);
		assertThat(sent.get("status").stringValue()).isEqualTo("SENT");
		assertThat(sent.get("provider").stringValue()).isEqualTo("resend");
		assertThat(sent.get("provider_message_id").stringValue()).isEqualTo("resend-1");
		assertThat(sent.get("attempts").intValue()).isEqualTo(2);
	}

	@Test
	void ac_13_9_theBreakerOpensAfterFiveFailuresAndProbesOneSendAfterSixtySeconds() throws Exception {
		List<UUID> ids = accept(a, 8);
		respond(503, "service_unavailable");

		drain();

		assertThat(RESEND.getAllServeEvents()).as("provider calls before the breaker opened").hasSize(5);
		assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.OPEN);
		long untouched = ids.stream().filter(id -> requestsFor(id) == 0).count();
		assertThat(untouched).isEqualTo(3);
		for (UUID id : ids) {
			assertThat(status(id)).isEqualTo("QUEUED");
			assertThat(row(id).get("attempts").intValue()).isEqualTo(requestsFor(id));
		}

		clock.advance(Duration.ofSeconds(59));
		assertThat(worker.runOnce()).isEmpty();

		clock.advance(Duration.ofSeconds(1));
		RESEND.resetRequests();
		accepted();
		assertThat(worker.runOnce()).as("a single probe").hasSize(1);
		assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.CLOSED);
		drain();
		assertThat(RESEND.getAllServeEvents()).hasSize(3);
	}

	@Test
	void docs05_4_aRevokedKeyOpensTheBreakerAtOnceAndConsumesNoAttempts() throws Exception {
		List<UUID> ids = accept(a, 20);
		respond(401, "restricted_api_key");
		double before = configErrors("restricted_api_key");

		drain();
		clock.advance(Duration.ofSeconds(30));
		drain();

		assertThat(RESEND.getAllServeEvents()).as("only the first call reached the provider").hasSize(1);
		assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.OPEN);
		for (UUID id : ids) {
			JsonNode row = row(id);
			assertThat(row.get("status").stringValue()).isEqualTo("QUEUED");
			assertThat(row.get("attempts").intValue()).isZero();
		}
		assertThat(configErrors("restricted_api_key") - before).isEqualTo(1.0);

		// The probe after the open period finds the key still revoked: open again, still no attempt spent.
		clock.advance(Duration.ofSeconds(30));
		drain();
		assertThat(RESEND.getAllServeEvents()).hasSize(2);
		assertThat(ids).allSatisfy(id -> assertThat(row(id).get("attempts").intValue()).isZero());
	}

	@Test
	void docs05_4_anUnverifiedDomainPausesOnlyThatTenant() throws Exception {
		SendingTestSupport b = new SendingTestSupport(http(), "SECURITY");
		List<UUID> ofA = accept(a, 3);
		List<UUID> ofB = accept(b, 3);
		accepted();
		for (UUID id : ofA) {
			RESEND.stubFor(post("/emails").withHeader("Idempotency-Key", equalTo(id.toString()))
				.willReturn(aResponse().withStatus(403)
					.withBody("{\"statusCode\": 403, \"name\": \"validation_error\", \"message\": \"...\"}")));
		}

		drain();

		JsonNode tenant = tenantRow(a);
		assertThat(tenant.get("pause_reason").stringValue()).isEqualTo("PROVIDER_DOMAIN");
		assertThat(tenant.get("sending_paused_at").isNull()).isFalse();
		for (UUID id : ofA) {
			assertThat(status(id)).isEqualTo("QUEUED");
			assertThat(row(id).get("attempts").intValue()).isZero();
		}
		assertThat(ofB).allSatisfy(id -> assertThat(status(id)).isEqualTo("SENT"));
		assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.CLOSED);
		assertThat(AuditRows.forTenant(a.tenant.id())).filteredOn(r -> r.action().equals("TENANT_PAUSED"))
			.singleElement()
			.satisfies(r -> {
				assertThat(r.actorType()).isEqualTo("SYSTEM");
				assertThat(r.metadata()).contains("PROVIDER_DOMAIN");
			});
		HttpResponse<String> blocked = a.send(a.body(""), null);
		assertThat(blocked.statusCode()).isEqualTo(403);
		assertThat(blocked.body()).contains("TENANT_SENDING_PAUSED");

		// The owner fixes the domain in Resend and resumes the tenant: the held queue goes out.
		RESEND.resetAll();
		accepted();
		HttpResponse<String> resumed = a.admin.resumeSending(a.tenant, "{\"note\": \"domain verified\"}");
		assertThat(resumed.statusCode()).as(resumed.body()).isEqualTo(200);
		assertThat(resumed.body()).contains("\"sendingPausedAt\":null");
		assertThat(AuditRows.forTenant(a.tenant.id())).filteredOn(r -> r.action().equals("TENANT_RESUMED"))
			.singleElement()
			.satisfies(r -> {
				assertThat(r.actorType()).isEqualTo("ADMIN");
				assertThat(r.metadata()).contains("PROVIDER_DOMAIN", "domain verified");
			});
		drain();
		assertThat(ofA).allSatisfy(id -> assertThat(status(id)).isEqualTo("SENT"));
	}

	@Test
	void resumingATenantThatIsNotPausedChangesNothing() throws Exception {
		HttpResponse<String> response = a.admin.resumeSending(a.tenant, "{}");
		assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
		assertThat(AuditRows.forTenant(a.tenant.id())).noneMatch(r -> r.action().equals("TENANT_RESUMED"));
		assertThat(a.admin.resumeSending(new AdminApi.Tenant("missing-tenant", null), "{}")
			.statusCode()).isEqualTo(404);
	}

	@Test
	void ac_13_8_anIdempotencyConflictFailsTheMessageAndIsCounted() throws Exception {
		UUID id = accept(a);
		respond(409, "invalid_idempotent_request");
		double before = meters.counter("email.provider.idempotency.conflict").count();

		worker.runOnce();

		JsonNode row = row(id);
		assertThat(row.get("status").stringValue()).isEqualTo("FAILED");
		assertThat(row.get("failure_code").stringValue()).isEqualTo("PROVIDER_IDEMPOTENCY_CONFLICT");
		assertThat(meters.counter("email.provider.idempotency.conflict").count() - before).isEqualTo(1.0);
		assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.CLOSED);
	}

	double configErrors(String error) {
		return meters.counter("email.provider.config.error", "error", error).count();
	}

	static JsonNode tenantRow(SendingTestSupport tenant) throws Exception {
		return SendingTestSupport.tenantRow(tenant.tenant.id());
	}

}
