package com.emailservice.sending.worker;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.emailservice.provider.SendException;
import com.emailservice.sending.SendingTestSupport;
import com.emailservice.support.AdminApi;
import com.emailservice.support.Webhooks;

import tools.jackson.databind.JsonNode;

/** FR-19 and FR-20 through the API, with the worker and signed webhooks producing real state. */
class EmailQueryTest extends WorkerTest {

	SendingTestSupport a;

	@BeforeEach
	void setUp() throws Exception {
		a = new SendingTestSupport(http(), "SECURITY");
	}

	UUID accept(String body) throws Exception {
		HttpResponse<String> response = a.send(body, null);
		assertThat(response.statusCode()).as(response.body()).isEqualTo(202);
		return UUID.fromString(SendingTestSupport.json(response).get("id").stringValue());
	}

	HttpResponse<String> get(AdminApi.Key key, String path) throws Exception {
		return http().get(path).header("Authorization", key.bearer()).send();
	}

	JsonNode detail(UUID id) throws Exception {
		HttpResponse<String> response = get(a.product, "/v1/emails/" + id);
		assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
		return SendingTestSupport.json(response);
	}

	List<String> ids(String query) throws Exception {
		HttpResponse<String> response = get(a.product, "/v1/emails" + query);
		assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
		List<String> ids = new ArrayList<>();
		SendingTestSupport.json(response).get("data").forEach(item -> ids.add(item.get("id").stringValue()));
		return ids;
	}

	@Test
	void ac_19_1_returnsTheStateTheAttemptsAndTheEventHistory() throws Exception {
		UUID id = accept(a.body("\"cc\": [\"cc@example.test\"], \"tags\": [\"reset\"], "
				+ "\"metadata\": {\"userId\": \"u_1\"}"));
		worker.runOnce();
		new Webhooks(http()).post(Webhooks.newEventId(), Webhooks.emailEvent("email.delivered", "stub-" + id, id, ""));

		JsonNode body = detail(id);

		assertThat(body.get("id").stringValue()).isEqualTo(id.toString());
		assertThat(body.get("status").stringValue()).isEqualTo("DELIVERED");
		assertThat(body.get("category").stringValue()).isEqualTo("SECURITY");
		assertThat(body.get("templateKey").stringValue()).isEqualTo(a.templateKey);
		assertThat(body.get("templateVersion").intValue()).isOne();
		assertThat(body.get("locale").stringValue()).isEqualTo("es-CR");
		assertThat(body.get("to").stringValue()).isEqualTo("ana@example.test");
		assertThat(body.get("cc").get(0).stringValue()).isEqualTo("cc@example.test");
		assertThat(body.get("from").stringValue()).isEqualTo("no-reply@sender.test");
		assertThat(body.get("tags").get(0).stringValue()).isEqualTo("reset");
		assertThat(body.at("/metadata/userId").stringValue()).isEqualTo("u_1");
		assertThat(body.get("attempts").intValue()).isOne();
		assertThat(body.get("provider").stringValue()).isEqualTo("stub");
		assertThat(body.get("providerMessageId").stringValue()).isEqualTo("stub-" + id);
		assertThat(body.get("sentAt").isNull()).isFalse();
		assertThat(body.get("deliveredAt").isNull()).isFalse();
		assertThat(body.get("events")).hasSize(1);
		assertThat(body.at("/events/0/type").stringValue()).isEqualTo("DELIVERED");
		assertThat(body.has("renderedHtml")).as("only with storeRenderedContent").isFalse();
	}

	@Test
	void ac_19_1_theLastErrorShowsWhileTheMessageIsStillRetrying() throws Exception {
		UUID id = accept(a.body(""));
		sender.behave(email -> {
			throw SendException.transientFailure("PROVIDER_UNAVAILABLE", "down", null);
		});
		worker.runOnce();

		JsonNode body = detail(id);

		assertThat(body.get("status").stringValue()).isEqualTo("QUEUED");
		assertThat(body.get("lastError").stringValue()).isEqualTo("PROVIDER_UNAVAILABLE");
		assertThat(body.get("failureCode").isNull()).isTrue();
	}

	@Test
	void ac_19_2_anotherTenantsMessageIsNotFound() throws Exception {
		UUID id = accept(a.body(""));
		SendingTestSupport b = new SendingTestSupport(http(), "SECURITY");

		HttpResponse<String> foreign = get(b.product, "/v1/emails/" + id);
		assertThat(foreign.statusCode()).isEqualTo(404);
		assertThat(foreign.body()).contains("MESSAGE_NOT_FOUND");
		assertThat(get(a.product, "/v1/emails/" + UUID.randomUUID()).statusCode()).isEqualTo(404);
		assertThat(get(a.product, "/v1/emails/not-a-uuid").statusCode()).isEqualTo(404);

		AdminApi.Key sendOnly = a.admin.issueKey(a.tenant, "{\"name\": \"send-only\", \"scopes\": [\"emails:send\"]}");
		assertThat(get(sendOnly, "/v1/emails/" + id).statusCode()).isEqualTo(403);
		assertThat(get(sendOnly, "/v1/emails").statusCode()).isEqualTo(403);
	}

