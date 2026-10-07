package com.emailservice.support;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Creates tenants and keys through the real admin API, as an operator would. */
public final class AdminApi {

	private static final Pattern API_KEY = Pattern.compile("\"apiKey\":\"(esk_[a-z]+_[0-9A-Za-z]{8}_[0-9A-Za-z]{43})\"");

	private static final Pattern ID = Pattern.compile("\"id\":\"([0-9a-f-]{36})\"");

	private final TestHttp http;

	public AdminApi(TestHttp http) {
		this.http = http;
	}

	public record Tenant(String slug, UUID id) {
	}

	public record Key(UUID id, String token) {

		public String bearer() {
			return "Bearer " + token;
		}

	}

	public Tenant createTenant() throws Exception {
		String slug = "a-" + UUID.randomUUID().toString().substring(0, 8);
		HttpResponse<String> response = admin(http.post("/admin/v1/tenants")).json("""
				{"slug": "%s", "name": "Auth", "fromEmail": "no-reply@example.test", "fromName": "Auth"}
				""".formatted(slug)).send();
		assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
		return new Tenant(slug, UUID.fromString(group(ID, response.body())));
	}

	public Key issueKey(Tenant tenant, String body) throws Exception {
		HttpResponse<String> response = admin(http.post("/admin/v1/tenants/" + tenant.slug() + "/api-keys")).json(body)
			.send();
		assertThat(response.statusCode()).as(response.body()).isEqualTo(201);
		return new Key(UUID.fromString(group(ID, response.body())), group(API_KEY, response.body()));
	}

	public void patchTenant(Tenant tenant, String body) throws Exception {
		assertThat(admin(http.patch("/admin/v1/tenants/" + tenant.slug())).json(body).send().statusCode()).isEqualTo(200);
	}

	public void revoke(Key key) throws Exception {
		assertThat(admin(http.delete("/admin/v1/api-keys/" + key.id())).send().statusCode()).isEqualTo(204);
	}

	private static TestHttp.Request admin(TestHttp.Request request) {
		return request.header("X-Admin-Key", IntegrationTestEnvironment.ADMIN_API_KEY);
	}

	private static String group(Pattern pattern, String body) {
		Matcher matcher = pattern.matcher(body);
		assertThat(matcher.find()).as("%s in %s", pattern, body).isTrue();
		return matcher.group(1);
	}

}
