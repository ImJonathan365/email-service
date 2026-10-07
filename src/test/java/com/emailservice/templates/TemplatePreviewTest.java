package com.emailservice.templates;

import static com.emailservice.templates.TemplateTestSupport.HTML;
import static com.emailservice.templates.TemplateTestSupport.SCHEMA;
import static com.emailservice.templates.TemplateTestSupport.as;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.emailservice.support.AdminApi;
import com.emailservice.support.IntegrationTest;
import com.emailservice.support.PostgresTestDatabase;
import com.emailservice.support.PostgresTestDatabase.Role;

/** FR-06 (preview), AC-04.10 (clipping warning) and the AC-07.8 URL rule applied in preview. */
class TemplatePreviewTest extends IntegrationTest {

	static final String VARIABLES = """
			{"firstName": "Ana & Juan", "resetUrl": "https://app.example.test/reset?t=abc", "expiresInMinutes": 30}
			""";

	TemplateTestSupport a;

	String key;

	@BeforeEach
	void setUp() throws Exception {
		a = new TemplateTestSupport(http());
		new AdminApi(http()).patchTenant(a.tenant, "{\"allowedLinkHosts\": [\"example.test\"]}");
		key = "v-" + UUID.randomUUID().toString().substring(0, 8);
		a.createTemplate(key, "SECURITY");
	}

	HttpResponse<String> preview(String body) throws Exception {
		return as(a.operator, http().post("/v1/templates/" + key + "/preview")).json(body).send();
	}

	@Test
	void ac_06_1_rendersADraftWithoutCreatingAnyMessage() throws Exception {
		a.createVersion(key, "es-CR", "Hola {{firstName}}", HTML, SCHEMA);

		HttpResponse<String> response = preview("{\"templateVersion\": 1, \"variables\": " + VARIABLES + "}");

		assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
		assertThat(response.body()).contains("\"subject\":\"Hola Ana & Juan\"")
			.contains("Hola Ana &amp; Juan")
			.contains("\"text\":\"Hola Ana & Juan: https://app.example.test/reset?t=abc\"")
			.contains("\"status\":\"DRAFT\"")
			.contains("\"warnings\":[]");
		try (Connection system = PostgresTestDatabase.connect(Role.SYSTEM);
				PreparedStatement count = system.prepareStatement("SELECT count(*) FROM email_message WHERE tenant_id = ?")) {
			count.setObject(1, a.tenant.id());
			try (ResultSet rs = count.executeQuery()) {
				rs.next();
				assertThat(rs.getLong(1)).isZero();
			}
		}
	}

	@Test
	void ac_06_2_missingVariablesAreListed() throws Exception {
		a.createVersion(key, "es-CR", "Hola {{firstName}}", HTML, SCHEMA);
		HttpResponse<String> response = preview("{\"templateVersion\": 1, \"variables\": {\"expiresInMinutes\": \"30\"}}");
		assertThat(response.statusCode()).isEqualTo(422);
		assertThat(response.body()).contains("\"code\":\"TEMPLATE_VARIABLES_INVALID\"")
			.contains("\"field\":\"variables.firstName\"")
			.contains("\"field\":\"variables.resetUrl\"")
			.contains("\"field\":\"variables.expiresInMinutes\"");
	}

	@Test
	void ac_07_8_urlVariablesMustBeHttpsOnAllowedHosts() throws Exception {
		a.createVersion(key, "es-CR", "Hola {{firstName}}", HTML, SCHEMA);
		for (String url : new String[] { "http://app.example.test/reset", "https://phishing.test/reset" }) {
			HttpResponse<String> response = preview("""
					{"templateVersion": 1, "variables": {"firstName": "Ana", "resetUrl": "%s"}}
					""".formatted(url));
			assertThat(response.statusCode()).isEqualTo(422);
			assertThat(response.body()).contains("\"code\":\"UNSAFE_URL\"").contains("\"field\":\"variables.resetUrl\"");
		}
	}

	@Test
	void ac_04_10_largeRenderedHtmlCarriesAClippingWarning() throws Exception {
		a.createVersion(key, "es-CR", "x", "<p>{{firstName}}</p><p>" + "x".repeat(102_400) + "</p>", SCHEMA);
		HttpResponse<String> response = preview("{\"templateVersion\": 1, \"variables\": " + VARIABLES + "}");
		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.body()).contains("\"warnings\":[\"HTML_MAY_BE_CLIPPED\"]");
	}

	@Test
	void withoutAVersionThePublishedOneOfTheLocaleIsUsedWithTenantFallback() throws Exception {
		assertThat(preview("{\"variables\": " + VARIABLES + "}").body()).contains("\"code\":\"TEMPLATE_NOT_PUBLISHED\"");

		a.createVersion(key, "es-CR", "Hola {{firstName}}", HTML, SCHEMA);
		as(a.operator, http().post("/v1/templates/" + key + "/versions/1/publish")).send();

		HttpResponse<String> fallback = preview("{\"locale\": \"en\", \"variables\": " + VARIABLES + "}");
		assertThat(fallback.body()).contains("\"locale\":\"es-CR\"").contains("\"subject\":\"Hola Ana & Juan\"");

		a.createVersion(key, "en", "Hi {{firstName}}", HTML, SCHEMA);
		as(a.operator, http().post("/v1/templates/" + key + "/versions/2/publish")).send();
		HttpResponse<String> english = preview("{\"locale\": \"en\", \"variables\": " + VARIABLES + "}");
		assertThat(english.body()).contains("\"locale\":\"en\"").contains("\"subject\":\"Hi Ana & Juan\"");
	}

	@Test
	void variablesThatBreakTheRenderAreReported() throws Exception {
		a.createVersion(key, "es-CR", "x", "<p>{{formatMoney firstName \"CRC\"}}</p>", SCHEMA);
		HttpResponse<String> response = preview("{\"templateVersion\": 1, \"variables\": " + VARIABLES + "}");
		assertThat(response.statusCode()).isEqualTo(422);
		assertThat(response.body()).contains("\"code\":\"TEMPLATE_VARIABLES_INVALID\"")
			.contains("formatMoney needs a number")
			.doesNotContain("Ana & Juan");
	}

	@Test
	void previewNeedsTemplatesWriteAndStaysWithinTheTenant() throws Exception {
		a.createVersion(key, "es-CR", "x", HTML, SCHEMA);
		assertThat(as(a.product, http().post("/v1/templates/" + key + "/preview")).json("{}").send().statusCode())
			.isEqualTo(403);
		TemplateTestSupport b = new TemplateTestSupport(http());
		assertThat(as(b.operator, http().post("/v1/templates/" + key + "/preview")).json("{\"templateVersion\": 1}")
			.send()
			.statusCode()).isEqualTo(404);
	}

}
