package com.emailservice.sending.worker;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;

/** AC-12.6: one worker instance never calls the provider more than WORKER_PROVIDER_RPS times a second. */
class ProviderRateLimiterTest {

	static final long SECOND = TimeUnit.SECONDS.toNanos(1);

	/** Time only moves when someone sleeps, so the test is exact. */
	static final class FakeTicker implements ProviderRateLimiter.Ticker {

		long now = 1_000 * SECOND;

		@Override
		public synchronized long nanoTime() {
			return now;
		}

		@Override
		public synchronized void sleepNanos(long nanos) {
			now += nanos;
		}

	}

	@Test
	void ac_12_6_callsAreSpacedOneIntervalApartWithoutBurst() throws Exception {
		FakeTicker ticker = new FakeTicker();
		ProviderRateLimiter limiter = new ProviderRateLimiter(4, ticker);
		List<Long> times = new ArrayList<>();
		for (int i = 0; i < 9; i++) {
			limiter.acquire();
			times.add(ticker.now);
		}
		for (int i = 1; i < times.size(); i++) {
			assertThat(times.get(i) - times.get(i - 1)).isEqualTo(SECOND / 4);
		}
		assertThat(times.getLast() - times.getFirst()).isEqualTo(2 * SECOND);
	}

	@Test
	void anIdleLimiterDoesNotMakeTheNextCallWait() throws Exception {
		FakeTicker ticker = new FakeTicker();
		ProviderRateLimiter limiter = new ProviderRateLimiter(2, ticker);
		limiter.acquire();
		ticker.now += 10 * SECOND;
		long before = ticker.now;
		limiter.acquire();
		assertThat(ticker.now).isEqualTo(before);
	}

	@Test
	void ac_12_6_concurrentWorkersNeverExceedTheRateInAnyOneSecondWindow() throws Exception {
		int rps = 10;
		ProviderRateLimiter limiter = new ProviderRateLimiter(rps, ProviderRateLimiter.Ticker.SYSTEM);
		ConcurrentLinkedQueue<Long> calls = new ConcurrentLinkedQueue<>();
		try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
			for (int i = 0; i < 25; i++) {
				executor.submit(() -> {
					limiter.acquire();
					calls.add(System.nanoTime());
					return null;
				});
			}
		}
		List<Long> sorted = calls.stream().sorted().toList();
		assertThat(sorted).hasSize(25);
		// Any rps + 1 consecutive calls span at least one second, minus how late a thread may wake.
		for (int i = rps; i < sorted.size(); i++) {
			assertThat(sorted.get(i) - sorted.get(i - rps)).isGreaterThanOrEqualTo(SECOND - TimeUnit.MILLISECONDS.toNanos(25));
		}
	}

}
