package com.emailservice.events;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.emailservice.sending.SendingTestSupport;
import com.emailservice.support.AdminApi;
import com.emailservice.support.AuditRows;
import com.emailservice.support.IntegrationTest;
import com.emailservice.support.IntegrationTestEnvironment;
import com.emailservice.support.TestHttp;
import com.emailservice.support.Webhooks;

import tools.jackson.databind.JsonNode;

/** FR-18 and the global suppression administration of ADR-0012. */
class SuppressionApiTest extends IntegrationTest {

	SendingTestSupport a;

	AdminApi.Key aWriter;

	@BeforeEach
	void setUp() throws Exception {
		a = new SendingTestSupport(http(), "SECURITY");
		aWriter = writer(a);
	}

	static AdminApi.Key writer(SendingTestSupport tenant) throws Exception {
		return tenant.admin.issueKey(tenant.tenant,
				"{\"name\": \"suppressions\", \"scopes\": [\"suppressions:write\", \"emails:read\"]}");
	}

	TestHttp.Request as(AdminApi.Key key, TestHttp.Request request) {
		return request.header("Authorization", key.bearer());
	}

	TestHttp.Request asAdmin(TestHttp.Request request) {
		return request.header("X-Admin-Key", IntegrationTestEnvironment.ADMIN_API_KEY);
	}

	static String path(String email) {
		return URLEncoder.encode(email, StandardCharsets.UTF_8);
	}

	HttpResponse<String> add(AdminApi.Key key, String email) throws Exception {
		return as(key, http().post("/v1/suppressions")).json("{\"email\": \"" + email + "\", \"note\": \"asked\"}")
			.send();
	}

	/** A global HARD_BOUNCE, created the way production creates it: an orphan bounce webhook. */
	String globallyBounced() throws Exception {
		// Its own domain, so its masked form (g***@domain) is unique among all global rows.
		String address = "g" + UUID.randomUUID().toString().substring(0, 8) + "@d"
				+ UUID.randomUUID().toString().substring(0, 8) + ".example.test";
		assertThat(new Webhooks(http()).post(Webhooks.newEventId(),
				Webhooks.emailEvent("email.bounced", UUID.randomUUID().toString(), null, "", Instant.now(), address))
			.statusCode()).isEqualTo(204);
		return address;
	}

	List<JsonNode> list(AdminApi.Key key, String query) throws Exception {
		HttpResponse<String> response = as(key, http().get("/v1/suppressions" + query)).send();
		assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
		List<JsonNode> items = new ArrayList<>();
		SendingTestSupport.json(response).get("data").forEach(items::add);
		return items;
	}

	@Test
	void ac_18_2_addingIsManualForThisTenantAndRepeatingReturnsTheExistingOne() throws Exception {
		String address = SendingTestSupport.uniqueAddress();

		HttpResponse<String> created = add(aWriter, address.toUpperCase());
		HttpResponse<String> again = add(aWriter, address);

		assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
		JsonNode body = SendingTestSupport.json(created);
		assertThat(body.get("email").stringValue()).isEqualTo(address);
		assertThat(body.get("scope").stringValue()).isEqualTo("TENANT");
		assertThat(body.get("reason").stringValue()).isEqualTo("MANUAL");
		assertThat(body.get("note").stringValue()).isEqualTo("asked");
		assertThat(again.statusCode()).isEqualTo(200);
		assertThat(SendingTestSupport.json(again).get("createdAt")).isEqualTo(body.get("createdAt"));
	}

	@Test
	void ac_18_2_addingNeedsSuppressionsWriteAndAValidAddress() throws Exception {
		assertThat(add(a.product, SendingTestSupport.uniqueAddress()).statusCode()).isEqualTo(403);
		HttpResponse<String> invalid = add(aWriter, "not-an-address");
		assertThat(invalid.statusCode()).isEqualTo(422);
		assertThat(invalid.body()).contains("VALIDATION_ERROR");
	}

	@Test
	void ac_18_1_listsOwnInFullAndGlobalMaskedWithoutTheirOrigin() throws Exception {
		String own = SendingTestSupport.uniqueAddress();
		add(aWriter, own);
		String global = globallyBounced();

		List<JsonNode> items = list(aWriter, "?limit=100");

		assertThat(items).filteredOn(item -> item.has("email") && own.equals(item.get("email").stringValue()))
			.singleElement()
			.satisfies(item -> assertThat(item.get("scope").stringValue()).isEqualTo("TENANT"));
		String masked = "g***" + global.substring(global.indexOf('@'));
		assertThat(items).filteredOn(item -> item.has("emailMasked") && masked.equals(item.get("emailMasked").stringValue()))
			.singleElement()
			.satisfies(item -> {
				assertThat(item.get("scope").stringValue()).isEqualTo("GLOBAL");
				assertThat(item.get("reason").stringValue()).isEqualTo("HARD_BOUNCE");
				assertThat(item.has("email")).isFalse();
			});
		assertThat(items.toString()).doesNotContain(global, "source", "Tenant", a.tenant.id().toString());
	}

