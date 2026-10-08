package com.emailservice.sending;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

import com.emailservice.support.AdminApi;
import com.emailservice.support.IntegrationTestEnvironment;
import com.emailservice.support.PostgresTestDatabase;
import com.emailservice.support.PostgresTestDatabase.Role;
import com.emailservice.support.TestHttp;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * A tenant ready to send: allowed hosts and sender domains, an operator and a product key, and a
 * password-reset template published in es-CR and en through the API. Addresses are .test only.
 */
final class SendingTestSupport {

	static final JsonMapper JSON = JsonMapper.builder().build();

	static final String SCHEMA = """
			{"required": ["firstName", "resetUrl"],
			 "properties": {"firstName": {"type": "string", "maxLength": 80},
			                "resetUrl": {"type": "string", "format": "uri", "x-sensitive": true},
			                "amount": {"type": "number"}}}
			""";

	static final String VARIABLES = "{\"firstName\": \"Ana\", \"resetUrl\": \"https://app.example.test/reset?t=1\"}";

	final TestHttp http;

	final AdminApi admin;

	final AdminApi.Tenant tenant;

	final AdminApi.Key product;

	final AdminApi.Key operator;

	final String templateKey;

	SendingTestSupport(TestHttp http, String category) throws Exception {
		this.http = http;
		this.admin = new AdminApi(http);
		this.tenant = admin.createTenant();
		admin.patchTenant(tenant, """
				{"allowedLinkHosts": ["example.test"], "allowedFromDomains": ["sender.test"],
				 "fromEmail": "no-reply@sender.test", "fromName": "Sender", "replyTo": "support@sender.test"}
				""");
		this.product = admin.issueKey(tenant, "{\"name\": \"product\"}");
		this.operator = admin.issueKey(tenant,
				"{\"name\": \"operator\", \"scopes\": [\"templates:write\", \"emails:read\"]}");
		this.templateKey = "reset-" + UUID.randomUUID().toString().substring(0, 8);
		createTemplate(templateKey, category);
		int es = createVersion(templateKey, "es-CR", "Hola {{firstName}}");
		int en = createVersion(templateKey, "en", "Hi {{firstName}}");
		HttpResponse<String> published = operator(http.post("/v1/templates/" + templateKey + "/publish"))
			.json("{\"versions\": {\"es-CR\": " + es + ", \"en\": " + en + "}}")
			.send();
		assertThat(published.statusCode()).as(published.body()).isEqualTo(200);
	}

	void createTemplate(String key, String category) throws Exception {
		HttpResponse<String> response = operator(http.post("/v1/templates"))
			.json("{\"key\": \"" + key + "\", \"name\": \"Reset\", \"category\": \"" + category + "\"}")
			.send();
		assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
	}

	int createVersion(String key, String locale, String subject) throws Exception {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("locale", locale);
		body.put("subjectTemplate", subject);
		body.put("htmlTemplate", "<p>" + subject + "</p><p><a href=\"{{resetUrl}}\">link</a></p>");
		body.put("textTemplate", subject + " {{resetUrl}}");
		body.put("variablesSchema", JSON.readTree(SCHEMA));
		HttpResponse<String> response = operator(http.post("/v1/templates/" + key + "/versions"))
			.json(JSON.writeValueAsString(body))
			.send();
		assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
		return JSON.readTree(response.body()).get("version").intValue();
	}

	TestHttp.Request operator(TestHttp.Request request) {
		return request.header("Authorization", operator.bearer());
	}

	/** A send request with the given extra fields merged into a valid body. */
	String body(String extraFields) {
		return "{\"templateKey\": \"" + templateKey + "\", \"to\": {\"email\": \"ana@example.test\", \"name\": \"Ana\"}, "
				+ "\"variables\": " + VARIABLES + (extraFields.isBlank() ? "" : ", " + extraFields) + "}";
	}

	HttpResponse<String> send(String body, String idempotencyKey) throws Exception {
		TestHttp.Request request = http.post("/v1/emails").header("Authorization", product.bearer()).json(body);
		if (idempotencyKey != null) {
			request.header("Idempotency-Key", idempotencyKey);
		}
		return request.send();
	}

	static JsonNode json(HttpResponse<String> response) {
		return JSON.readTree(response.body());
	}

	/** Reads one message row as JSON through the system role, as an operator inspecting the queue. */
	static JsonNode row(UUID messageId) throws Exception {
		try (Connection system = PostgresTestDatabase.connect(Role.SYSTEM);
				PreparedStatement statement = system
					.prepareStatement("SELECT row_to_json(m)::text FROM email_message m WHERE id = ?")) {
			statement.setObject(1, messageId);
			try (ResultSet rs = statement.executeQuery()) {
				return rs.next() ? JSON.readTree(rs.getString(1)) : null;
			}
		}
	}

	static long countMessages(UUID tenantId) throws Exception {
		try (Connection system = PostgresTestDatabase.connect(Role.SYSTEM);
				PreparedStatement statement = system.prepareStatement("SELECT count(*) FROM email_message WHERE tenant_id = ?")) {
			statement.setObject(1, tenantId);
			try (ResultSet rs = statement.executeQuery()) {
				rs.next();
				return rs.getLong(1);
			}
		}
	}

	/** Suppressions arrive through webhooks and the API in H6; tests insert them directly. */
	static void suppress(String address, String scope, UUID tenantId, String reason) throws Exception {
		try (Connection system = PostgresTestDatabase.connect(Role.SYSTEM);
				PreparedStatement statement = system.prepareStatement("""
						INSERT INTO suppression (id, scope, tenant_id, email, email_hash, reason)
						VALUES (?, ?, ?, ?, ?, ?)
						""")) {
			statement.setObject(1, UUID.randomUUID());
			statement.setString(2, scope);
			statement.setObject(3, "GLOBAL".equals(scope) ? null : tenantId);
			statement.setString(4, address.toLowerCase(Locale.ROOT));
			statement.setBytes(5, hmac(address));
			statement.setString(6, reason);
			statement.executeUpdate();
		}
	}

	static byte[] hmac(String address) throws Exception {
		Mac mac = Mac.getInstance("HmacSHA256");
		mac.init(new SecretKeySpec(IntegrationTestEnvironment.SUPPRESSION_HASH_KEY.getBytes(StandardCharsets.UTF_8),
				"HmacSHA256"));
		return mac.doFinal(address.trim().toLowerCase(Locale.ROOT).getBytes(StandardCharsets.UTF_8));
	}

}
