package com.emailservice.support;

import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.emailservice.support.PostgresTestDatabase.Role;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Signs and posts webhooks the way Resend does (Svix), with the fictional test secret. Tests build
 * their own signatures; no real secret and no real provider are ever involved.
 */
public final class Webhooks {

	private static final JsonMapper JSON = JsonMapper.builder().build();

	private final TestHttp http;

	public Webhooks(TestHttp http) {
		this.http = http;
	}

	public static String sign(String secret, String id, long timestamp, String body) {
		try {
			Mac mac = Mac.getInstance("HmacSHA256");
			mac.init(new SecretKeySpec(Base64.getDecoder().decode(secret.substring("whsec_".length())), "HmacSHA256"));
			byte[] signature = mac.doFinal((id + "." + timestamp + "." + body).getBytes(StandardCharsets.UTF_8));
			return "v1," + Base64.getEncoder().encodeToString(signature);
		}
		catch (Exception ex) {
			throw new IllegalStateException(ex);
		}
	}

	public static String newEventId() {
		return "msg_" + UUID.randomUUID().toString().replace("-", "");
	}

	/** Posts a correctly signed, current event. */
	public HttpResponse<String> post(String eventId, String body) throws Exception {
		long now = Instant.now().getEpochSecond();
		return post(eventId, now, sign(IntegrationTestEnvironment.WEBHOOK_SECRET, eventId, now, body), body);
	}

	public HttpResponse<String> post(String eventId, long timestamp, String signature, String body) throws Exception {
		TestHttp.Request request = http.post("/webhooks/resend").json(body);
		if (eventId != null) {
			request.header("svix-id", eventId);
		}
		request.header("svix-timestamp", String.valueOf(timestamp));
		if (signature != null) {
			request.header("svix-signature", signature);
		}
		return request.send();
	}

	/** A Resend email event as documented, with the fields that must not be stored. */
	public static String emailEvent(String type, String emailId, UUID messageIdTag, String extraData) {
		String tags = messageIdTag == null ? "{\"category\": \"reset\"}"
				: "{\"category\": \"reset\", \"message_id\": \"" + messageIdTag + "\"}";
		return """
				{"type": "%s", "created_at": "%s",
				 "data": {"created_at": "2026-10-11T10:00:00.000Z", "email_id": "%s",
				          "message_id": "<111-222@email.example.test>", "from": "Sender <no-reply@sender.test>",
				          "to": ["ana@example.test"], "subject": "Hola Ana", "tags": %s%s}}
				""".formatted(type, Instant.now(), emailId, tags, extraData.isEmpty() ? "" : ", " + extraData);
	}

	/** The stored email_event row for a provider event id, as JSON, or null. */
	public static JsonNode storedEvent(String eventId) throws Exception {
		try (Connection system = PostgresTestDatabase.connect(Role.SYSTEM);
				PreparedStatement statement = system
					.prepareStatement("SELECT row_to_json(e)::text FROM email_event e WHERE provider_event_id = ?")) {
			statement.setString(1, eventId);
			try (ResultSet rs = statement.executeQuery()) {
				return rs.next() ? JSON.readTree(rs.getString(1)) : null;
			}
		}
	}

	public static long countEvents(String eventId) throws Exception {
		try (Connection system = PostgresTestDatabase.connect(Role.SYSTEM);
				PreparedStatement statement = system
					.prepareStatement("SELECT count(*) FROM email_event WHERE provider_event_id = ?")) {
			statement.setString(1, eventId);
			try (ResultSet rs = statement.executeQuery()) {
				rs.next();
				return rs.getLong(1);
			}
		}
	}

}
