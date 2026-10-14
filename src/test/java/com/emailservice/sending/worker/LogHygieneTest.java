package com.emailservice.sending.worker;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Arrays;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import com.emailservice.events.EmailAddresses;
import com.emailservice.sending.SendingTestSupport;
import com.emailservice.support.Webhooks;

/** AC-24.3: JSON logs correlated by tenantSlug and messageId, never with an address or a variable. */
@ExtendWith(OutputCaptureExtension.class)
class LogHygieneTest extends WorkerTest {

	@Test
	void ac_24_3_aFullSendLeavesNoAddressOrVariableInTheLogs(CapturedOutput output) throws Exception {
		SendingTestSupport a = new SendingTestSupport(http(), "SECURITY");
		String to = SendingTestSupport.uniqueAddress();
		HttpResponse<String> accepted = a.send(a.bodyTo(to, "\"cc\": [\"copy-" + to + "\"]"), null);
		UUID id = UUID.fromString(SendingTestSupport.json(accepted).get("id").stringValue());
		worker.runOnce();
		new Webhooks(http()).post(Webhooks.newEventId(),
				Webhooks.emailEvent("email.complained", "stub-" + id, id, "", Instant.now(), to));

		String logs = output.getAll();
		assertThat(logs).doesNotContain(to, "Ana", "https://app.example.test/reset?t=1", "Hola Ana");
		String complaint = Arrays.stream(logs.split("\n"))
			.filter(line -> line.contains("Spam complaint"))
			.findFirst()
			.orElseThrow();
		assertThat(complaint).contains("\"tenantSlug\":\"" + a.tenant.slug() + "\"", "\"messageId\":\"" + id + "\"");
	}

	@Test
	void ac_24_3_addressesAreMaskedWhenTheyMustAppear() {
		assertThat(EmailAddresses.masked("ana@example.com")).isEqualTo("a***@example.com");
		assertThat(EmailAddresses.masked("not-an-address")).isEqualTo("***");
	}

}
