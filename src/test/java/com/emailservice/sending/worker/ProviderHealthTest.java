package com.emailservice.sending.worker;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;

import com.emailservice.common.alert.Alerts;
import com.emailservice.support.MutableClock;

/** AC-24.1: the provider indicator from the last five minutes of results; NFR-11: breaker open too long. */
@ExtendWith(OutputCaptureExtension.class)
class ProviderHealthTest {

	MutableClock clock = new MutableClock();

	ProviderOutcomes outcomes = new ProviderOutcomes(clock);

	CircuitBreaker breaker = new CircuitBreaker(5, Duration.ofSeconds(60), clock);

	ProviderHealthIndicator indicator = new ProviderHealthIndicator(outcomes, breaker);

	void record(ProviderOutcomes.Outcome outcome, int times) {
		for (int i = 0; i < times; i++) {
			outcomes.record(outcome);
		}
	}

	@Test
	void ac_24_1_upWithoutCallsOrWithMostlySuccesses() {
		assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
		record(ProviderOutcomes.Outcome.SUCCESS, 4);
		record(ProviderOutcomes.Outcome.TRANSIENT, 3);
		assertThat(indicator.health().getStatus()).isEqualTo(Status.UP);
	}

	@Test
	void ac_24_1_degradedWhenHalfOfTheRecentCallsFailed() {
		record(ProviderOutcomes.Outcome.SUCCESS, 3);
		record(ProviderOutcomes.Outcome.TRANSIENT, 3);
		assertThat(indicator.health().getStatus().getCode()).isEqualTo("DEGRADED");

		clock.advance(Duration.ofMinutes(6));
		assertThat(indicator.health().getStatus()).as("only the last five minutes count").isEqualTo(Status.UP);
	}

	@Test
	void ac_24_1_downWithTheBreakerOpenOrAConfigurationError() {
		record(ProviderOutcomes.Outcome.CONFIGURATION, 1);
		assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);

		clock.advance(Duration.ofMinutes(6));
		breaker.onConfigurationError();
		assertThat(indicator.health().getStatus()).isEqualTo(Status.DOWN);
		assertThat(indicator.health().getDetails()).containsEntry("circuitBreaker", "OPEN");
	}

	@Test
	void nfr_11_theBreakerOpenForMoreThanTenMinutesAlertsOnce(CapturedOutput output) {
		ProviderAlerts alerts = new ProviderAlerts(breaker, new Alerts(clock));
		breaker.onConfigurationError();
		clock.advance(Duration.ofMinutes(10));
		alerts.run();
		assertThat(output.getAll()).doesNotContain("CIRCUIT_BREAKER_OPEN");

		// A failed probe reopens it: still the same outage.
		clock.advance(Duration.ofSeconds(61));
		breaker.permittedClaims(1);
		breaker.onTransientFailure();
		alerts.run();
		alerts.run();

		assertThat(output.getAll().split("\"alert\":\"CIRCUIT_BREAKER_OPEN\"", -1)).hasSize(2);
		breaker.onSuccess();
		assertThat(breaker.openFor()).isZero();
	}

}
