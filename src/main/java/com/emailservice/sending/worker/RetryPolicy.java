package com.emailservice.sending.worker;

import java.time.Duration;
import java.util.List;
import java.util.function.DoubleSupplier;

/**
 * Backoff of FR-13: after the n-th failed attempt wait RETRY_BACKOFF_SECONDS[n - 1] with ±20 %
 * jitter (AC-13.1), or the provider's Retry-After (AC-13.5). attempts counts the attempt that just
 * failed, since it was incremented when the message was claimed.
 */
final class RetryPolicy {

	private final List<Integer> backoffSeconds;

	private final int maxAttempts;

	private final DoubleSupplier random;

	RetryPolicy(List<Integer> backoffSeconds, int maxAttempts, DoubleSupplier random) {
		this.backoffSeconds = List.copyOf(backoffSeconds);
		this.maxAttempts = maxAttempts;
		this.random = random;
	}

	boolean exhausted(int attempts) {
		return attempts >= maxAttempts;
	}

	Duration delayAfter(int attempts, Duration retryAfter) {
		if (retryAfter != null && !retryAfter.isNegative()) {
			return retryAfter;
		}
		int index = Math.clamp(attempts - 1, 0, backoffSeconds.size() - 1);
		double jitter = 0.8 + 0.4 * random.getAsDouble();
		return Duration.ofMillis(Math.round(backoffSeconds.get(index) * 1_000 * jitter));
	}

}
