package com.emailservice.sending;

import static com.emailservice.sending.SendingTestSupport.json;
import static com.emailservice.sending.SendingTestSupport.row;
import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.util.LinkedHashMap;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.emailservice.support.IntegrationTest;
import com.emailservice.support.PostgresTestDatabase;
import com.emailservice.support.PostgresTestDatabase.Role;

import tools.jackson.databind.JsonNode;

/** FR-07 (acceptance), FR-09 (addresses), FR-37 (locale) and AC-36.2 (priority) on POST /v1/emails. */
class EmailAcceptanceTest extends IntegrationTest {

	SendingTestSupport a;

	@BeforeEach
	void setUp() throws Exception {
		a = new SendingTestSupport(http(), "SECURITY");
	}

	@Test
	void ac_07_1_acceptsQueuesAndPinsTheVersion() throws Exception {
		HttpResponse<String> response = a.send(a.body("\"tags\": [\"password-reset\"], \"metadata\": {\"userId\": \"u_1\"}"),
				"reset:" + UUID.randomUUID());

		assertThat(response.statusCode()).as(response.body()).isEqualTo(202);
		JsonNode body = json(response);
		assertThat(body.get("status").stringValue()).isEqualTo("QUEUED");
		assertThat(body.get("templateKey").stringValue()).isEqualTo(a.templateKey);
		assertThat(body.get("locale").stringValue()).isEqualTo("es-CR");
		assertThat(body.get("to").stringValue()).isEqualTo("ana@example.test");
		assertThat(body.get("droppedRecipients").size()).isZero();
		UUID id = UUID.fromString(body.get("id").stringValue());
		assertThat(id.version()).isEqualTo(7);

		JsonNode row = row(id);
		assertThat(row.get("status").stringValue()).isEqualTo("QUEUED");
		assertThat(row.get("priority").intValue()).isZero();
		assertThat(row.get("category").stringValue()).isEqualTo("SECURITY");
		assertThat(row.get("template_version_id").stringValue()).isNotBlank();
		assertThat(row.get("attempts").intValue()).isZero();
		assertThat(row.get("from_email").stringValue()).isEqualTo("no-reply@sender.test");
		assertThat(row.get("reply_to").stringValue()).isEqualTo("support@sender.test");
		assertThat(row.get("variables").get("firstName").stringValue()).isEqualTo("Ana");
		assertThat(row.get("provider").isNull()).isTrue();
	}

	@Test
	void ac_36_2_priorityFollowsTheCategory() throws Exception {
		SendingTestSupport notice = new SendingTestSupport(http(), "NOTICE");
		HttpResponse<String> response = notice.send(notice.body(""), null);
		assertThat(row(UUID.fromString(json(response).get("id").stringValue())).get("priority").intValue()).isEqualTo(2);
	}

	@Test
	void ac_07_2_missingOrMalformedFieldsCreateNothing() throws Exception {
		HttpResponse<String> response = a.send("{\"templateKey\": \"" + a.templateKey + "\"}", null);
		assertThat(response.statusCode()).isEqualTo(422);
		assertThat(response.body()).contains("\"code\":\"VALIDATION_ERROR\"")
			.contains("\"field\":\"to\"")
			.contains("\"field\":\"variables\"");
		assertThat(SendingTestSupport.countMessages(a.tenant.id())).isZero();
	}

	@Test
	void ac_07_3_unknownTemplateIsNotFound() throws Exception {
		HttpResponse<String> response = a.send(a.body("").replace(a.templateKey, "missing-template"), null);
		assertThat(response.statusCode()).isEqualTo(404);
		assertThat(response.body()).contains("\"code\":\"TEMPLATE_NOT_FOUND\"");
	}

	@Test
	void ac_04_6_anotherTenantsTemplateIsNotFound() throws Exception {
		SendingTestSupport b = new SendingTestSupport(http(), "SECURITY");
		HttpResponse<String> response = b.send(a.body(""), null);
		assertThat(response.statusCode()).isEqualTo(404);
		assertThat(SendingTestSupport.countMessages(b.tenant.id())).isZero();
	}

	@Test
	void ac_07_4_variablesMustMatchTheSchema() throws Exception {
		HttpResponse<String> response = a.send(a.body("").replace("\"firstName\": \"Ana\", ", ""), null);
		assertThat(response.statusCode()).isEqualTo(422);
		assertThat(response.body()).contains("\"code\":\"TEMPLATE_VARIABLES_INVALID\"")
			.contains("\"field\":\"variables.firstName\"");
	}

	@Test
	void ac_07_6_attachmentsAndSendAtAreRejectedWithExplicitReasons() throws Exception {
		HttpResponse<String> response = a.send(
				a.body("\"attachments\": [{\"filename\": \"x.pdf\"}], \"sendAt\": \"2030-01-01T00:00:00Z\""), null);
		assertThat(response.statusCode()).isEqualTo(422);
		assertThat(response.body()).contains("\"field\":\"attachments\"")
			.contains("\"field\":\"sendAt\"")
			.contains("is not supported yet: scheduled sending (FR-31) arrives in milestone H8");
		assertThat(SendingTestSupport.countMessages(a.tenant.id())).isZero();
	}

