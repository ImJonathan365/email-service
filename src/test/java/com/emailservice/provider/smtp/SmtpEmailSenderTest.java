package com.emailservice.provider.smtp;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.List;
import java.util.Properties;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.mail.javamail.JavaMailSenderImpl;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.utility.DockerImageName;

import com.emailservice.provider.OutboundEmail;
import com.emailservice.provider.SendException;
import com.emailservice.provider.SendResult;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** FR-15: SMTP delivers to Mailpit (a local catcher; nothing leaves the test). */
class SmtpEmailSenderTest {

	static final GenericContainer<?> MAILPIT = new GenericContainer<>(DockerImageName.parse("axllent/mailpit:v1.31.4"))
		.withExposedPorts(1025, 8025)
		.waitingFor(Wait.forHttp("/api/v1/info").forPort(8025));

	static final JsonMapper JSON = JsonMapper.builder().build();

	@BeforeAll
	static void start() {
		MAILPIT.start();
	}

	@AfterAll
	static void stop() {
		MAILPIT.stop();
	}

	static SmtpEmailSender sender(int port) {
		JavaMailSenderImpl mailSender = new JavaMailSenderImpl();
		mailSender.setHost(MAILPIT.getHost());
		mailSender.setPort(port);
		Properties properties = new Properties();
		properties.setProperty("mail.smtp.connectiontimeout", "2000");
		properties.setProperty("mail.smtp.timeout", "2000");
		mailSender.setJavaMailProperties(properties);
		return new SmtpEmailSender(mailSender);
	}

	static OutboundEmail email(UUID id) {
		return new OutboundEmail(id, new OutboundEmail.Address("no-reply@demo.localhost", "Demo vía Colmena"),
				new OutboundEmail.Address("support@demo.localhost", null), new OutboundEmail.Address("ana@example.test", "Ana"),
				List.of("cc@example.test"), List.of("bcc@example.test"), "Hola Ana & Juan",
				"<p>Hola Ana &amp; Juan</p>", "Hola Ana & Juan", List.of("password-reset"));
	}

	@Test
	void ac_15_1_messageArrivesInMailpitWithHtmlTextAndHeaders() throws Exception {
		UUID id = UUID.randomUUID();
		SendResult result = sender(MAILPIT.getMappedPort(1025)).send(email(id));
		assertThat(result.provider()).isEqualTo("smtp");
		assertThat(result.providerMessageId()).isNull();

		String api = "http://" + MAILPIT.getHost() + ":" + MAILPIT.getMappedPort(8025) + "/api/v1";
		String mailpitId = null;
		for (JsonNode summary : get(api + "/messages").get("messages").values()) {
			if ((id + "@email-service").equals(summary.get("MessageID").stringValue())) {
				mailpitId = summary.get("ID").stringValue();
			}
		}
		assertThat(mailpitId).as("message with Message-ID %s in Mailpit", id).isNotNull();
		JsonNode message = get(api + "/message/" + mailpitId);
		assertThat(message.get("Subject").stringValue()).isEqualTo("Hola Ana & Juan");
		assertThat(message.get("HTML").stringValue()).contains("<p>Hola Ana &amp; Juan</p>");
		assertThat(message.get("Text").stringValue()).contains("Hola Ana & Juan");
		assertThat(message.get("From").get("Name").stringValue()).isEqualTo("Demo vía Colmena");
		assertThat(message.get("MessageID").stringValue()).isEqualTo(id + "@email-service");
		assertThat(message.get("Cc").get(0).get("Address").stringValue()).isEqualTo("cc@example.test");
	}

	@Test
	void unreachableServerIsATransientFailure() throws Exception {
		int closedPort;
		try (ServerSocket socket = new ServerSocket(0)) {
			closedPort = socket.getLocalPort();
		}
		assertThatThrownBy(() -> sender(closedPort).send(email(UUID.randomUUID())))
			.isInstanceOfSatisfying(SendException.class, ex -> {
				assertThat(ex.kind()).isEqualTo(SendException.Kind.TRANSIENT);
				assertThat(ex.code()).isIn("PROVIDER_UNAVAILABLE", "PROVIDER_TIMEOUT");
			});
	}

	@Test
	void toStringNeverShowsContentOrAddresses() {
		assertThat(email(UUID.randomUUID()).toString()).doesNotContain("ana@example.test", "Hola");
	}

	private static JsonNode get(String url) throws Exception {
		HttpResponse<String> response = HttpClient.newHttpClient()
			.send(HttpRequest.newBuilder(URI.create(url)).build(), HttpResponse.BodyHandlers.ofString());
		return JSON.readTree(response.body());
	}

}
