package com.emailservice.provider.resend;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.emailservice.provider.WebhookVerifier;
import com.emailservice.provider.WebhookVerifier.Rejected.Reason;
import com.emailservice.support.Webhooks;

/** FR-16, AC-16.1 to AC-16.3 against Svix's published example, so the algorithm is not checked against itself. */
class SvixWebhookVerifierTest {

	// Example from the Svix documentation; not a secret of ours.
	static final String SVIX_EXAMPLE_SECRET = "whsec_MfKQ9r8GKYqrTwjUPD8ILPZIo2LaLaSw"; // gitleaks:allow

	static final String ID = "msg_p5jXN8AQM9LWM0D4loKWxJek";

	static final long TIMESTAMP = 1614265330;

	static final String BODY = "{\"test\": 2432232314}";

	static final String SIGNATURE = "v1,g0hM9SsE+OTPJTGt/tmIKtSyZlE3uFJELVlNIOLJ1OE=";

	static final String OTHER_SECRET = "whsec_dGVzdC1vbmx5LXdlYmhvb2stc2VjcmV0LTAwMDI="; // gitleaks:allow

	static SvixWebhookVerifier verifier(Instant now, String... secrets) {
		return new SvixWebhookVerifier(List.of(secrets), Duration.ofMinutes(5), Clock.fixed(now, ZoneOffset.UTC));
	}

	static WebhookVerifier.Verified verify(SvixWebhookVerifier verifier, Map<String, String> headers, String body)
			throws WebhookVerifier.Rejected {
		return verifier.verify(headers::get, body.getBytes(StandardCharsets.UTF_8));
	}

	static Reason rejection(SvixWebhookVerifier verifier, Map<String, String> headers, String body) {
		return catchThrowableOfType(WebhookVerifier.Rejected.class, () -> verify(verifier, headers, body)).reason();
	}

	static Map<String, String> headers(String id, long timestamp, String signature) {
		return Map.of("svix-id", id, "svix-timestamp", String.valueOf(timestamp), "svix-signature", signature);
	}

	@Test
	void ac_16_1_acceptsSvixsPublishedExample() throws Exception {
		WebhookVerifier.Verified verified = verify(verifier(Instant.ofEpochSecond(TIMESTAMP), SVIX_EXAMPLE_SECRET),
				headers(ID, TIMESTAMP, SIGNATURE), BODY);
		assertThat(verified.eventId()).isEqualTo(ID);
		assertThat(verified.signedAt()).isEqualTo(Instant.ofEpochSecond(TIMESTAMP));
		assertThat(Webhooks.sign(SVIX_EXAMPLE_SECRET, ID, TIMESTAMP, BODY)).as("the test signer matches Svix")
			.isEqualTo(SIGNATURE);
	}

	@Test
	void ac_16_2_anyChangeToTheRawBodyOrHeadersBreaksTheSignature() {
		SvixWebhookVerifier verifier = verifier(Instant.ofEpochSecond(TIMESTAMP), SVIX_EXAMPLE_SECRET);
		// Re-serialized JSON (no space) is a different body.
		assertThat(rejection(verifier, headers(ID, TIMESTAMP, SIGNATURE), "{\"test\":2432232314}"))
			.isEqualTo(Reason.INVALID_SIGNATURE);
		assertThat(rejection(verifier, headers("msg_other", TIMESTAMP, SIGNATURE), BODY))
			.isEqualTo(Reason.INVALID_SIGNATURE);
		assertThat(rejection(verifier, headers(ID, TIMESTAMP + 1, SIGNATURE), BODY)).isEqualTo(Reason.INVALID_SIGNATURE);
		assertThat(rejection(verifier, headers(ID, TIMESTAMP, "v1,AAAA"), BODY)).isEqualTo(Reason.INVALID_SIGNATURE);
		assertThat(rejection(verifier, headers(ID, TIMESTAMP, "v2," + SIGNATURE.substring(3)), BODY))
			.as("only v1 signatures count")
			.isEqualTo(Reason.INVALID_SIGNATURE);
		assertThat(rejection(verifier(Instant.ofEpochSecond(TIMESTAMP), OTHER_SECRET), headers(ID, TIMESTAMP, SIGNATURE),
				BODY))
			.isEqualTo(Reason.INVALID_SIGNATURE);
	}

	@Test
	void ac_16_2_missingOrMalformedHeadersAreRejected() {
		SvixWebhookVerifier verifier = verifier(Instant.ofEpochSecond(TIMESTAMP), SVIX_EXAMPLE_SECRET);
		assertThat(rejection(verifier, Map.of("svix-id", ID, "svix-timestamp", String.valueOf(TIMESTAMP)), BODY))
			.isEqualTo(Reason.MISSING_HEADERS);
		assertThat(rejection(verifier, Map.of(), BODY)).isEqualTo(Reason.MISSING_HEADERS);
		assertThat(rejection(verifier, headers(ID, TIMESTAMP, SIGNATURE).entrySet()
			.stream()
			.collect(java.util.stream.Collectors.toMap(Map.Entry::getKey,
					e -> e.getKey().equals("svix-timestamp") ? "yesterday" : e.getValue())), BODY))
			.isEqualTo(Reason.MISSING_HEADERS);
	}

	@Test
	void ac_16_3_signaturesOutsideFiveMinutesEitherWayAreRejected() throws Exception {
		Map<String, String> headers = headers(ID, TIMESTAMP, SIGNATURE);
		// Signed 300 s / 301 s in the past: our clock is ahead of the signature.
		assertThat(verify(verifier(Instant.ofEpochSecond(TIMESTAMP + 300), SVIX_EXAMPLE_SECRET), headers, BODY))
			.isNotNull();
		assertThat(rejection(verifier(Instant.ofEpochSecond(TIMESTAMP + 301), SVIX_EXAMPLE_SECRET), headers, BODY))
			.isEqualTo(Reason.OUTSIDE_TOLERANCE);
		// Signed 300 s / 301 s in the future: our clock is behind the signature.
		assertThat(verify(verifier(Instant.ofEpochSecond(TIMESTAMP - 300), SVIX_EXAMPLE_SECRET), headers, BODY))
			.isNotNull();
		assertThat(rejection(verifier(Instant.ofEpochSecond(TIMESTAMP - 301), SVIX_EXAMPLE_SECRET), headers, BODY))
			.isEqualTo(Reason.OUTSIDE_TOLERANCE);
	}

	@Test
	void docs08_rotationAcceptsEitherSecretAndAnyListedSignature() throws Exception {
		SvixWebhookVerifier rotating = verifier(Instant.ofEpochSecond(TIMESTAMP), OTHER_SECRET, SVIX_EXAMPLE_SECRET);
		assertThat(verify(rotating, headers(ID, TIMESTAMP, SIGNATURE), BODY)).isNotNull();
		String several = "v1,bm90LXRoZS1zaWduYXR1cmU= " + SIGNATURE;
		assertThat(verify(verifier(Instant.ofEpochSecond(TIMESTAMP), SVIX_EXAMPLE_SECRET), headers(ID, TIMESTAMP, several),
				BODY))
			.isNotNull();
		assertThat(rotating.toString()).doesNotContain("whsec_", "MfKQ9r8");
	}

}