	@Test
	void ac_07_6_oversizedBodiesAreRejectedBeforeReading() throws Exception {
		String big = a.body("\"metadata\": {\"blob\": \"" + "x".repeat(270_000) + "\"}");
		HttpResponse<String> response = a.send(big, null);
		assertThat(response.statusCode()).isEqualTo(413);
		assertThat(response.body()).contains("\"code\":\"PAYLOAD_TOO_LARGE\"");
	}

	@Test
	void ac_07_7_atMostFiveCcAndBcc() throws Exception {
		String six = "[\"a@example.test\", \"b@example.test\", \"c@example.test\", \"d@example.test\", "
				+ "\"e@example.test\", \"f@example.test\"]";
		HttpResponse<String> response = a.send(a.body("\"cc\": " + six), null);
		assertThat(response.statusCode()).isEqualTo(422);
		assertThat(response.body()).contains("\"field\":\"cc\"");
	}

	@Test
	void ac_07_8_urlVariablesMustBeHttpsOnAllowedHosts() throws Exception {
		HttpResponse<String> response = a.send(a.body("").replace("https://app.example.test", "https://phishing.test"),
				null);
		assertThat(response.statusCode()).isEqualTo(422);
		assertThat(response.body()).contains("\"code\":\"UNSAFE_URL\"").contains("\"field\":\"variables.resetUrl\"");
	}

	@Test
	void ac_07_5_aVariableThatBreaksTheRenderIsRejected() throws Exception {
		String key = "money-" + UUID.randomUUID().toString().substring(0, 8);
		a.createTemplate(key, "TRANSACTIONAL");
		var body = new LinkedHashMap<String, Object>();
		body.put("locale", "es-CR");
		body.put("subjectTemplate", "Pago");
		body.put("htmlTemplate", "<p>{{formatMoney label \"CRC\"}}</p>");
		body.put("variablesSchema", SendingTestSupport.JSON.readTree("{\"properties\": {\"label\": {\"type\": \"string\"}}}"));
		a.operator(http().post("/v1/templates/" + key + "/versions")).json(SendingTestSupport.JSON.writeValueAsString(body)).send();
		a.operator(http().post("/v1/templates/" + key + "/versions/1/publish")).send();

		HttpResponse<String> response = a.send("{\"templateKey\": \"" + key
				+ "\", \"to\": {\"email\": \"ana@example.test\"}, \"variables\": {\"label\": \"not a number\"}}", null);
		assertThat(response.statusCode()).isEqualTo(422);
		assertThat(response.body()).contains("\"code\":\"TEMPLATE_VARIABLES_INVALID\"").contains("formatMoney");
	}

	@Test
	void ac_07_9_pausedTenantCannotSend() throws Exception {
		try (var system = PostgresTestDatabase.connect(Role.SYSTEM);
				var statement = system.prepareStatement(
						"UPDATE tenant SET sending_paused_at = now(), pause_reason = 'MANUAL' WHERE id = ?")) {
			statement.setObject(1, a.tenant.id());
			statement.executeUpdate();
		}
		HttpResponse<String> response = a.send(a.body(""), null);
		assertThat(response.statusCode()).isEqualTo(403);
		assertThat(response.body()).contains("\"code\":\"TENANT_SENDING_PAUSED\"");
		assertThat(SendingTestSupport.countMessages(a.tenant.id())).isZero();
	}

	@ParameterizedTest
	@CsvSource(delimiter = '|', value = { "\"to\": {\"email\": \"ana@example.test\\r\\nBcc: x@example.test\"}|to.email",
			"\"fromName\": \"Ana\\nBcc: x@example.test\"|fromName", "\"cc\": [\"a@example.test\\r\\n\"]|cc[0]",
			"\"tags\": [\"x\\ny\"]|tags[0]" })
	void ac_09_3_lineBreaksInHeaderFieldsAreInjection(String field, String path) throws Exception {
		String body = field.startsWith("\"to\"") ? a.body("").replace("\"to\": {\"email\": \"ana@example.test\", \"name\": \"Ana\"}", field)
				: a.body(field);
		HttpResponse<String> response = a.send(body, null);
		assertThat(response.statusCode()).as(response.body()).isEqualTo(422);
		assertThat(response.body()).contains("\"code\":\"HEADER_INJECTION_DETECTED\"").contains("\"field\":\"" + path + "\"");
	}

	@ParameterizedTest
	@CsvSource({ "not-an-email", "\"ana\"@example.test", "ana(comment)@example.test", "ana@localhost", "ana@@example.test",
			"ana@-bad-.test" })
	void ac_09_1_invalidAddressesAreRejected(String address) throws Exception {
		HttpResponse<String> response = a.send(a.body("").replace("ana@example.test", address.replace("\"", "\\\"")), null);
		assertThat(response.statusCode()).isEqualTo(422);
		assertThat(response.body()).contains("\"code\":\"INVALID_EMAIL_ADDRESS\"").contains("\"field\":\"to.email\"");
	}

