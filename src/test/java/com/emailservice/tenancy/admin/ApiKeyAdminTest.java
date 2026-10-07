package com.emailservice.tenancy.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import com.emailservice.support.AuditRows;
import com.emailservice.support.IntegrationTest;
import com.emailservice.support.IntegrationTestEnvironment;
import com.emailservice.support.PostgresTestDatabase;
import com.emailservice.support.PostgresTestDatabase.Role;
import com.emailservice.support.TestHttp;
import com.emailservice.tenancy.ApiKeyFormat;

/** FR-03 (issue, list, revoke) and the issuance side of FR-33 (scopes and CIDRs). */
class ApiKeyAdminTest extends IntegrationTest {

	static final Pattern API_KEY = Pattern.compile("\"apiKey\":\"(esk_test_[0-9A-Za-z]{8}_[0-9A-Za-z]{43})\"");

	static final Pattern ID = Pattern.compile("\"id\":\"([0-9a-f-]{36})\"");

	TestHttp.Request admin(TestHttp.Request request) {
		return request.header("X-Admin-Key", IntegrationTestEnvironment.ADMIN_API_KEY);
	}

	String createTenant() throws Exception {
		String slug = "k-" + UUID.randomUUID().toString().substring(0, 8);
		HttpResponse<String> response = admin(http().post("/admin/v1/tenants")).json("""
				{"slug": "%s", "name": "Keys", "fromEmail": "no-reply@example.test", "fromName": "Keys"}
				""".formatted(slug)).send();
		assertThat(response.statusCode()).isEqualTo(201);
		return slug;
	}

	HttpResponse<String> issue(String slug, String body) throws Exception {
		return admin(http().post("/admin/v1/tenants/" + slug + "/api-keys")).json(body).send();
	}

	static String group(Pattern pattern, String body) {
		Matcher matcher = pattern.matcher(body);
		assertThat(matcher.find()).as("pattern %s in %s", pattern, body).isTrue();
		return matcher.group(1);
	}

	@Test
	void ac_03_1_secretIsShownOnceAndOnlyItsHashIsStored() throws Exception {
		String slug = createTenant();
		String requestId = "test-" + UUID.randomUUID();
		HttpResponse<String> response = admin(http().post("/admin/v1/tenants/" + slug + "/api-keys"))
			.header("X-Request-Id", requestId)
			.json("{\"name\": \"production 2026\"}")
			.send();

		assertThat(response.statusCode()).isEqualTo(201);
		String token = group(API_KEY, response.body());
		assertThat(response.body()).contains("\"warning\":");
		ApiKeyFormat.Parsed parsed = ApiKeyFormat.parse(token).orElseThrow();

		try (Connection system = PostgresTestDatabase.connect(Role.SYSTEM);
				PreparedStatement statement = system
					.prepareStatement("SELECT key_prefix, key_hash, row_to_json(api_key)::text FROM api_key WHERE key_prefix = ?")) {
			statement.setString(1, parsed.keyPrefix());
			try (ResultSet rs = statement.executeQuery()) {
				assertThat(rs.next()).isTrue();
				assertThat(rs.getBytes("key_hash")).isEqualTo(ApiKeyFormat.hash(parsed.secret()));
				assertThat(rs.getString(3)).doesNotContain(parsed.secret());
			}
		}

		List<AuditRows.Row> audit = AuditRows.forRequest(requestId);
		assertThat(audit).extracting(AuditRows.Row::action).containsExactly("API_KEY_ISSUED");
		assertThat(audit.getFirst().metadata()).contains(parsed.keyPrefix()).doesNotContain(parsed.secret());
	}

	@Test
	void ac_33_1_defaultScopesAreSendAndRead() throws Exception {
		HttpResponse<String> response = issue(createTenant(), "{\"name\": \"default\"}");
		assertThat(response.body()).contains("\"scopes\":[\"emails:send\",\"emails:read\"]")
			.contains("\"allowedCidrs\":[]");
	}

