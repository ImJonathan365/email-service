package com.emailservice.common.alert;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import com.emailservice.support.MutableClock;

/** NFR-11 (owner, 2026-10-12): structured ERROR lines, once per alert and subject every 15 minutes. */
@ExtendWith(OutputCaptureExtension.class)
class AlertsTest {

	MutableClock clock = new MutableClock();

	Alerts alerts = new Alerts(clock);

	@Test
	void theSameAlertForTheSameSubjectIsDeduplicatedForFifteenMinutes(CapturedOutput output) {
		assertThat(alerts.raise("QUEUE_AGE", "queue", "first")).isTrue();
		assertThat(alerts.raise("QUEUE_AGE", "queue", "again")).isFalse();
		assertThat(alerts.raise("TENANT_PAUSED", "colmena", "another alert")).isTrue();
		assertThat(alerts.raise("TENANT_PAUSED", "pipemend", "another subject")).isTrue();

		clock.advance(Duration.ofMinutes(14));
		assertThat(alerts.raise("QUEUE_AGE", "queue", "still deduplicated")).isFalse();
		clock.advance(Duration.ofMinutes(1));
		assertThat(alerts.raise("QUEUE_AGE", "queue", "emitted again")).isTrue();

		assertThat(output.getAll()).contains("first", "emitted again").doesNotContain("still deduplicated");
	}

}