	@Test
	void ac_19_3_renderedContentOnlyWithTheFlagAndWithoutSensitiveValues() throws Exception {
		a.admin.patchTenant(a.tenant, "{\"storeRenderedContent\": true}");
		UUID id = accept(a.body(""));
		worker.runOnce();

		JsonNode stored = detail(id);
		assertThat(stored.get("renderedSubject").stringValue()).isEqualTo("Hola Ana");
		assertThat(stored.get("renderedHtml").stringValue()).contains("[REDACTED]").doesNotContain("reset?t=1");
		assertThat(stored.get("renderedText").stringValue()).contains("[REDACTED]").doesNotContain("reset?t=1");
		// What was actually sent did carry the link.
		assertThat(sender.sent().getFirst().text()).contains("https://app.example.test/reset?t=1");

		a.admin.patchTenant(a.tenant, "{\"storeRenderedContent\": false}");
		assertThat(detail(id).has("renderedHtml")).isFalse();
	}

	@Test
	void ac_20_1_filtersByStatusTemplateRecipientTagAndDates() throws Exception {
		String other = a.publishTemplate("SECURITY", "<p>Hola {{firstName}}</p>",
				"{\"required\": [\"firstName\"], \"properties\": {\"firstName\": {\"type\": \"string\"}}}");
		UUID plain = accept(a.body(""));
		UUID tagged = accept(a.body("\"tags\": [\"invoice\"]"));
		UUID toBea = accept(a.bodyTo("Bea@Example.test", ""));
		UUID otherTemplate = accept("{\"templateKey\": \"" + other + "\", \"to\": {\"email\": \"ana@example.test\"}, "
				+ "\"variables\": {\"firstName\": \"Ana\"}}");
		UUID old = accept(a.body(""));
		Instant now = Instant.now();
		WorkerTest.sql("UPDATE email_message SET created_at = now() - interval '3 days' WHERE id = ?", old);
		WorkerTest.sql("UPDATE email_message SET status = 'CANCELED', finalized_at = now() WHERE id = ?", plain);

		assertThat(ids("?status=CANCELED")).containsExactly(plain.toString());
		assertThat(ids("?templateKey=" + other)).containsExactly(otherTemplate.toString());
		assertThat(ids("?to=bea@example.test")).containsExactly(toBea.toString());
		assertThat(ids("?tag=invoice")).containsExactly(tagged.toString());
		assertThat(ids("?createdBefore=" + now.minus(1, ChronoUnit.DAYS))).containsExactly(old.toString());
		assertThat(ids("?createdAfter=" + now.minus(1, ChronoUnit.DAYS) + "&tag=invoice"))
			.containsExactly(tagged.toString());
		assertThat(ids("?templateKey=does-not-exist")).isEmpty();
		// Default: newest first.
		assertThat(ids("")).containsExactly(otherTemplate.toString(), toBea.toString(), tagged.toString(),
				plain.toString(), old.toString());
	}

	@Test
	void ac_20_1_cursorPagesHaveNoGapsOrDuplicatesEvenWithNewMessages() throws Exception {
		List<String> created = new ArrayList<>();
		for (int i = 0; i < 7; i++) {
			created.add(0, accept(a.body("")).toString());
		}

		List<String> seen = new ArrayList<>();
		String cursor = null;
		int pages = 0;
		do {
			HttpResponse<String> response = get(a.product,
					"/v1/emails?limit=3" + (cursor == null ? "" : "&cursor=" + cursor));
			JsonNode page = SendingTestSupport.json(response);
			page.get("data").forEach(item -> seen.add(item.get("id").stringValue()));
			cursor = page.get("nextCursor").isNull() ? null : page.get("nextCursor").stringValue();
			if (pages++ == 0) {
				accept(a.body(""));
			}
		}
		while (cursor != null);

		assertThat(seen).containsExactlyElementsOf(created);
		assertThat(pages).isEqualTo(3);
	}

	@Test
	void ac_20_1_invalidParametersAre422() throws Exception {
		for (String query : new String[] { "?cursor=forged", "?status=LOST", "?limit=0", "?limit=101",
				"?createdAfter=yesterday" }) {
			HttpResponse<String> response = get(a.product, "/v1/emails" + query);
			assertThat(response.statusCode()).as(query).isEqualTo(422);
			assertThat(response.body()).contains("VALIDATION_ERROR");
		}
	}

	@Test
	void ac_20_3_aTenantOnlyListsItsOwnMessages() throws Exception {
		UUID mine = accept(a.body(""));
		SendingTestSupport b = new SendingTestSupport(http(), "SECURITY");

		HttpResponse<String> response = get(b.product, "/v1/emails?limit=100");

		assertThat(response.statusCode()).isEqualTo(200);
		assertThat(response.body()).doesNotContain(mine.toString());
		assertThat(ids("?limit=100")).contains(mine.toString());
	}

}