	@Test
	void ac_03_2_listShowsMetadataButNeverTheSecret() throws Exception {
		String slug = createTenant();
		String token = group(API_KEY, issue(slug, """
				{"name": "ci", "scopes": ["templates:write", "emails:read"], "allowedCidrs": ["10.20.0.0/16", "192.0.2.7"],
				 "expiresAt": "2099-01-01T00:00:00Z"}
				""").body());

		HttpResponse<String> list = admin(http().get("/admin/v1/tenants/" + slug + "/api-keys")).send();
		assertThat(list.statusCode()).isEqualTo(200);
		assertThat(list.body()).contains("\"keyPrefix\":\"" + ApiKeyFormat.parse(token).orElseThrow().keyPrefix() + "\"")
			.contains("\"name\":\"ci\"")
			.contains("\"status\":\"ACTIVE\"")
			.contains("\"scopes\":[\"templates:write\",\"emails:read\"]")
			.contains("\"allowedCidrs\":[\"10.20.0.0/16\",\"192.0.2.7/32\"]")
			.contains("\"expiresAt\":\"2099-01-01T00:00:00Z\"")
			.contains("\"lastUsedAt\":null")
			.doesNotContain("apiKey")
			.doesNotContain(token.substring(token.lastIndexOf('_') + 1));
	}

	@Test
	void ac_03_3_severalKeysCanBeActiveAtOnce() throws Exception {
		String slug = createTenant();
		issue(slug, "{\"name\": \"old\"}");
		issue(slug, "{\"name\": \"new\"}");

		String list = admin(http().get("/admin/v1/tenants/" + slug + "/api-keys")).send().body();
		assertThat(Pattern.compile("\"status\":\"ACTIVE\"").matcher(list).results().count()).isEqualTo(2);
	}

	@Test
	void ac_03_4_and_03_6_revocationIsAuditedAndIdempotent() throws Exception {
		String slug = createTenant();
		String id = group(ID, issue(slug, "{\"name\": \"to revoke\"}").body());

		String requestId = "test-" + UUID.randomUUID();
		HttpResponse<String> revoked = admin(http().delete("/admin/v1/api-keys/" + id))
			.header("X-Request-Id", requestId)
			.send();
		assertThat(revoked.statusCode()).isEqualTo(204);
		assertThat(AuditRows.forRequest(requestId)).extracting(AuditRows.Row::action)
			.containsExactly("API_KEY_REVOKED");

		String list = admin(http().get("/admin/v1/tenants/" + slug + "/api-keys")).send().body();
		assertThat(list).contains("\"status\":\"REVOKED\"").doesNotContain("\"revokedAt\":null");

		String againId = "test-" + UUID.randomUUID();
		assertThat(admin(http().delete("/admin/v1/api-keys/" + id)).header("X-Request-Id", againId).send().statusCode())
			.isEqualTo(204);
		assertThat(AuditRows.forRequest(againId)).extracting(AuditRows.Row::action).doesNotContain("API_KEY_REVOKED");
	}

	@Test
	void revokingAnUnknownKeyIsNotFound() throws Exception {
		assertThat(admin(http().delete("/admin/v1/api-keys/" + UUID.randomUUID())).send().statusCode()).isEqualTo(404);
		assertThat(admin(http().delete("/admin/v1/api-keys/not-a-uuid")).send().statusCode()).isEqualTo(404);
	}

	@Test
	void ac_03_7_scopesAndCidrsCannotBeModified() throws Exception {
		String id = group(ID, issue(createTenant(), "{\"name\": \"fixed\"}").body());
		HttpResponse<String> response = admin(http().patch("/admin/v1/api-keys/" + id))
			.json("{\"scopes\": [\"templates:write\"]}")
			.send();
		assertThat(response.statusCode()).isEqualTo(405);
		assertThat(response.body()).contains("\"code\":\"METHOD_NOT_ALLOWED\"");
		assertThat(response.headers().firstValue("Allow")).hasValue("DELETE");
	}

	@Test
	void invalidScopesCidrsAndExpiryAreRejected() throws Exception {
		HttpResponse<String> response = issue(createTenant(), """
				{"name": "bad", "scopes": ["emails:send", "admin"], "allowedCidrs": ["10.0.0.1/8"],
				 "expiresAt": "2001-01-01T00:00:00Z"}
				""");
		assertThat(response.statusCode()).isEqualTo(422);
		assertThat(response.body()).contains("\"field\":\"expiresAt\"");

		HttpResponse<String> serviceLevel = issue(createTenant(), """
				{"name": "bad", "scopes": ["emails:send", "admin"], "allowedCidrs": ["10.0.0.1/8"]}
				""");
		assertThat(serviceLevel.statusCode()).isEqualTo(422);
		assertThat(serviceLevel.body()).contains("\"field\":\"scopes[1]\"").contains("\"field\":\"allowedCidrs[0]\"");
	}

	@Test
	void issuingForAnUnknownTenantIsNotFound() throws Exception {
		assertThat(issue("no-such-tenant", "{\"name\": \"x\"}").statusCode()).isEqualTo(404);
	}

}
