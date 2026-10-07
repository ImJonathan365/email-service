package com.emailservice.tenancy.auth;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import com.emailservice.support.AdminApi;
import com.emailservice.support.AuditRows;
import com.emailservice.support.IntegrationTest;
import com.emailservice.support.IntegrationTestEnvironment;
import com.emailservice.support.PostgresTestDatabase;
import com.emailservice.support.PostgresTestDatabase.Role;
import com.emailservice.tenancy.RequiresScope;

/** FR-01 (API key authentication) and FR-33 (scopes and source CIDRs) on /v1. */
@ExtendWith(OutputCaptureExtension.class)
class ApiKeyAuthenticationTest extends IntegrationTest {

	static final String READ = "/v1/test-probe/read";

	AdminApi admin;

	AdminApi.Tenant tenant;

	@Autowired
	@Qualifier("requestMappingHandlerMapping")
	RequestMappingHandlerMapping handlerMapping;

	@BeforeEach
	void setUp() throws Exception {
		admin = new AdminApi(http());
		tenant = admin.createTenant();
	}

	HttpResponse<String> read(String authorization) throws Exception {
		var request = http().get(READ);
		return (authorization == null ? request : request.header("Authorization", authorization)).send();
	}

	@Test
	void ac_01_1_validKeyRunsTheRequestInItsTenantContextUnderRls() throws Exception {
		AdminApi.Key key = admin.issueKey(tenant, "{\"name\": \"product\"}");
		admin.issueKey(admin.createTenant(), "{\"name\": \"other tenant\"}");

		HttpResponse<String> response = read(key.bearer());

		assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
		String id = tenant.id().toString();
		assertThat(response.body()).contains("\"tenantId\":\"" + id + "\"")
			.contains("\"contextTenantId\":\"" + id + "\"")
			.contains("\"rlsTenantId\":\"" + id + "\"");
	}

	@Test
	void ac_01_2_missingMalformedOrUnknownKeysGetTheSameAnswer() throws Exception {
		AdminApi.Key key = admin.issueKey(tenant, "{\"name\": \"product\"}");
		String wrongSecret = key.token().substring(0, key.token().length() - 1)
				+ (key.token().endsWith("a") ? "b" : "a");
		String unknownPrefix = "esk_test_ZZZZZZZZ_" + key.token().substring(key.token().length() - 43);

		List<String> bodies = new ArrayList<>();
		for (String authorization : new String[] { null, "Basic abc", "Bearer not-a-key", "Bearer " + wrongSecret,
				"Bearer " + unknownPrefix, key.token() }) {
			HttpResponse<String> response = read(authorization);
			assertThat(response.statusCode()).as(String.valueOf(authorization)).isEqualTo(401);
			assertThat(response.body()).contains("\"code\":\"UNAUTHENTICATED\"");
			bodies.add(response.body().replaceAll("\"requestId\":\"[^\"]+\"", ""));
		}
		assertThat(bodies).allMatch(body -> body.equals(bodies.getFirst()));
	}

	@Test
	void ac_01_3_revokedKeyIsRejectedAndAudited() throws Exception {
		AdminApi.Key key = admin.issueKey(tenant, "{\"name\": \"product\"}");
		admin.revoke(key);

		String requestId = "test-" + UUID.randomUUID();
		HttpResponse<String> response = http().get(READ)
			.header("Authorization", key.bearer())
			.header("X-Request-Id", requestId)
			.send();

		assertThat(response.statusCode()).isEqualTo(401);
		assertRejectionAudited(requestId, key, "KEY_REVOKED");
	}

	@Test
	void ac_03_5_expiredKeyIsRejectedAndAudited() throws Exception {
		AdminApi.Key key = admin.issueKey(tenant, "{\"name\": \"product\"}");
		try (Connection system = PostgresTestDatabase.connect(Role.SYSTEM);
				PreparedStatement statement = system
					.prepareStatement("UPDATE api_key SET expires_at = now() - interval '1 second' WHERE id = ?")) {
			statement.setObject(1, key.id());
			statement.executeUpdate();
		}

		String requestId = "test-" + UUID.randomUUID();
		HttpResponse<String> response = http().get(READ)
			.header("Authorization", key.bearer())
			.header("X-Request-Id", requestId)
			.send();

		assertThat(response.statusCode()).isEqualTo(401);
		assertRejectionAudited(requestId, key, "KEY_EXPIRED");
	}

	@Test
	void ac_01_3_suspendedTenantIsForbiddenAndAudited() throws Exception {
		AdminApi.Key key = admin.issueKey(tenant, "{\"name\": \"product\"}");
		admin.patchTenant(tenant, "{\"status\": \"SUSPENDED\"}");

		String requestId = "test-" + UUID.randomUUID();
		HttpResponse<String> response = http().get(READ)
			.header("Authorization", key.bearer())
			.header("X-Request-Id", requestId)
			.send();

		assertThat(response.statusCode()).isEqualTo(403);
		assertThat(response.body()).contains("\"code\":\"TENANT_SUSPENDED\"");
		assertRejectionAudited(requestId, key, "TENANT_SUSPENDED");

		admin.patchTenant(tenant, "{\"status\": \"ACTIVE\"}");
		assertThat(read(key.bearer()).statusCode()).isEqualTo(200);
	}

