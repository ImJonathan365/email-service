package com.emailservice.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;

import java.net.http.HttpResponse;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.QueryTimeoutException;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.emailservice.support.IntegrationTest;
import com.emailservice.support.Webhooks;

/** AC-16.7: only a database failure turns into 503, so the provider retries the delivery. */
class WebhookDatabaseFailureTest extends IntegrationTest {

	@MockitoSpyBean
	EventRepository events;

	@Test
	void ac_16_7_aDatabaseErrorAnswers503AndStoresNothing() throws Exception {
		doThrow(new DataAccessResourceFailureException("connection refused")).when(events)
			.insert(any(), any(), any(), any(), any(), any(), any());
		String eventId = Webhooks.newEventId();

		HttpResponse<String> response = new Webhooks(http()).post(eventId,
				Webhooks.emailEvent("email.delivered", UUID.randomUUID().toString(), null, ""));

		assertThat(response.statusCode()).isEqualTo(503);
		assertThat(response.body()).contains("SERVICE_UNAVAILABLE");
		assertThat(Webhooks.countEvents(eventId)).isZero();
	}

	@Test
	void ac_16_7_anyOtherDataAccessErrorIsAlso503() throws Exception {
		doThrow(new QueryTimeoutException("statement timeout")).when(events)
			.insert(any(), any(), any(), any(), any(), any(), any());

		assertThat(new Webhooks(http())
			.post(Webhooks.newEventId(), Webhooks.emailEvent("email.delivered", UUID.randomUUID().toString(), null, ""))
			.statusCode()).isEqualTo(503);
	}

}