	@Test
	void ac_18_1_filtersByReasonAndPagesWithACursor() throws Exception {
		for (int i = 0; i < 3; i++) {
			add(aWriter, SendingTestSupport.uniqueAddress());
		}
		List<JsonNode> manual = list(aWriter, "?reason=MANUAL&limit=100");
		assertThat(manual).hasSize(3).allSatisfy(item -> assertThat(item.get("reason").stringValue()).isEqualTo("MANUAL"));

		HttpResponse<String> first = as(aWriter, http().get("/v1/suppressions?reason=MANUAL&limit=2")).send();
		JsonNode firstPage = SendingTestSupport.json(first);
		assertThat(firstPage.get("data")).hasSize(2);
		String cursor = firstPage.get("nextCursor").stringValue();
		JsonNode secondPage = SendingTestSupport
			.json(as(aWriter, http().get("/v1/suppressions?reason=MANUAL&limit=2&cursor=" + cursor)).send());
		assertThat(secondPage.get("data")).hasSize(1);
		assertThat(secondPage.get("nextCursor").isNull()).isTrue();
		assertThat(secondPage.get("data").get(0).get("email"))
			.isNotIn(firstPage.get("data").get(0).get("email"), firstPage.get("data").get(1).get("email"));

		assertThat(as(aWriter, http().get("/v1/suppressions?cursor=forged")).send().statusCode()).isEqualTo(422);
		assertThat(as(aWriter, http().get("/v1/suppressions?reason=SPAM")).send().statusCode()).isEqualTo(422);
		assertThat(as(aWriter, http().get("/v1/suppressions?limit=500")).send().statusCode()).isEqualTo(422);
	}

	@Test
	void ac_18_3_deletingOwnIsAuditedAndAGlobalNeedsAnAdministrator() throws Exception {
		String own = SendingTestSupport.uniqueAddress();
		add(aWriter, own);

		HttpResponse<String> deleted = as(aWriter, http().delete("/v1/suppressions/" + path(own))).send();
		assertThat(deleted.statusCode()).isEqualTo(204);
		assertThat(AuditRows.forTenant(a.tenant.id())).filteredOn(r -> r.action().equals("SUPPRESSION_DELETED"))
			.singleElement()
			.satisfies(r -> {
				assertThat(r.actorType()).isEqualTo("API_KEY");
				assertThat(r.metadata()).contains("MANUAL").doesNotContain(own);
			});

		String global = globallyBounced();
		HttpResponse<String> refused = as(aWriter, http().delete("/v1/suppressions/" + path(global))).send();
		assertThat(refused.statusCode()).isEqualTo(403);
		assertThat(refused.body()).contains("ADMIN_REQUIRED");
		assertThat(SuppressionEventTest.global(global)).isNotNull();

		assertThat(as(aWriter, http().delete("/v1/suppressions/" + path(SendingTestSupport.uniqueAddress()))).send()
			.statusCode()).isEqualTo(404);
	}

	@Test
	void ac_18_4_aTenantNeitherSeesNorDeletesAnotherTenantsManualSuppression() throws Exception {
		String address = SendingTestSupport.uniqueAddress();
		add(aWriter, address);
		SendingTestSupport b = new SendingTestSupport(http(), "SECURITY");
		AdminApi.Key bWriter = writer(b);

		assertThat(list(bWriter, "?reason=MANUAL&limit=100").toString()).doesNotContain(address);
		assertThat(as(bWriter, http().delete("/v1/suppressions/" + path(address))).send().statusCode()).isEqualTo(404);
		assertThat(list(aWriter, "?reason=MANUAL&limit=100").toString()).contains(address);
		// And it does not block the other tenant's sends either (MANUAL is per tenant).
		HttpResponse<String> sent = b.send(b.bodyTo(address, ""), null);
		assertThat(SendingTestSupport.json(sent).get("status").stringValue()).isEqualTo("QUEUED");
	}

	@Test
	void adr_0012_administratorsListGlobalsWithTheirOriginAndRemoveThem() throws Exception {
		String global = globallyBounced();

		HttpResponse<String> listed = asAdmin(http().get("/admin/v1/suppressions?reason=HARD_BOUNCE&limit=100")).send();
		assertThat(listed.statusCode()).isEqualTo(200);
		assertThat(listed.body()).contains(global);

		HttpResponse<String> deleted = asAdmin(http().delete("/admin/v1/suppressions/" + path(global))).send();
		assertThat(deleted.statusCode()).isEqualTo(204);
		assertThat(SuppressionEventTest.global(global)).isNull();
		HttpResponse<String> sent = a.send(a.bodyTo(global, ""), null);
		assertThat(SendingTestSupport.json(sent).get("status").stringValue()).isEqualTo("QUEUED");

		assertThat(asAdmin(http().delete("/admin/v1/suppressions/" + path(global))).send().statusCode()).isEqualTo(404);
		HttpResponse<String> tenantKey = as(aWriter, http().get("/admin/v1/suppressions")).send();
		assertThat(tenantKey.statusCode()).as("a tenant key is not an admin credential").isEqualTo(403);
		assertThat(tenantKey.body()).contains("ADMIN_REQUIRED");
	}

}