	@Test
	void ac_01_5_lastUsedAtIsRecordedWithMinuteGranularity() throws Exception {
		AdminApi.Key key = admin.issueKey(tenant, "{\"name\": \"product\"}");
		assertThat(read(key.bearer()).statusCode()).isEqualTo(200);
		assertThat(read(key.bearer()).statusCode()).isEqualTo(200);

		try (Connection system = PostgresTestDatabase.connect(Role.SYSTEM);
				PreparedStatement statement = system.prepareStatement("SELECT last_used_at FROM api_key WHERE id = ?")) {
			statement.setObject(1, key.id());
			try (ResultSet rs = statement.executeQuery()) {
				rs.next();
				OffsetDateTime lastUsed = rs.getObject(1, OffsetDateTime.class);
				assertThat(lastUsed).isNotNull();
				assertThat(lastUsed.getSecond()).isZero();
				assertThat(lastUsed.getNano()).isZero();
			}
		}
	}

	@Test
	void ac_01_6_keyNeverAppearsInResponsesOrLogs(CapturedOutput output) throws Exception {
		AdminApi.Key key = admin.issueKey(tenant, "{\"name\": \"product\"}");
		String secret = key.token().substring(key.token().length() - 43);

		HttpResponse<String> ok = read(key.bearer());
		HttpResponse<String> forbidden = http().post("/v1/test-probe/templates").header("Authorization", key.bearer()).send();
		HttpResponse<String> unauthenticated = read("Bearer " + key.token() + "x");

		assertThat(List.of(ok.body(), forbidden.body(), unauthenticated.body())).noneMatch(body -> body.contains(secret));
		assertThat(output.getAll()).doesNotContain(secret);
	}

	@Test
	void ac_01_7_and_33_2_missingScopeIsForbidden() throws Exception {
		AdminApi.Key readOnly = admin.issueKey(tenant, "{\"name\": \"reader\", \"scopes\": [\"emails:read\"]}");
		AdminApi.Key writer = admin.issueKey(tenant, "{\"name\": \"ci\", \"scopes\": [\"templates:write\"]}");

		HttpResponse<String> denied = http().post("/v1/test-probe/templates").header("Authorization", readOnly.bearer()).send();
		assertThat(denied.statusCode()).isEqualTo(403);
		assertThat(denied.body()).contains("\"code\":\"INSUFFICIENT_SCOPE\"");

		assertThat(http().post("/v1/test-probe/templates").header("Authorization", writer.bearer()).send().statusCode())
			.isEqualTo(200);
		assertThat(read(writer.bearer()).statusCode()).isEqualTo(403);
	}

	@Test
	void ac_33_3_sourceOutsideAllowedCidrsIsForbiddenAndAudited() throws Exception {
		AdminApi.Key elsewhere = admin.issueKey(tenant,
				"{\"name\": \"office\", \"allowedCidrs\": [\"203.0.113.0/24\"]}");
		AdminApi.Key local = admin.issueKey(tenant,
				"{\"name\": \"local\", \"allowedCidrs\": [\"127.0.0.0/8\", \"::1/128\"]}");

		String requestId = "test-" + UUID.randomUUID();
		HttpResponse<String> denied = http().get(READ)
			.header("Authorization", elsewhere.bearer())
			.header("X-Request-Id", requestId)
			// No trusted proxies are configured, so a client-supplied X-Forwarded-For is ignored.
			.header("X-Forwarded-For", "203.0.113.5")
			.send();
		assertThat(denied.statusCode()).isEqualTo(403);
		assertThat(denied.body()).contains("\"code\":\"IP_NOT_ALLOWED\"");
		assertRejectionAudited(requestId, elsewhere, "IP_NOT_ALLOWED");

		assertThat(read(local.bearer()).statusCode()).isEqualTo(200);
	}

	@Test
	void adminKeyIsNotATenantCredential() throws Exception {
		HttpResponse<String> response = http().get(READ)
			.header("X-Admin-Key", IntegrationTestEnvironment.ADMIN_API_KEY)
			.send();
		assertThat(response.statusCode()).isEqualTo(401);
	}

	@Test
	void v1HandlerWithoutRequiresScopeFailsClosed() throws Exception {
		AdminApi.Key key = admin.issueKey(tenant, "{\"name\": \"product\"}");
		HttpResponse<String> response = http().get("/v1/test-probe/unannotated").header("Authorization", key.bearer()).send();
		assertThat(response.statusCode()).isEqualTo(500);
		assertThat(response.body()).contains("\"code\":\"INTERNAL_ERROR\"").doesNotContain("reached");
	}

	@Test
	void everyProductionV1HandlerDeclaresItsScope() {
		handlerMapping.getHandlerMethods().forEach((mapping, method) -> {
			boolean isV1 = mapping.getPatternValues().stream().anyMatch(pattern -> pattern.startsWith("/v1/"));
			if (isV1 && method.getBeanType() != AuthProbeController.class) {
				assertThat(method.hasMethodAnnotation(RequiresScope.class)
						|| method.getBeanType().isAnnotationPresent(RequiresScope.class))
					.as("@RequiresScope on %s", method)
					.isTrue();
			}
		});
	}

	private static void assertRejectionAudited(String requestId, AdminApi.Key key, String reason) throws Exception {
		List<AuditRows.Row> audit = AuditRows.forRequest(requestId);
		assertThat(audit).extracting(AuditRows.Row::action).containsExactly("AUTHENTICATION_REJECTED");
		assertThat(audit.getFirst().actorType()).isEqualTo("API_KEY");
		assertThat(audit.getFirst().actorId()).isEqualTo(key.id().toString());
		assertThat(audit.getFirst().metadata()).contains(reason);
		assertThat(audit.getFirst().ip()).isNotNull();
	}

}
