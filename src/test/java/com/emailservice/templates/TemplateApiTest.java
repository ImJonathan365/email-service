package com.emailservice.templates;

import static com.emailservice.templates.TemplateTestSupport.HTML;
import static com.emailservice.templates.TemplateTestSupport.SCHEMA;
import static com.emailservice.templates.TemplateTestSupport.as;
import static com.emailservice.templates.TemplateTestSupport.versionBody;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.emailservice.support.IntegrationTest;

/** FR-04 (templates and drafts), AC-36.1/36.4 and AC-37.1 through the /v1 API. */
class TemplateApiTest extends IntegrationTest {

	TemplateTestSupport a;

	@BeforeEach
	void setUp() throws Exception {
		a = new TemplateTestSupport(http());
	}

	static String key() {
		return "t-" + UUID.randomUUID().toString().substring(0, 8);
	}

	@Test
	void ac_04_1_createsATemplateAndRejectsADuplicateKey() throws Exception {
		String key = key();
		HttpResponse<String> created = a.createTemplate(key, "SECURITY");
		assertThat(created.statusCode()).as(created.body()).isEqualTo(201);
		assertThat(created.body()).contains("\"key\":\"" + key + "\"")
			.contains("\"category\":\"SECURITY\"")
			.contains("\"trackingEnabled\":false")
			.contains("\"status\":\"ACTIVE\"");

		HttpResponse<String> duplicate = a.createTemplate(key, "NOTICE");
		assertThat(duplicate.statusCode()).isEqualTo(409);
		assertThat(duplicate.body()).contains("\"code\":\"TEMPLATE_KEY_TAKEN\"");

		HttpResponse<String> badKey = a.createTemplate("Not Kebab", "SECURITY");
		assertThat(badKey.statusCode()).isEqualTo(422);
		assertThat(badKey.body()).contains("\"field\":\"key\"");
	}

	@Test
	void ac_36_1_categoryIsRequiredAndClosed() throws Exception {
		assertThat(a.createTemplate(key(), "MARKETING").statusCode()).isEqualTo(422);
	}

	@Test
	void ac_04_11_trackingOnlyForNotice() throws Exception {
		HttpResponse<String> security = as(a.operator, http().post("/v1/templates"))
			.json("{\"key\": \"%s\", \"name\": \"x\", \"category\": \"SECURITY\", \"trackingEnabled\": true}"
				.formatted(key()))
			.send();
		assertThat(security.statusCode()).isEqualTo(422);
		assertThat(security.body()).contains("\"field\":\"trackingEnabled\"");

		HttpResponse<String> notice = as(a.operator, http().post("/v1/templates"))
			.json("{\"key\": \"%s\", \"name\": \"x\", \"category\": \"NOTICE\", \"trackingEnabled\": true}"
				.formatted(key()))
			.send();
		assertThat(notice.statusCode()).isEqualTo(201);
		assertThat(notice.body()).contains("\"trackingEnabled\":true");
	}

	@Test
	void ac_04_2_draftsAreNumberedFromOneAndSchemaIsOptional() throws Exception {
		String key = key();
		a.createTemplate(key, "SECURITY");

		HttpResponse<String> first = a.createVersion(key, "es-CR", "Hola {{firstName}}", HTML, null);
		assertThat(first.statusCode()).as(first.body()).isEqualTo(201);
		assertThat(first.body()).contains("\"version\":1").contains("\"status\":\"DRAFT\"").contains("\"locale\":\"es-CR\"");

		HttpResponse<String> second = a.createVersion(key, "en", "Hi {{firstName}}", HTML, SCHEMA);
		assertThat(second.body()).contains("\"version\":2").contains("\"locale\":\"en\"");

		HttpResponse<String> detail = as(a.operator, http().get("/v1/templates/" + key)).send();
		assertThat(detail.statusCode()).isEqualTo(200);
		assertThat(detail.body()).contains("\"subjectTemplate\":\"Hi {{firstName}}\"")
			.contains("\"variablesSchema\":null")
			.contains("\"format\":\"uri\"");
	}

	@Test
	void ac_04_3_draftsCanBeReplacedAndDeleted() throws Exception {
		String key = key();
		a.createTemplate(key, "TRANSACTIONAL");
		a.createVersion(key, "es-CR", "v1", HTML, null);

		HttpResponse<String> replaced = as(a.operator, http().put("/v1/templates/" + key + "/versions/1"))
			.json(versionBody("es-CR", "edited", HTML, SCHEMA))
			.send();
		assertThat(replaced.statusCode()).as(replaced.body()).isEqualTo(200);
		assertThat(as(a.operator, http().get("/v1/templates/" + key)).send().body()).contains("\"subjectTemplate\":\"edited\"");

		assertThat(as(a.operator, http().delete("/v1/templates/" + key + "/versions/1")).send().statusCode())
			.isEqualTo(204);
		assertThat(as(a.operator, http().delete("/v1/templates/" + key + "/versions/1")).send().statusCode())
			.isEqualTo(404);
	}

