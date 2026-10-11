package com.emailservice.provider.resend;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.equalToJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.wireMockConfig;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.emailservice.provider.OutboundEmail;
import com.emailservice.provider.SendException;
import com.emailservice.provider.SendResult;
import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.http.Fault;

import tools.jackson.databind.json.JsonMapper;

/** ADR-0016 and docs/05 §4 against a local WireMock standing in for Resend; no network, no email. */
class ResendEmailSenderTest {

	static final String KEY = "re_test_0123456789"; // gitleaks:allow (fictional, only sent to WireMock)

	static WireMockServer resend;

	ResendEmailSender sender;

	@BeforeAll
	static void start() {
		resend = new WireMockServer(wireMockConfig().dynamicPort());
		resend.start();
	}

	@AfterAll
	static void stop() {
		resend.stop();
	}

	@BeforeEach
	void setUp() {
		resend.resetAll();
		sender = new ResendEmailSender(resend.baseUrl() + "/", KEY, Duration.ofSeconds(2), Duration.ofMillis(500),
				JsonMapper.builder().build());
	}

	static OutboundEmail email(UUID id) {
		return new OutboundEmail(id, new OutboundEmail.Address("no-reply@sender.test", "Sender \"Ops\""),
				new OutboundEmail.Address("support@sender.test", null), new OutboundEmail.Address("ana@example.test", "Ana"),
				List.of("cc@example.test"), List.of(), "Hola Ana", "<p>Hola</p>", "Hola", List.of("reset"));
	}

	void respond(int status, String body) {
		resend.stubFor(post("/emails").willReturn(aResponse().withStatus(status)
			.withHeader("Content-Type", "application/json")
			.withBody(body)));
	}

	SendException failure() {
		return catchThrowableOfType(SendException.class, () -> sender.send(email(UUID.randomUUID())));
	}

	@Test
	void ac_12_2_postsTheMessageWithTheIdAsIdempotencyKeyAndTag() throws Exception {
		UUID id = UUID.randomUUID();
		respond(200, "{\"id\": \"49a3999c-0ce1-4ea6-ab68-afcd6dc2e794\"}");

		SendResult result = sender.send(email(id));

		assertThat(result.provider()).isEqualTo("resend");
		assertThat(result.providerMessageId()).isEqualTo("49a3999c-0ce1-4ea6-ab68-afcd6dc2e794");
		resend.verify(postRequestedFor(urlEqualTo("/emails"))
			.withHeader("Authorization", equalTo("Bearer " + KEY))
			.withHeader("Idempotency-Key", equalTo(id.toString()))
			.withHeader("Content-Type", equalTo("application/json"))
			.withRequestBody(equalToJson("""
					{"from": "\\"Sender \\\\\\"Ops\\\\\\"\\" <no-reply@sender.test>",
					 "to": ["\\"Ana\\" <ana@example.test>"],
					 "cc": ["cc@example.test"],
					 "reply_to": "support@sender.test",
					 "subject": "Hola Ana", "html": "<p>Hola</p>", "text": "Hola",
					 "tags": [{"name": "message_id", "value": "%s"}, {"name": "reset", "value": "true"}]}
					""".formatted(id))));
	}

	@Test
	void ac_12_7_theSameMessageAlwaysProducesTheSameBody() {
		UUID id = UUID.randomUUID();
		assertThat(sender.body(email(id))).isEqualTo(sender.body(email(id)));
		assertThat(sender.body(email(id)).keySet()).containsExactly("from", "to", "cc", "reply_to", "subject", "html",
				"text", "tags");
	}

