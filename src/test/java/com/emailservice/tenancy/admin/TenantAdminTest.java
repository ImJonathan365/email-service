package com.emailservice.tenancy.admin;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.emailservice.support.AuditRows;
import com.emailservice.support.IntegrationTest;
import com.emailservice.support.IntegrationTestEnvironment;
import com.emailservice.support.TestHttp;

/** FR-02 (tenant administration) and the admin side of FR-22 (audit). */
class TenantAdminTest extends IntegrationTest {

	static String uniqueSlug() {
		return "t-" + UUID.randomUUID().toString().substring(0, 8);
	}

	static String requestId() {
		return "test-" + UUID.randomUUID();
	}

	TestHttp.Request admin(TestHttp.Request request) {
		return request.header("X-Admin-Key", IntegrationTestEnvironment.ADMIN_API_KEY);
	}

	static String createBody(String slug) {
		return """
				{"slug": "%s", "name": "Test Tenant", "fromEmail": "no-reply@example.test", "fromName": "Test",
				 "allowedFromDomains": ["Example.TEST"], "allowedLinkHosts": ["app.example.test"]}
				""".formatted(slug);
	}

	@Test
	void ac_02_1_and_02_5_createsTenantWithDefaults() throws Exception {
		String slug = uniqueSlug();
		String requestId = requestId();
		HttpResponse<String> response = admin(http().post("/admin/v1/tenants"))
			.header("X-Request-Id", requestId)
			.json(createBody(slug))
			.send();

		assertThat(response.statusCode()).isEqualTo(201);
		assertThat(response.body()).contains("\"slug\":\"" + slug + "\"")
			.contains("\"status\":\"ACTIVE\"")
			.contains("\"locale\":\"es-CR\"")
			.contains("\"timezone\":\"America/Costa_Rica\"")
			.contains("\"rateLimitPerMinute\":60")
			.contains("\"dailyQuota\":1000")
			.contains("\"retentionDays\":90")
			.contains("\"storeRenderedContent\":false")
			.contains("\"allowedFromDomains\":[\"example.test\"]");

		List<AuditRows.Row> audit = AuditRows.forRequest(requestId);
		assertThat(audit).extracting(AuditRows.Row::action).containsExactly("TENANT_CREATED");
		assertThat(audit.getFirst().actorType()).isEqualTo("ADMIN");
		assertThat(audit.getFirst().actorId()).startsWith("admin:").doesNotContain(IntegrationTestEnvironment.ADMIN_API_KEY);
		assertThat(audit.getFirst().tenantId()).isNotNull();
	}

	@Test
	void ac_02_2_duplicateSlugIsAConflict() throws Exception {
		String slug = uniqueSlug();
		assertThat(admin(http().post("/admin/v1/tenants")).json(createBody(slug)).send().statusCode()).isEqualTo(201);

		HttpResponse<String> duplicate = admin(http().post("/admin/v1/tenants")).json(createBody(slug)).send();
		assertThat(duplicate.statusCode()).isEqualTo(409);
		assertThat(duplicate.body()).contains("\"code\":\"TENANT_SLUG_TAKEN\"");
	}

	@Test
	void ac_02_3_suspendAndReactivateAreAudited() throws Exception {
		String slug = uniqueSlug();
		admin(http().post("/admin/v1/tenants")).json(createBody(slug)).send();

		String suspendId = requestId();
		HttpResponse<String> suspended = admin(http().patch("/admin/v1/tenants/" + slug))
			.header("X-Request-Id", suspendId)
			.json("{\"status\": \"SUSPENDED\"}")
			.send();
		assertThat(suspended.statusCode()).isEqualTo(200);
		assertThat(suspended.body()).contains("\"status\":\"SUSPENDED\"");
		assertThat(AuditRows.forRequest(suspendId)).extracting(AuditRows.Row::action)
			.containsExactly("TENANT_SUSPENDED");

		String reactivateId = requestId();
		HttpResponse<String> reactivated = admin(http().patch("/admin/v1/tenants/" + slug))
			.header("X-Request-Id", reactivateId)
			.json("{\"status\": \"ACTIVE\"}")
			.send();
		assertThat(reactivated.body()).contains("\"status\":\"ACTIVE\"");
		assertThat(AuditRows.forRequest(reactivateId)).extracting(AuditRows.Row::action)
			.containsExactly("TENANT_REACTIVATED");
	}

	@Test
	void ac_02_4_tenantApiKeyIsNotAnAdminCredential() throws Exception {
		String requestId = requestId();
		HttpResponse<String> response = http().get("/admin/v1/tenants")
			.header("Authorization", "Bearer esk_test_AbCdEfGh_" + "x".repeat(43))
			.header("X-Request-Id", requestId)
			.send();

		assertThat(response.statusCode()).isEqualTo(403);
		assertThat(response.body()).contains("\"code\":\"ADMIN_REQUIRED\"");
		assertThat(AuditRows.forRequest(requestId)).extracting(AuditRows.Row::action)
			.containsExactly("ADMIN_ACCESS_DENIED");
	}

