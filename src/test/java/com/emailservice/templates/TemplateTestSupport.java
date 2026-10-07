package com.emailservice.templates;

import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.Map;

import com.emailservice.support.AdminApi;
import com.emailservice.support.TestHttp;

import tools.jackson.databind.json.JsonMapper;

/** A tenant with an operator key (templates:write) and a product key, plus template request helpers. */
final class TemplateTestSupport {

	static final JsonMapper JSON = JsonMapper.builder().build();

	static final String SCHEMA = """
			{"required": ["firstName", "resetUrl"],
			 "properties": {"firstName": {"type": "string", "maxLength": 80},
			                "resetUrl": {"type": "string", "format": "uri", "x-sensitive": true},
			                "expiresInMinutes": {"type": "integer"}}}
			""";

	static final String HTML = "<p>Hola {{firstName}}</p><p><a href=\"{{resetUrl}}\">Restablecer</a></p>";

	final TestHttp http;

	final AdminApi.Tenant tenant;

	final AdminApi.Key operator;

	final AdminApi.Key product;

	TemplateTestSupport(TestHttp http) throws Exception {
		this.http = http;
		AdminApi admin = new AdminApi(http);
		this.tenant = admin.createTenant();
		this.operator = admin.issueKey(tenant,
				"{\"name\": \"operator\", \"scopes\": [\"templates:write\", \"emails:read\"]}");
		this.product = admin.issueKey(tenant, "{\"name\": \"product\"}");
	}

	HttpResponse<String> createTemplate(String key, String category) throws Exception {
		return as(operator, http.post("/v1/templates")).json(json(Map.of("key", key, "name", "Name of " + key,
				"category", category))).send();
	}

	HttpResponse<String> createVersion(String key, String locale, String subject, String html, String schema)
			throws Exception {
		return as(operator, http.post("/v1/templates/" + key + "/versions")).json(versionBody(locale, subject, html, schema))
			.send();
	}

	static String versionBody(String locale, String subject, String html, String schema) {
		Map<String, Object> body = new LinkedHashMap<>();
		body.put("locale", locale);
		body.put("subjectTemplate", subject);
		body.put("htmlTemplate", html);
		body.put("textTemplate", "Hola {{firstName}}: {{resetUrl}}");
		body.put("variablesSchema", schema == null ? null : JSON.readTree(schema));
		return json(body);
	}

	static TestHttp.Request as(AdminApi.Key key, TestHttp.Request request) {
		return request.header("Authorization", key.bearer());
	}

	static String json(Object value) {
		return JSON.writeValueAsString(value);
	}

}
