package com.emailservice.sending.worker;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import com.emailservice.support.MutableClock;

/** AC-13.9: open after N consecutive transient failures, a single probe after the open period. */
class CircuitBreakerTest {

	MutableClock clock = new MutableClock();

	CircuitBreaker breaker = new CircuitBreaker(5, Duration.ofSeconds(60), clock);

	void fail(int times) {
		for (int i = 0; i < times; i++) {
			breaker.onTransientFailure();
		}
	}

	@Test
	void ac_13_9_opensAfterFiveConsecutiveFailures() {
		fail(4);
		assertThat(breaker.permittedClaims(4)).isEqualTo(4);
		fail(1);
		assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.OPEN);
		assertThat(breaker.permittedClaims(4)).isZero();
		assertThat(breaker.blocksSending()).isTrue();
	}

	@Test
	void aSuccessResetsTheCount() {
		fail(4);
		breaker.onSuccess();
		fail(4);
		assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.CLOSED);
	}

	@Test
	void ac_13_9_afterSixtySecondsExactlyOneProbeGoesThrough() {
		fail(5);
		clock.advance(Duration.ofSeconds(59));
		assertThat(breaker.permittedClaims(4)).isZero();

		clock.advance(Duration.ofSeconds(1));
		assertThat(breaker.blocksSending()).isFalse();
		assertThat(breaker.permittedClaims(4)).isOne();
		assertThat(breaker.permittedClaims(4)).as("probe in flight").isZero();

		breaker.onSuccess();
		assertThat(breaker.permittedClaims(4)).isEqualTo(4);
	}

	@Test
	void aFailedProbeReopensForAnotherPeriod() {
		fail(5);
		clock.advance(Duration.ofSeconds(60));
		assertThat(breaker.permittedClaims(4)).isOne();

		breaker.onTransientFailure();

		assertThat(breaker.state()).isEqualTo(CircuitBreaker.State.OPEN);
		clock.advance(Duration.ofSeconds(59));
		assertThat(breaker.permittedClaims(4)).isZero();
		clock.advance(Duration.ofSeconds(1));
		assertThat(breaker.permittedClaims(4)).isOne();
	}

	@Test
	void anInconclusiveProbeFreesItsSlot() {
		fail(5);
		clock.advance(Duration.ofSeconds(60));
		assertThat(breaker.permittedClaims(4)).isOne();

		breaker.onInconclusive();

		assertThat(breaker.permittedClaims(4)).isOne();
	}

	@Test
	void aConfigurationErrorOpensAtOnce() {
		breaker.onConfigurationError();
		assertThat(breaker.permittedClaims(4)).isZero();
	}

}