	@Test
	void ac_09_2_aCustomFromMustBeOnAnAllowedDomain() throws Exception {
		HttpResponse<String> denied = a.send(a.body("\"from\": \"ceo@other.test\""), null);
		assertThat(denied.statusCode()).isEqualTo(403);
		assertThat(denied.body()).contains("\"code\":\"FROM_DOMAIN_NOT_ALLOWED\"");

		HttpResponse<String> allowed = a.send(a.body("\"from\": \"alerts@mail.sender.test\""), null);
		assertThat(allowed.statusCode()).isEqualTo(202);
		assertThat(row(UUID.fromString(json(allowed).get("id").stringValue())).get("from_email").stringValue())
			.isEqualTo("alerts@mail.sender.test");
	}

	@Test
	void ac_09_5_fromNameOverridesOnlyTheDisplayName() throws Exception {
		HttpResponse<String> ok = a.send(a.body("\"fromName\": \"Pulpería La Esquina vía Colmena\""), null);
		JsonNode row = row(UUID.fromString(json(ok).get("id").stringValue()));
		assertThat(row.get("from_name").stringValue()).isEqualTo("Pulpería La Esquina vía Colmena");
		assertThat(row.get("from_email").stringValue()).isEqualTo("no-reply@sender.test");

		for (String bad : new String[] { "Evil <evil@x.test>", "x".repeat(81), "a@b" }) {
			HttpResponse<String> response = a.send(a.body("\"fromName\": \"" + bad + "\""), null);
			assertThat(response.statusCode()).as(bad).isEqualTo(422);
			assertThat(response.body()).contains("\"field\":\"fromName\"");
		}
	}

	@Test
	void ac_37_2_requestedLocaleIsUsedWhenPublished() throws Exception {
		HttpResponse<String> response = a.send(a.body("\"locale\": \"en\""), null);
		assertThat(json(response).get("locale").stringValue()).isEqualTo("en");
		assertThat(row(UUID.fromString(json(response).get("id").stringValue())).get("locale").stringValue()).isEqualTo("en");
	}

	@Test
	void ac_37_3_and_37_4_missingLocaleFallsBackToTheTenantLocale() throws Exception {
		String key = "only-es-" + UUID.randomUUID().toString().substring(0, 8);
		a.createTemplate(key, "NOTICE");
		int version = a.createVersion(key, "es-CR", "Hola {{firstName}}");
		a.operator(http().post("/v1/templates/" + key + "/versions/" + version + "/publish")).send();
		String body = a.body("\"locale\": \"en\"").replace(a.templateKey, key);
		double before = fallbackCount();

		HttpResponse<String> response = a.send(body, null);
		assertThat(response.statusCode()).isEqualTo(202);
		assertThat(json(response).get("locale").stringValue()).isEqualTo("es-CR");
		assertThat(fallbackCount()).isEqualTo(before + 1);

		assertThat(json(a.send(a.body(""), null)).get("locale").stringValue()).isEqualTo("es-CR");
	}

	@Test
	void ac_05_2_andExplicitVersionsMustHaveBeenPublished() throws Exception {
		int draft = a.createVersion(a.templateKey, "es-CR", "Borrador {{firstName}}");
		HttpResponse<String> draftResponse = a.send(a.body("\"templateVersion\": " + draft), null);
		assertThat(draftResponse.statusCode()).isEqualTo(422);
		assertThat(draftResponse.body()).contains("\"code\":\"TEMPLATE_NOT_PUBLISHED\"");

		a.operator(http().post("/v1/templates/" + a.templateKey + "/versions/" + draft + "/publish")).send();
		// Version 1 is now ARCHIVED but still usable when referenced explicitly (AC-05.2).
		HttpResponse<String> archived = a.send(a.body("\"templateVersion\": 1"), null);
		assertThat(archived.statusCode()).isEqualTo(202);
		assertThat(json(archived).get("templateVersion").intValue()).isEqualTo(1);
	}

	@Test
	void ac_05_4_templateWithoutAnyPublishedVersion() throws Exception {
		String key = "unpublished-" + UUID.randomUUID().toString().substring(0, 8);
		a.createTemplate(key, "NOTICE");
		HttpResponse<String> response = a.send(a.body("").replace(a.templateKey, key), null);
		assertThat(response.statusCode()).isEqualTo(422);
		assertThat(response.body()).contains("\"code\":\"TEMPLATE_NOT_PUBLISHED\"");
	}

	@Test
	void sendingNeedsTheEmailsSendScope() throws Exception {
		HttpResponse<String> response = http().post("/v1/emails")
			.header("Authorization", a.operator.bearer())
			.json(a.body(""))
			.send();
		assertThat(response.statusCode()).isEqualTo(403);
		assertThat(response.body()).contains("\"code\":\"INSUFFICIENT_SCOPE\"");
	}

	private double fallbackCount() throws Exception {
		String metrics = http().get("/actuator/prometheus").send().body();
		return metrics.lines()
			.filter(line -> line.startsWith("email_locale_fallback_total"))
			.mapToDouble(line -> Double.parseDouble(line.substring(line.lastIndexOf(' ') + 1)))
			.findFirst()
			.orElse(0);
	}

}