	@Test
	void wrongAdminKeyIsRejected() throws Exception {
		HttpResponse<String> response = http().get("/admin/v1/tenants")
			.header("X-Admin-Key", IntegrationTestEnvironment.ADMIN_API_KEY + "x")
			.send();
		assertThat(response.statusCode()).isEqualTo(403);
		assertThat(response.body()).contains("\"code\":\"ADMIN_REQUIRED\"");
	}

	@Test
	void listIsAuditedAsAdminAccess() throws Exception {
		String slug = uniqueSlug();
		admin(http().post("/admin/v1/tenants")).json(createBody(slug)).send();

		String requestId = requestId();
		HttpResponse<String> response = admin(http().get("/admin/v1/tenants")).header("X-Request-Id", requestId).send();
		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.body()).startsWith("{\"data\":[").contains("\"slug\":\"" + slug + "\"");
		assertThat(AuditRows.forRequest(requestId)).extracting(AuditRows.Row::action).containsExactly("ADMIN_ACCESS");
	}

	@Test
	void updateChangesOnlyGivenFieldsAndAuditsTheirNames() throws Exception {
		String slug = uniqueSlug();
		admin(http().post("/admin/v1/tenants")).json(createBody(slug)).send();

		String requestId = requestId();
		HttpResponse<String> response = admin(http().patch("/admin/v1/tenants/" + slug))
			.header("X-Request-Id", requestId)
			.json("{\"rateLimitPerMinute\": 120, \"allowedLinkHosts\": [\"cdn.example.test\"]}")
			.send();

		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.body()).contains("\"rateLimitPerMinute\":120")
			.contains("\"allowedLinkHosts\":[\"cdn.example.test\"]")
			.contains("\"dailyQuota\":1000")
			.contains("\"allowedFromDomains\":[\"example.test\"]");
		List<AuditRows.Row> audit = AuditRows.forRequest(requestId);
		assertThat(audit).extracting(AuditRows.Row::action).containsExactly("TENANT_UPDATED");
		assertThat(audit.getFirst().metadata()).contains("rateLimitPerMinute", "allowedLinkHosts")
			.doesNotContain("120");
	}

	@Test
	void updateOfUnknownTenantIsNotFound() throws Exception {
		HttpResponse<String> response = admin(http().patch("/admin/v1/tenants/" + uniqueSlug()))
			.json("{\"name\": \"x\"}")
			.send();
		assertThat(response.statusCode()).isEqualTo(404);
		assertThat(response.body()).contains("\"code\":\"RESOURCE_NOT_FOUND\"");
	}

	@Test
	void invalidFieldsAreReportedTogether() throws Exception {
		HttpResponse<String> beanValidation = admin(http().post("/admin/v1/tenants"))
			.json("""
					{"slug": "Bad Slug", "name": "x", "fromEmail": "not-an-email", "fromName": "a\\r\\nBcc: x@y.z"}
					""")
			.send();
		assertThat(beanValidation.statusCode()).isEqualTo(422);
		assertThat(beanValidation.body()).contains("\"code\":\"VALIDATION_ERROR\"")
			.contains("\"field\":\"slug\"")
			.contains("\"field\":\"fromEmail\"")
			.contains("\"field\":\"fromName\"");

		HttpResponse<String> serviceValidation = admin(http().post("/admin/v1/tenants"))
			.json("""
					{"slug": "%s", "name": "x", "fromEmail": "a@example.test", "fromName": "x",
					 "locale": "fr", "timezone": "Mars/Base", "allowedLinkHosts": ["https://bad/"]}
					""".formatted(uniqueSlug()))
			.send();
		assertThat(serviceValidation.statusCode()).isEqualTo(422);
		assertThat(serviceValidation.body()).contains("\"field\":\"locale\"")
			.contains("\"field\":\"timezone\"")
			.contains("\"field\":\"allowedLinkHosts[0]\"");
	}

	@Test
	void nonJsonBodyIsUnsupportedMediaType() throws Exception {
		HttpResponse<String> response = admin(http().post("/admin/v1/tenants"))
			.header("Content-Type", "text/plain")
			.send();
		assertThat(response.statusCode()).isEqualTo(415);
		assertThat(response.body()).contains("\"code\":\"UNSUPPORTED_MEDIA_TYPE\"");
	}

	@Test
	void unsupportedMethodIsMethodNotAllowed() throws Exception {
		HttpResponse<String> response = admin(http().delete("/admin/v1/tenants")).send();
		assertThat(response.statusCode()).isEqualTo(405);
		assertThat(response.body()).contains("\"code\":\"METHOD_NOT_ALLOWED\"");
		assertThat(response.headers().firstValue("Allow")).hasValue("GET, POST");
	}

	@Test
	void malformedJsonIsBadRequest() throws Exception {
		HttpResponse<String> response = admin(http().post("/admin/v1/tenants")).json("{\"slug\": ").send();
		assertThat(response.statusCode()).isEqualTo(400);
		assertThat(response.body()).contains("\"code\":\"MALFORMED_REQUEST\"");
	}

}