	@Test
	void ac_04_4_and_04_5_unsafeOrInvalidTemplatesAreRejected() throws Exception {
		String key = key();
		a.createTemplate(key, "SECURITY");

		HttpResponse<String> raw = a.createVersion(key, "es-CR", "x", "<p>{{{firstName}}}</p>", null);
		assertThat(raw.statusCode()).isEqualTo(422);
		assertThat(raw.body()).contains("\"code\":\"UNSAFE_TEMPLATE_CONSTRUCT\"").contains("\"field\":\"htmlTemplate\"");

		HttpResponse<String> syntax = a.createVersion(key, "es-CR", "x", "<p>\n{{#if vip}}never closed</p>", null);
		assertThat(syntax.statusCode()).isEqualTo(422);
		assertThat(syntax.body()).contains("\"code\":\"TEMPLATE_SYNTAX_ERROR\"").contains("line ");
	}

	@Test
	void ac_04_7_htmlLinterRunsOnSave() throws Exception {
		String key = key();
		a.createTemplate(key, "SECURITY");
		HttpResponse<String> response = a.createVersion(key, "es-CR", "x", "<p style=\"color:{{c}}\">x</p>", null);
		assertThat(response.statusCode()).isEqualTo(422);
		assertThat(response.body()).contains("\"code\":\"UNSAFE_TEMPLATE_CONSTRUCT\"");
	}

	@Test
	void ac_04_10_htmlSourceIsLimitedTo256Kb() throws Exception {
		String key = key();
		a.createTemplate(key, "NOTICE");
		HttpResponse<String> response = a.createVersion(key, "es-CR", "x", "<p>" + "a".repeat(262_144) + "</p>", null);
		assertThat(response.statusCode()).isEqualTo(422);
		assertThat(response.body()).contains("\"code\":\"VALIDATION_ERROR\"").contains("\"field\":\"htmlTemplate\"");
	}

	@Test
	void ac_37_1_localeMustBeSupportedAndSchemaMustBeTheSubset() throws Exception {
		String key = key();
		a.createTemplate(key, "SECURITY");
		HttpResponse<String> response = a.createVersion(key, "fr", "x", HTML,
				"{\"properties\": {\"firstName\": {\"type\": \"string\", \"pattern\": \".*\"}}}");
		assertThat(response.statusCode()).isEqualTo(422);
		assertThat(response.body()).contains("\"field\":\"locale\"")
			.contains("\"field\":\"variablesSchema.properties.firstName.pattern\"");
	}

	@Test
	void ac_36_4_categoryCannotBeEditedInPlace() throws Exception {
		String key = key();
		a.createTemplate(key, "SECURITY");
		HttpResponse<String> response = as(a.operator, http().patch("/v1/templates/" + key))
			.json("{\"category\": \"NOTICE\"}")
			.send();
		assertThat(response.statusCode()).isEqualTo(405);
	}

	@Test
	void productKeyCannotManageTemplatesButCanReadThem() throws Exception {
		String key = key();
		a.createTemplate(key, "SECURITY");
		HttpResponse<String> create = as(a.product, http().post("/v1/templates"))
			.json("{\"key\": \"%s\", \"name\": \"x\", \"category\": \"SECURITY\"}".formatted(key()))
			.send();
		assertThat(create.statusCode()).isEqualTo(403);
		assertThat(create.body()).contains("\"code\":\"INSUFFICIENT_SCOPE\"");
		assertThat(as(a.product, http().get("/v1/templates/" + key)).send().statusCode()).isEqualTo(200);
	}

	@Test
	void ac_04_6_anotherTenantCannotSeeOrTouchTheTemplate() throws Exception {
		String key = key();
		a.createTemplate(key, "SECURITY");
		a.createVersion(key, "es-CR", "x", HTML, null);
		TemplateTestSupport b = new TemplateTestSupport(http());

		assertThat(as(b.operator, http().get("/v1/templates")).send().body()).doesNotContain(key);
		for (HttpResponse<String> response : new HttpResponse[] {
				as(b.operator, http().get("/v1/templates/" + key)).send(),
				b.createVersion(key, "es-CR", "x", HTML, null),
				as(b.operator, http().put("/v1/templates/" + key + "/versions/1")).json(versionBody("es-CR", "x", HTML, null)).send(),
				as(b.operator, http().delete("/v1/templates/" + key + "/versions/1")).send() }) {
			assertThat(response.statusCode()).isEqualTo(404);
			assertThat(response.body()).contains("\"code\":\"TEMPLATE_NOT_FOUND\"");
		}

		// B may reuse the same key: keys are unique per tenant.
		assertThat(b.createTemplate(key, "NOTICE").statusCode()).isEqualTo(201);
		assertThat(as(a.operator, http().get("/v1/templates/" + key)).send().body()).contains("\"category\":\"SECURITY\"");
	}

}
