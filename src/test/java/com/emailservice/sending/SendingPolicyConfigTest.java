package com.emailservice.sending;

import static com.emailservice.sending.SendingTestSupport.suppress;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.test.context.TestPropertySource;

import com.emailservice.support.IntegrationTest;

/** Configuration-driven policies: recipient allowlist (AC-09.4) and SUPPRESSION_REJECT_MODE=reject (AC-10.1). */
@TestPropertySource(properties = { "app.sending.allowed-recipient-domains=example.test",
		"app.sending.suppression-reject-mode=reject" })
class SendingPolicyConfigTest extends IntegrationTest {

	@Test
	void ac_09_4_recipientsOutsideTheAllowlistAreRejected() throws Exception {
		SendingTestSupport a = new SendingTestSupport(http(), "SECURITY");
		assertThat(a.send(a.body("").replace("ana@example.test", "ana@mail.example.test"), null).statusCode())
			.isEqualTo(202);

		for (String body : new String[] { a.body("").replace("ana@example.test", "ana@gmail.test"),
				a.body("\"bcc\": [\"x@elsewhere.test\"]") }) {
			HttpResponse<String> response = a.send(body, null);
			assertThat(response.statusCode()).isEqualTo(403);
			assertThat(response.body()).contains("\"code\":\"RECIPIENT_NOT_ALLOWED_IN_ENV\"");
		}
	}

	@Test
	void ac_10_1_rejectModeAnswers422AndCreatesNothing() throws Exception {
		SendingTestSupport a = new SendingTestSupport(http(), "SECURITY");
		String to = "blocked-" + UUID.randomUUID().toString().substring(0, 8) + "@example.test";
		suppress(to, "GLOBAL", null, "HARD_BOUNCE");

		HttpResponse<String> response = a.send(a.body("").replace("ana@example.test", to), null);
		assertThat(response.statusCode()).isEqualTo(422);
		assertThat(response.body()).contains("\"code\":\"RECIPIENT_SUPPRESSED\"").doesNotContain(to);
		assertThat(SendingTestSupport.countMessages(a.tenant.id())).isZero();
	}

}
