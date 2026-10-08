package com.emailservice.sending.worker;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;

/** AC-13.1, AC-13.3 and AC-13.5. */
class RetryPolicyTest {

	static final List<Integer> BACKOFF = List.of(60, 300, 900, 3_600, 21_600);

	@Test
	void ac_13_1_followsTheBackoffWithTwentyPercentJitter() {
		RetryPolicy lowest = new RetryPolicy(BACKOFF, 6, () -> 0.0);
		RetryPolicy highest = new RetryPolicy(BACKOFF, 6, () -> 1.0);
		assertThat(lowest.delayAfter(1, null)).isEqualTo(Duration.ofSeconds(48));
		assertThat(highest.delayAfter(1, null)).isEqualTo(Duration.ofSeconds(72));
		assertThat(lowest.delayAfter(2, null)).isEqualTo(Duration.ofSeconds(240));
		assertThat(highest.delayAfter(5, null)).isEqualTo(Duration.ofSeconds(25_920));
		assertThat(new RetryPolicy(BACKOFF, 6, () -> 0.5).delayAfter(3, null)).isEqualTo(Duration.ofSeconds(900));
	}

	@Test
	void ac_13_5_retryAfterReplacesTheBackoff() {
		assertThat(new RetryPolicy(BACKOFF, 6, () -> 0.5).delayAfter(1, Duration.ofSeconds(7)))
			.isEqualTo(Duration.ofSeconds(7));
	}

	@Test
	void ac_13_3_attemptsAreExhaustedAtMaxAttempts() {
		RetryPolicy policy = new RetryPolicy(BACKOFF, 6, () -> 0.5);
		assertThat(policy.exhausted(5)).isFalse();
		assertThat(policy.exhausted(6)).isTrue();
	}

}
