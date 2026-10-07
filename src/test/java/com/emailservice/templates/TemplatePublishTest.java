package com.emailservice.templates;

import static com.emailservice.templates.TemplateTestSupport.HTML;
import static com.emailservice.templates.TemplateTestSupport.SCHEMA;
import static com.emailservice.templates.TemplateTestSupport.as;
import static com.emailservice.templates.TemplateTestSupport.versionBody;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.emailservice.support.AuditRows;
import com.emailservice.support.IntegrationTest;

/** FR-05 (publication and immutability), AC-04.8 and AC-37.1/37.6. */
class TemplatePublishTest extends IntegrationTest {

	TemplateTestSupport a;

	String key;

	@BeforeEach
	void setUp() throws Exception {
		a = new TemplateTestSupport(http());
		key = "p-" + UUID.randomUUID().toString().substring(0, 8);
		assertThat(a.createTemplate(key, "SECURITY").statusCode()).isEqualTo(201);
	}

	HttpResponse<String> publish(int version) throws Exception {
		return publish(version, null);
	}

	HttpResponse<String> publish(int version, String requestId) throws Exception {
		var request = as(a.operator, http().post("/v1/templates/" + key + "/versions/" + version + "/publish"));
		return (requestId == null ? request : request.header("X-Request-Id", requestId)).send();
	}

	String detail() throws Exception {
		return as(a.operator, http().get("/v1/templates/" + key)).send().body();
	}

	@Test
	void ac_05_1_publishedVersionIsImmutableAndPublicationIsAudited() throws Exception {
		a.createVersion(key, "es-CR", "Hola {{firstName}}", HTML, SCHEMA);
		String requestId = "test-" + UUID.randomUUID();

		HttpResponse<String> published = publish(1, requestId);
		assertThat(published.statusCode()).as(published.body()).isEqualTo(200);
		assertThat(published.body()).contains("\"status\":\"PUBLISHED\"").doesNotContain("\"publishedAt\":null");

		for (HttpResponse<String> attempt : List.of(
				as(a.operator, http().put("/v1/templates/" + key + "/versions/1")).json(versionBody("es-CR", "x", HTML, SCHEMA)).send(),
				as(a.operator, http().delete("/v1/templates/" + key + "/versions/1")).send(), publish(1))) {
			assertThat(attempt.statusCode()).isEqualTo(409);
			assertThat(attempt.body()).contains("\"code\":\"VERSION_IMMUTABLE\"");
		}

		List<AuditRows.Row> audit = AuditRows.forRequest(requestId);
		assertThat(audit).extracting(AuditRows.Row::action).containsExactly("TEMPLATE_PUBLISHED");
		assertThat(audit.getFirst().actorId()).isEqualTo(a.operator.id().toString());
		assertThat(audit.getFirst().metadata()).contains(key).contains("es-CR");
	}

	@Test
	void ac_05_2_and_37_1_publishingArchivesOnlyThePreviousVersionOfTheSameLocale() throws Exception {
		a.createVersion(key, "es-CR", "v1 {{firstName}}", HTML, SCHEMA);
		a.createVersion(key, "en", "v2 {{firstName}}", HTML, SCHEMA);
		a.createVersion(key, "es-CR", "v3 {{firstName}}", HTML, SCHEMA);
		publish(1);
		publish(2);

		assertThat(publish(3).statusCode()).isEqualTo(200);

		String detail = detail();
		assertThat(detail).containsPattern("\"version\":1,\"locale\":\"es-CR\",\"status\":\"ARCHIVED\"")
			.containsPattern("\"version\":2,\"locale\":\"en\",\"status\":\"PUBLISHED\"")
			.containsPattern("\"version\":3,\"locale\":\"es-CR\",\"status\":\"PUBLISHED\"");
		assertThat(as(a.operator, http().get("/v1/templates")).send().body())
			.contains("\"published\":[{\"version\":2,\"locale\":\"en\"},{\"version\":3,\"locale\":\"es-CR\"}]");
	}

	@Test
	void ac_05_5_publishingRequiresASchema() throws Exception {
		a.createVersion(key, "es-CR", "x", "<p>Hola {{firstName}}</p>", null);
		HttpResponse<String> response = publish(1);
		assertThat(response.statusCode()).isEqualTo(422);
		assertThat(response.body()).contains("\"code\":\"VALIDATION_ERROR\"").contains("\"field\":\"variablesSchema\"");
		assertThat(detail()).contains("\"status\":\"DRAFT\"");
	}

	@Test
	void ac_04_8_urlVariablesMustBeUrisToPublish() throws Exception {
		a.createVersion(key, "es-CR", "x", "<a href=\"{{firstName}}\">x</a>", SCHEMA);
		HttpResponse<String> response = publish(1);
		assertThat(response.statusCode()).isEqualTo(422);
		assertThat(response.body()).contains("\"code\":\"UNSAFE_TEMPLATE_CONSTRUCT\"").contains("firstName");
	}

	@Test
	void ac_37_6_allLocalesMustRequireTheSameVariables() throws Exception {
		a.createVersion(key, "es-CR", "x", HTML, SCHEMA);
		publish(1);
		a.createVersion(key, "en", "x", HTML, """
				{"required": ["firstName"], "properties": {"firstName": {"type": "string"},
				 "resetUrl": {"type": "string", "format": "uri"}}}
				""");

		HttpResponse<String> response = publish(2);
		assertThat(response.statusCode()).isEqualTo(422);
		assertThat(response.body()).contains("\"field\":\"variablesSchema.required\"").contains("es-CR");
	}

	@Test
	void anotherTenantCannotPublish() throws Exception {
		a.createVersion(key, "es-CR", "x", HTML, SCHEMA);
		TemplateTestSupport b = new TemplateTestSupport(http());
		HttpResponse<String> response = as(b.operator,
				http().post("/v1/templates/" + key + "/versions/1/publish")).send();
		assertThat(response.statusCode()).isEqualTo(404);
		assertThat(detail()).contains("\"status\":\"DRAFT\"");
	}

}
