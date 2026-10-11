package com.emailservice.events;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.sql.Connection;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.emailservice.support.IntegrationTest;
import com.emailservice.support.PostgresTestDatabase;
import com.emailservice.support.PostgresTestDatabase.Role;
import com.emailservice.support.Webhooks;

/**
 * AC-16.7: only a database failure turns into 503, so the provider retries the delivery. The
 * failures are real ones caused through the owner role and undone right after, so this test runs
 * in the shared context without mocks.
 */
class WebhookDatabaseFailureTest extends IntegrationTest {

	HttpResponse<String> deliver(String eventId) throws Exception {
		return new Webhooks(http()).post(eventId,
				Webhooks.emailEvent("email.delivered", UUID.randomUUID().toString(), null, ""));
	}

	static void asOwner(String statement) throws Exception {
		try (Connection owner = PostgresTestDatabase.connect(Role.OWNER)) {
			owner.createStatement().execute(statement);
		}
	}

	@Test
	void ac_16_7_aDatabaseErrorAnswers503AndStoresNothing() throws Exception {
		String eventId = Webhooks.newEventId();
		asOwner("REVOKE INSERT ON email_event FROM email_system");
		HttpResponse<String> response;
		try {
			response = deliver(eventId);
		}
		finally {
			asOwner("GRANT INSERT ON email_event TO email_system");
		}

		assertThat(response.statusCode()).isEqualTo(503);
		assertThat(response.body()).contains("SERVICE_UNAVAILABLE");
		assertThat(Webhooks.countEvents(eventId)).isZero();
		// Once the database is back, the provider's retry of the same event succeeds.
		assertThat(deliver(eventId).statusCode()).isEqualTo(204);
		assertThat(Webhooks.countEvents(eventId)).isOne();
	}

	@Test
	void ac_16_7_aMissingTableIsAlso503() throws Exception {
		asOwner("ALTER TABLE email_event RENAME TO email_event_unavailable");
		HttpResponse<String> response;
		try {
			response = deliver(Webhooks.newEventId());
		}
		finally {
			asOwner("ALTER TABLE email_event_unavailable RENAME TO email_event");
		}

		assertThat(response.statusCode()).isEqualTo(503);
	}

}