	@ParameterizedTest(name = "{0} {1} -> {2} {3}")
	@CsvSource({
			// Configuration of our own account: breaker opens, the attempt does not count.
			"401, missing_api_key, CONFIGURATION, PROVIDER_CONFIGURATION",
			"401, restricted_api_key, CONFIGURATION, PROVIDER_CONFIGURATION",
			"403, restricted_api_key, CONFIGURATION, PROVIDER_CONFIGURATION",
			"403, suspended_api_key, CONFIGURATION, PROVIDER_CONFIGURATION",
			"403, invalid_permission, CONFIGURATION, PROVIDER_CONFIGURATION",
			"404, not_found, CONFIGURATION, PROVIDER_CONFIGURATION",
			"405, method_not_allowed, CONFIGURATION, PROVIDER_CONFIGURATION",
			// The tenant's domain: only that tenant pauses.
			"403, validation_error, TENANT_CONFIGURATION, PROVIDER_DOMAIN_NOT_VERIFIED",
			// Final rejections of this message.
			"400, validation_error, PERMANENT, PROVIDER_REJECTED",
			"400, invalid_idempotency_key, PERMANENT, PROVIDER_REJECTED",
			"422, missing_required_field, PERMANENT, PROVIDER_REJECTED",
			"422, invalid_from_address, PERMANENT, PROVIDER_REJECTED",
			"409, invalid_idempotent_request, PERMANENT, PROVIDER_IDEMPOTENCY_CONFLICT",
			// The same key still in flight, rate limits and outages: retry.
			"409, concurrent_idempotent_requests, TRANSIENT, PROVIDER_UNAVAILABLE",
			"409, resource_locked, TRANSIENT, PROVIDER_UNAVAILABLE",
			"429, rate_limit_exceeded, TRANSIENT, PROVIDER_RATE_LIMITED",
			"429, daily_quota_exceeded, TRANSIENT, PROVIDER_RATE_LIMITED",
			"429, monthly_quota_exceeded, TRANSIENT, PROVIDER_RATE_LIMITED",
			"500, application_error, TRANSIENT, PROVIDER_UNAVAILABLE",
			"502, unknown, TRANSIENT, PROVIDER_UNAVAILABLE",
			"503, service_unavailable, TRANSIENT, PROVIDER_UNAVAILABLE" })
	void docs05_4_everyResendErrorIsClassified(int status, String name, SendException.Kind kind, String code) {
		respond(status, "{\"statusCode\": %d, \"name\": \"%s\", \"message\": \"to ana@example.test\"}".formatted(status,
				name));

		SendException failure = failure();

		assertThat(failure.kind()).isEqualTo(kind);
		assertThat(failure.code()).isEqualTo(code);
		assertThat(failure.providerError()).isEqualTo(name);
		// Resend's message may echo addresses: only the error name is kept.
		assertThat(failure.getMessage()).isEqualTo("Resend " + status + " " + name).doesNotContain("ana@");
	}

	@Test
	void anUnparseableErrorBodyKeepsTheStatusClass() {
		respond(503, "<html>Bad gateway</html>");
		assertThat(failure().kind()).isEqualTo(SendException.Kind.TRANSIENT);
		respond(422, "{\"name\": \"Contains ana@example.test\"}");
		assertThat(failure().providerError()).isEqualTo("unknown");
	}

	@Test
	void ac_13_4_retryAfterIsHonouredInSecondsOrAsAnHttpDate() {
		resend.stubFor(post("/emails").willReturn(aResponse().withStatus(429)
			.withHeader("Retry-After", "17")
			.withBody("{\"name\": \"rate_limit_exceeded\"}")));
		assertThat(failure().retryAfter()).isEqualTo(Duration.ofSeconds(17));

		String inTwoMinutes = DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now().plusMinutes(2));
		resend.stubFor(post("/emails").willReturn(aResponse().withStatus(429)
			.withHeader("Retry-After", inTwoMinutes)
			.withBody("{\"name\": \"daily_quota_exceeded\"}")));
		assertThat(failure().retryAfter()).isBetween(Duration.ofSeconds(100), Duration.ofSeconds(121));
	}

	@Test
	void ac_13_3_timeoutsAndBrokenConnectionsAreTransient() {
		resend.stubFor(post("/emails").willReturn(aResponse().withStatus(200).withFixedDelay(2_000)));
		SendException timeout = failure();
		assertThat(timeout.kind()).isEqualTo(SendException.Kind.TRANSIENT);
		assertThat(timeout.code()).isEqualTo("PROVIDER_TIMEOUT");

		resend.stubFor(post("/emails").willReturn(aResponse().withFault(Fault.CONNECTION_RESET_BY_PEER)));
		SendException reset = failure();
		assertThat(reset.kind()).isEqualTo(SendException.Kind.TRANSIENT);
		assertThat(reset.code()).isEqualTo("PROVIDER_UNAVAILABLE");
	}

	@Test
	void aSuccessWithoutAnIdIsRetriedNotTrusted() {
		respond(200, "{}");
		assertThat(failure().kind()).isEqualTo(SendException.Kind.TRANSIENT);
	}

	@Test
	void nfr_07_theApiKeyNeverAppearsInItsDescription() {
		assertThat(sender.toString()).doesNotContain(KEY).contains("***");
	}

}
