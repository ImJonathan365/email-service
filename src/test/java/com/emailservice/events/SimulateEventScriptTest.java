package com.emailservice.events;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.emailservice.sending.SendingTestSupport;
import com.emailservice.support.IntegrationTest;
import com.emailservice.support.IntegrationTestEnvironment;
import com.emailservice.support.PostgresTestDatabase;
import com.emailservice.support.PostgresTestDatabase.Role;

/**
 * scripts/simulate-event.sh runs against the real webhook endpoint: its events are signed and
 * shaped like Resend's, and they change states and create suppressions as real ones do (FR-16,
 * FR-17, ADR-0012). A message sent through SMTP has no provider id, as with Mailpit locally.
 */
class SimulateEventScriptTest extends IntegrationTest {

	static final String OTHER_SECRET = "whsec_dGVzdC1vbmx5LXdlYmhvb2stc2VjcmV0LTAwMDI="; // gitleaks:allow

	SendingTestSupport tenant;

	@BeforeEach
	void setUp() throws Exception {
		tenant = new SendingTestSupport(http(), "SECURITY");
	}

	record Sent(UUID id, String to) {
	}

	record Run(int exitCode, String output) {
	}

	Sent sentThroughSmtp() throws Exception {
		String to = SendingTestSupport.uniqueAddress();
		HttpResponse<String> accepted = tenant.send(tenant.bodyTo(to, ""), null);
		assertThat(accepted.statusCode()).as(accepted.body()).isEqualTo(202);
		UUID id = UUID.fromString(SendingTestSupport.json(accepted).get("id").stringValue());
		WebhookTest.sql("""
				UPDATE email_message SET status = 'SENT', provider = 'smtp', provider_message_id = NULL,
				    sent_at = now(), attempts = 1, first_attempt_at = now()
				WHERE id = ?
				""", id);
		return new Sent(id, to);
	}

	Run simulate(String secret, String messageId, String event) throws IOException, InterruptedException {
		ProcessBuilder builder = new ProcessBuilder("bash", "scripts/simulate-event.sh", messageId, event)
			.redirectErrorStream(true);
		Map<String, String> env = builder.environment();
		env.keySet().removeAll(List.of("BASE_URL", "TO", "EMAIL_ID"));
		env.put("EMAIL_SERVICE_URL", "http://localhost:" + port());
		env.put("MAIL_WEBHOOK_SIGNING_SECRET", secret);
		Process process = builder.start();
		assertThat(process.waitFor(30, TimeUnit.SECONDS)).as("script finished").isTrue();
		return new Run(process.exitValue(), new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8));
	}

	Run simulate(Sent message, String event) throws Exception {
		Run run = simulate(IntegrationTestEnvironment.WEBHOOK_SECRET, message.id().toString(), event);
		assertThat(run.exitCode()).as(run.output()).isZero();
		assertThat(run.output()).contains("HTTP 204");
		return run;
	}

	static String status(Sent message) throws Exception {
		return SendingTestSupport.row(message.id()).get("status").stringValue();
	}

	static List<String> eventTypes(UUID messageId) throws Exception {
		try (Connection system = PostgresTestDatabase.connect(Role.SYSTEM);
				PreparedStatement statement = system
					.prepareStatement("SELECT type FROM email_event WHERE message_id = ? ORDER BY received_at")) {
			statement.setObject(1, messageId);
			try (ResultSet rs = statement.executeQuery()) {
				List<String> types = new ArrayList<>();
				while (rs.next()) {
					types.add(rs.getString(1));
				}
				return types;
			}
		}
	}

	@Test
	void deliveredMarksTheMessageDelivered() throws Exception {
		Sent message = sentThroughSmtp();

		simulate(message, "delivered");

		assertThat(status(message)).isEqualTo("DELIVERED");
		assertThat(eventTypes(message.id())).containsExactly("DELIVERED");
		assertThat(SuppressionEventTest.global(message.to())).isNull();
	}

	@Test
	void bouncedMarksTheMessageAndSuppressesTheAddressGlobally() throws Exception {
		Sent message = sentThroughSmtp();

		simulate(message, "bounced");

		assertThat(status(message)).isEqualTo("BOUNCED");
		assertThat(SuppressionEventTest.global(message.to()).get("reason").stringValue()).isEqualTo("HARD_BOUNCE");
	}

	@Test
	void complainedMarksTheMessageAndSuppressesTheAddressGlobally() throws Exception {
		Sent message = sentThroughSmtp();

		simulate(message, "complained");

		assertThat(status(message)).isEqualTo("COMPLAINED");
		assertThat(SuppressionEventTest.global(message.to()).get("reason").stringValue()).isEqualTo("COMPLAINT");
	}

	@Test
	void delayedIsRecordedWithoutChangingTheState() throws Exception {
		Sent message = sentThroughSmtp();

		simulate(message, "delayed");

		assertThat(status(message)).isEqualTo("SENT");
		assertThat(eventTypes(message.id())).containsExactly("DELIVERY_DELAYED");
		assertThat(SuppressionEventTest.global(message.to())).isNull();
	}

	@Test
	void anotherSecretIsRefusedLikeAnyForgedWebhook() throws Exception {
		Sent message = sentThroughSmtp();

		Run run = simulate(OTHER_SECRET, message.id().toString(), "bounced");

		assertThat(run.exitCode()).isNotZero();
		assertThat(run.output()).contains("HTTP 401");
		assertThat(status(message)).isEqualTo("SENT");
		assertThat(eventTypes(message.id())).isEmpty();
		assertThat(SuppressionEventTest.global(message.to())).isNull();
	}

	@Test
	void anUnknownMessageIdSuppressesNobody() throws Exception {
		Run run = simulate(IntegrationTestEnvironment.WEBHOOK_SECRET, UUID.randomUUID().toString(), "bounced");

		assertThat(run.exitCode()).as(run.output()).isZero();
		assertThat(run.output()).as("uncorrelatable events are acknowledged, never retried").contains("HTTP 204");
	}

}
